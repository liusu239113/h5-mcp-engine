package com.mcp.h5engine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * TapTap 官方 CLI（`taptap-cli`）的封装 —— 发布页和 AI 工具都走这里。
 *
 * ## 为什么是一个**独立二进制**，而不是复用已有的 node 运行时
 *
 * 官方发的是六个平台的**预编译可执行文件**（package/bin/taptap-cli-linux-arm64 等）。
 * 我验过它的 ELF 头：aarch64、**静态链接**（没有 PT_INTERP、不依赖任何 lib*.so）——
 * 也就是说它跟 libc 是 glibc 还是 bionic 无关，Android 上能直接跑。
 * 所以不需要 node、不需要 musl 加载器，塞进 jniLibs 就行。
 *
 * ## 为什么必须放 jniLibs
 *
 * Android 从 API 29 起**禁止从应用私有目录 exec**（SELinux W^X）。
 * 唯一允许执行的地方是 `nativeLibraryDir`（也就是 APK 里的 `lib/<abi>/`）。
 * 所以 CI 会把二进制改名成 `libtaptapcli.so` 塞进 `jniLibs/arm64-v8a/` ——
 * 叫 .so 只是为了让打包流程认它，它本身还是个可执行文件。
 *
 * ## 输出约定
 *
 * CLI 的所有命令都吐一个 JSON 信封：`{ ok, data, meta, error }`。
 * [run] 直接把信封解出来，调用方按 [Result.ok] 和 [Result.data] 走，
 * 不用各自去猜文本格式。
 */
object TapCli {

    /** 打进 APK 的二进制名（见上面的说明：改名只是为了过打包流程） */
    private const val BIN_NAME = "libtaptapcli.so"

    /**
     * 凭证目录。走 TAPTAP_CLI_HOME 环境变量告诉 CLI，
     * **不要**让它用默认的 ~/.taptap —— 那个路径在 Android 上可能不可写。
     */
    fun home(ctx: Context): File = File(McpRt.rtDir(ctx), "home/taptapcli").apply { mkdirs() }

    private fun bin(ctx: Context): File =
        File(ctx.applicationInfo.nativeLibraryDir, BIN_NAME)

    /** 二进制在不在（没打进包时给用户一句人话，而不是空指针） */
    fun available(ctx: Context): Boolean = runCatching {
        val f = bin(ctx)
        f.isFile && f.canExecute() && f.length() > 1_000_000
    }.getOrDefault(false)

    /**
     * 一条命令的结果。
     *
     * @param ok      CLI 信封里的 ok 字段（进程退出码为 0 但业务失败时也是 false）
     * @param data    ok 时的业务数据；拿不到就是空 JSONObject
     * @param message 给用户看的一句话（失败原因、或 ok 时 CLI 给的提示）
     * @param raw     原始输出，排查用
     */
    data class Result(
        val ok: Boolean,
        val data: JSONObject,
        val message: String,
        val raw: String
    ) {
        fun str(vararg path: String): String {
            var cur: Any = data
            for (p in path) {
                cur = when (cur) {
                    is JSONObject -> cur.opt(p) ?: return ""
                    else -> return ""
                }
            }
            return cur?.toString().orEmpty()
        }

        fun obj(key: String): JSONObject? = data.optJSONObject(key)
        fun arr(key: String) = data.optJSONArray(key)
    }

    /**
     * 跑一条 CLI 命令（阻塞，必须放子线程）。
     *
     * @param args   不含可执行文件本身的参数，如 listOf("app","+list","--dev-id","123")
     * @param cwd    工作目录。**upload 会校验「文件必须在工作目录内」**，
     *               所以传素材所在目录最省事，别传工程根以外的地方。
     * @param stdin  需要喂进去的内容（目前没用上，留着给交互式命令）
     */
    fun run(
        ctx: Context,
        args: List<String>,
        cwd: File? = null,
        timeoutMs: Long = 120_000,
        stdin: String? = null
    ): Result {
        val exe = bin(ctx)
        if (!available(ctx)) {
            return Result(
                false, JSONObject(),
                "发布工具没随安装包进来（缺少 $BIN_NAME）。装最新版本即可；" +
                    "如果是自己编的包，注意 CI 要先把 taptap-cli 下下来。",
                ""
            )
        }

        return try {
            val cmd = mutableListOf(exe.absolutePath)
            cmd += args
            // 统一要 JSON 输出。
            //
            // ⚠️ 正确的 flag 是 **--json**，不是 --format。踩过的坑：
            //   · 我一开始追加的是 `--format json`，`auth qrcode` 直接报
            //     `unknown flag "--format"` —— 登录因此一直失败；
            //   · 而 `--format` 只有 metadata 那批命令认（app / developer 的某些子命令）。
            // 所以：默认加 `--json`（auth / upload 这些认它），
            // 只有明确属于 metadata 服务的才加 `--format json`。
            if (args.none { it == "--format" || it == "--json" }) {
                if (wantsFormatFlag(args)) cmd += listOf("--format", "json")
                else cmd += listOf("--json")
            }

            val pb = ProcessBuilder(cmd)
            pb.directory(cwd?.takeIf { it.isDirectory } ?: ctx.filesDir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["HOME"] = home(ctx).absolutePath
            env["TMPDIR"] = home(ctx).absolutePath
            env["TAPTAP_CLI_HOME"] = home(ctx).absolutePath
            // 静态二进制不需要解释器，但把 PATH 指清楚总没坏处
            env["PATH"] = ctx.applicationInfo.nativeLibraryDir

            val p = pb.start()
            runCatching {
                if (stdin != null) {
                    p.outputStream.use { it.write(stdin.toByteArray()); it.flush() }
                } else {
                    p.outputStream.close()
                }
            }

            val sb = StringBuilder()
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { sb.appendLine(it) } }
            }.apply { isDaemon = true }
            reader.start()

            if (!waitExit(p, timeoutMs)) {
                runCatching { p.destroy() }
                return Result(false, JSONObject(), "命令超时（${timeoutMs / 1000} 秒），已终止", sb.toString())
            }
            reader.join(1200)

            val raw = sb.toString().trim()
            parseEnvelope(raw)
        } catch (t: Throwable) {
            Result(false, JSONObject(), "跑不起来：${t.javaClass.simpleName}: ${t.message}", "")
        }
    }

    /**
     * 解 CLI 的 JSON 信封。
     *
     * 有的命令会先打几行进度再打 JSON，所以**从后往前**找第一个能解析成信封的对象，
     * 而不是傻傻地只看第一行。
     */
    private fun parseEnvelope(raw: String): Result {
        if (raw.isBlank()) return Result(false, JSONObject(), "没有任何输出", "")

        val candidates = raw.lines().asReversed().filter { it.trim().startsWith("{") }
        for (line in candidates) {
            val o = runCatching { JSONObject(line.trim()) }.getOrNull() ?: continue
            if (!o.has("ok") && !o.has("data")) continue
            val ok = o.optBoolean("ok", false)
            val data = o.optJSONObject("data") ?: JSONObject()
            val err = o.optJSONObject("error")
            val msg = when {
                ok -> o.optJSONObject("meta")?.optString("message", "").orEmpty()
                err != null -> err.optString("message", "")
                    .ifBlank { err.optString("code", "") }
                    .ifBlank { "命令执行失败" }
                else -> "命令执行失败"
            }
            return Result(ok, data, msg, raw)
        }

        // 不是信封（比如二进制没起来、或者打印了别的）—— 把原文截一段给用户看
        return Result(false, JSONObject(), raw.take(500), raw)
    }

    /**
     * 这条命令认不认 `--format json`（而不是 `--json`）。
     *
     * 实测（在真二进制上逐个 --help 过）：
     *   · `auth *`  —— 认 `--json`，**不认** `--format`（报了 unknown flag，登录就是死在这）；
     *   · `upload`  —— 认 `--format json`（`--help` 里写着）；
     *   · `app` / `developer` —— 认 `--format json`；
     *   · 拿不准的一律走 `--json`：多认一个参数的命令很多，但**不认就会直接失败**，
     *     而少了它顶多拿到纯文本（[parseEnvelope] 有兜底，仍能把原文交出去）。
     */
    private fun wantsFormatFlag(args: List<String>): Boolean {
        val head = args.firstOrNull().orEmpty()
        val second = args.getOrNull(1).orEmpty()
        return when (head) {
            "auth" -> false
            "app", "developer", "skills", "schema" -> true
            "upload" -> true
            // 兜底：只看第一个词不够时，再看子命令是不是 metadata 那批
            else -> second in setOf("analyze-app-status", "list-packages", "list-app-versions")
        }
    }

    private fun waitExit(p: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue()
                return true
            } catch (e: IllegalThreadStateException) {
                Thread.sleep(120)
            }
        }
        return false
    }

    // ==================== 常用操作的语义封装 ====================

    /** 认证状态：没登录 / 已登录。加 --json 才有结构化输出 */
    fun authStatus(ctx: Context): Result =
        run(ctx, listOf("auth", "status"), timeoutMs = 25_000)

    /**
     * 开始登录：**官方给 AI 用的那套**（`auth login --no-wait --json`）。
     *
     * 返回里带 `verification_url` / `device_code` —— 把 url 给用户去点，
     * 然后拿 device_code 去 [authLoginPoll] 轮询直到授权完成。
     *
     * ⚠️ 别用 `auth qrcode`：那个是**拿一个 URL 去生成二维码图片**的，
     * 不是「生成授权链接」。我一开始就是当成后者用的，所以永远拿不到链接。
     */
    fun authLoginStart(ctx: Context): Result =
        run(ctx, listOf("auth", "login", "--no-wait"), timeoutMs = 60_000)

    /**
     * 拿着 device_code 轮询，直到用户在他那边点完授权。
     * 会一直阻塞到授权成功 / 过期，所以超时给得宽。
     */
    fun authLoginPoll(ctx: Context, deviceCode: String, timeoutMs: Long = 10 * 60_000 + 30_000): Result =
        run(ctx, listOf("auth", "login", "--device-code", deviceCode), timeoutMs = timeoutMs)

    /** 退出登录 */
    fun authLogout(ctx: Context): Result =
        run(ctx, listOf("auth", "logout"), timeoutMs = 25_000)

    /** 当前账号下的游戏列表（用来选「要发布哪一个」） */
    fun appList(ctx: Context, devId: String): Result =
        run(ctx, listOf("app", "+list", "--dev-id", devId), timeoutMs = 60_000)

    /** 厂商（开发者）列表 —— 首次进入发布页时用来定位账号 */
    fun developerList(ctx: Context): Result =
        run(ctx, listOf("developer", "+list"), timeoutMs = 60_000)

    /**
     * 资料完整度分析：缺什么、能不能提审。
     * 发布页那三个分组（基本信息 / 游戏资料 / 宣传物料）的进度就来自这里。
     */
    fun analyzeStatus(ctx: Context, devId: String, appId: String): Result =
        run(
            ctx,
            listOf("app", "analyze-app-status", "--dev-id", devId, "--app-id", appId),
            timeoutMs = 90_000
        )

    /**
     * 上传一张素材（图片）。
     *
     * ⚠️ CLI 会校验「文件必须在 cwd 之内」，所以这里**把 cwd 设成该文件所在目录**、
     * 并且只传文件名 —— 传绝对路径会被拒。
     */
    fun uploadImage(
        ctx: Context,
        file: File,
        devId: String,
        appId: String,
        dryRun: Boolean = false
    ): Result {
        val dir = file.parentFile ?: ctx.filesDir
        val args = mutableListOf(
            "upload", file.name,
            "--dev-id", devId, "--app-id", appId
        )
        if (dryRun) args += "--dry-run" else args += "--yes"
        return run(ctx, args, cwd = dir, timeoutMs = 180_000)
    }

    /**
     * 保存资料修改（改简介 / 换 icon 等）。
     *
     * @param changes 一个 JSON 串，字段名照 CLI 的 schema 来
     */
    fun saveChanges(
        ctx: Context,
        devId: String,
        appId: String,
        changesJson: String,
        idempotencyKey: String,
        dryRun: Boolean = false
    ): Result {
        val args = mutableListOf(
            "app", "save-changes",
            "--dev-id", devId, "--app-id", appId,
            "--data", changesJson,
            "--idempotency-key", idempotencyKey
        )
        if (!dryRun) args += "--yes"
        return run(ctx, args, timeoutMs = 120_000)
    }

    /**
     * 提审。
     *
     * ⚠️ 这是 high-risk-write：CLI 要求**显式** `--yes`，而且必须带上
     * prepare-review-snapshot 给出的 review_fingerprint，否则会被拒。
     * 所以调用方务必先跑快照，把指纹原样带回来。
     */
    fun submitReview(
        ctx: Context,
        devId: String,
        appId: String,
        dataJson: String,
        idempotencyKey: String,
        confirmed: Boolean
    ): Result {
        val args = mutableListOf(
            "app", "submit-app-review",
            "--dev-id", devId, "--app-id", appId,
            "--data", dataJson,
            "--idempotency-key", idempotencyKey
        )
        if (confirmed) args += "--yes"
        return run(ctx, args, timeoutMs = 180_000)
    }

    /** 生成审核复核快照（拿到 review_fingerprint，提审时要原样带上） */
    fun prepareReviewSnapshot(ctx: Context, devId: String, appId: String): Result =
        run(
            ctx,
            listOf(
                "app", "prepare-review-snapshot",
                "--dev-id", devId, "--app-id", appId,
                "--data", """{"release_schedule":{"kind":"immediate"}}"""
            ),
            timeoutMs = 120_000
        )

    /** 提审前预检（不提交，只报阻塞项） */
    fun precheckReview(ctx: Context, devId: String, appId: String, dataJson: String): Result =
        run(
            ctx,
            listOf(
                "app", "precheck-app-review",
                "--dev-id", devId, "--app-id", appId,
                "--data", dataJson
            ),
            timeoutMs = 120_000
        )

    /** 包体列表（看线上包 / 待处理包） */
    fun listPackages(ctx: Context, devId: String, appId: String): Result =
        run(
            ctx,
            listOf("app", "list-packages", "--dev-id", devId, "--app-id", appId),
            timeoutMs = 90_000
        )

    /** 版本列表 */
    fun listVersions(ctx: Context, devId: String, appId: String): Result =
        run(
            ctx,
            listOf("app", "list-app-versions", "--dev-id", devId, "--app-id", appId),
            timeoutMs = 90_000
        )

    /** 通用兜底：AI 想调别的子命令时走它（参数自己拼，但仍然是白名单校验过的） */
    fun raw(ctx: Context, args: List<String>, cwd: File? = null, timeoutMs: Long = 120_000): Result =
        run(ctx, args, cwd = cwd, timeoutMs = timeoutMs)
}
