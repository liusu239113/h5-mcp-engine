package com.mcp.h5engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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

    private const val TAG = "hexoraTapCli"

    /** 打进 APK 的二进制名（见上面的说明：改名只是为了过打包流程） */
    private const val BIN_NAME = "libtaptapcli.so"

    /** 随包携带的 CA 根证书（assets/ca/cacert.pem）。见 [caBundle] 的说明。 */
    private const val CA_ASSET = "ca/cacert.pem"

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
     * 把随包的 CA 根证书释放到磁盘，返回路径（失败返回 null）。
     *
     * ## 为什么非要有这个
     *
     * taptap-cli 是**静态链接的 Go 二进制**，它用的是 Go 自己的 crypto/x509 ——
     * 而 Go 的证书加载**只认 Linux 那几个固定路径**：
     *
     *     /etc/ssl/certs/ca-certificates.crt
     *     /etc/pki/tls/certs/ca-bundle.crt
     *     /etc/ssl/ca-bundle.pem  …（就这几个）
     *
     * **Android 上一个都没有**（它的信任库在 /system/etc/security/cacerts，
     * 而且是个目录、格式也不一样，Go 根本不看）。
     *
     * 结果：TLS 握手时**找不到任何可信根**，直接失败。CLI 把它包装成
     *     {"type":"network","subtype":"transport","message":"TapTap OAuth request failed"}
     * —— 看起来像「网络不通」，实际是**证书验证失败**。
     *
     * 这就是「登录一直失败」的根因。我一开始误判成代理问题，改了两版都没修好：
     * 代理根本不是原因（直连 + 代理都失败，因为**两条路都要过 TLS**）。
     *
     * 修法：把 CA bundle 随包带进来，通过 `SSL_CERT_FILE` 显式指给 CLI
     * （Go 认这个环境变量，优先级高于上面那些固定路径）。
     *
     * ⚠️ **不要**用 `java.lang.ProcessBuilder` 去跑什么命令生成 —— 直接用 assets。
     * ⚠️ 同时清掉 `SSL_CERT_DIR`：那是个**目录**，Go 会去遍历它，
     *    指向不存在的目录虽然不致命，但会把「找不到证书」的噪音混进错误里。
     */
    fun caBundle(ctx: Context): File? {
        val f = File(home(ctx), "cacert.pem")
        // 已经释放过且内容看着正常（有 PEM 头、不是空文件）就直接复用
        if (f.isFile && f.length() > 10_000) return f
        return runCatching {
            ctx.assets.open(CA_ASSET).use { ins ->
                FileOutputStream(f).use { ins.copyTo(it, 1 shl 16) }
            }
            if (f.length() > 10_000) f else null
        }.getOrElse {
            Log.e(TAG, "CA 证书释放失败: ${it.message}")
            null
        }
    }

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
        if (!available(ctx)) {
            return Result(
                false, JSONObject(),
                "发布工具没随安装包进来（缺少 $BIN_NAME）。装最新版本即可；" +
                    "如果是自己编的包，注意 CI 要先把 taptap-cli 下下来。",
                ""
            )
        }

        // ① **先直连**。
        //
        // 直连优先是对的：给 CLI 塞一个连不上的代理，会把本来能用的网络搞坏
        // （报错**恰好**也是 `transport / TapTap OAuth request failed`）。
        val direct = exec(ctx, args, cwd, timeoutMs, stdin, null)

        // ② 直连失败、而且看着像网络问题，才退到本地代理重试一次。
        //
        // ★ 这一步是**必需的**，不是兜底 —— 见下面「两个独立的坑」的说明。
        if (direct.ok || !looksLikeNetworkError(direct)) return direct

        val port = runCatching { LocalProxy.ensure() }.getOrDefault(0)
        if (port <= 0) {
            return direct.withDiag("本地代理没起来（端口=$port）")
        }
        val viaProxy = exec(ctx, args, cwd, timeoutMs, stdin, "http://127.0.0.1:$port")
        // 代理也没救回来就返回**直连那次**的结果 —— 那是更本质的失败原因
        return if (viaProxy.ok) viaProxy else direct.withDiag(
            "本地代理(127.0.0.1:$port)也失败：" +
                LocalProxy.lastError.ifBlank { "（代理无报错记录）" } +
                "；走代理那次=" + viaProxy.message
        )
    }

    /**
     * 给失败结果补一行诊断信息。
     *
     * 为什么需要：CLI 把 TLS 失败、DNS 失败、连不上**全都**包成同一句话
     * （`transport / TapTap OAuth request failed`），光看它根本分不清是哪一种。
     * 这一行把「我们自己这边知道的情况」附上去，用户截个图就能定位。
     */
    private fun Result.withDiag(extra: String): Result =
        copy(message = message + "\n[诊断] " + extra)

    /*
     * ==========================================================================
     * 为什么 taptap-cli 在 Android 上会失败 —— 两个**互相独立**的坑
     * ==========================================================================
     *
     * 这两个坑是分别踩出来的（各修一版才弄清楚），报错文案**完全一样**：
     *     {"type":"network","subtype":"transport","message":"TapTap OAuth request failed"}
     * 所以「报同一个错」不代表「同一个原因」，别看到就以为没修好。
     *
     * ── 坑 1：TLS 找不到 CA 根证书 ──────────────────────────────────────────
     * 它是静态链接的 Go 二进制，Go 的 crypto/x509 **只认 Linux 那几个固定路径**
     * （/etc/ssl/certs/ca-certificates.crt 等），而 Android 上一个都没有。
     * 修法：[caBundle] + SSL_CERT_FILE / TAPTAP_CLI_CA_PATH。
     *
     * ── 坑 2：DNS 解析不了（**本文件的 [LocalProxy] 就是为它准备的**）──────
     * Go 的**纯**解析器读 /etc/resolv.conf 拿 DNS 服务器地址，而 Android 沙箱里
     * **没有这个文件**（系统的 DNS 走 netd，不是文件）。
     * → 拿不到 nameserver → 解析失败 → 同一个报错。
     *
     * 这正是 Maker 那条链路早就踩过、并用 dnsfix.js 绕开的坑
     * （见 assets/rt.tar 里的 dnsfix.js 开头注释，以及 MakerCli.newProcess 的
     *  `node --use-bundled-ca -r dnsfix.js`）—— 只是 taptap-cli 不是 node 脚本，
     * 塞不进 `-r`，所以只能改用「本地 CONNECT 代理」这个等价办法：
     * **由 App 去解析域名**（App 走 Android 的 resolver，解析是好的），
     * 再把 TCP 隧道转出去。CLI 只需要连 127.0.0.1（字面 IP，不用解析）即可。
     *
     * 已验证：CLI 走这条 CONNECT 代理链路可以正常完成 OAuth 请求
     * （`HTTPS_PROXY=http://127.0.0.1:<port>` → `ok:true`）。
     *
     * ⚠️ 所以 ② 那一步**不能删**。删了的话，只要用户的网络不能直连
     * （而 Android 上 DNS 本来就直连不了），登录就必然失败。
     */

    /** 这次失败像不像网络问题（决定要不要退到代理重试） */
    private fun looksLikeNetworkError(r: Result): Boolean {
        val t = (r.raw + r.message).lowercase()
        return t.contains("transport") || t.contains("network") ||
            t.contains("timeout") || t.contains("connection") ||
            t.contains("no such host") || t.contains("lookup") ||
            t.contains("dial tcp") || t.contains("refused") ||
            t.contains("unreachable")
    }

    /**
     * 真正跑一次 CLI。
     *
     * @param proxy 非空时给子进程设 HTTP(S)_PROXY；null = 直连。
     */
    private fun exec(
        ctx: Context,
        args: List<String>,
        cwd: File?,
        timeoutMs: Long,
        stdin: String?,
        proxy: String?
    ): Result {
        val exe = bin(ctx)
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

            // ★★★ 修复坑 1：把 CA 根证书显式指给 CLI ★★★
            //
            // Go 的证书加载只看 /etc/ssl/certs/ca-certificates.crt 那几个 Linux 固定路径，
            // 而 Android 上一个都没有 → TLS 握手找不到可信根 → 直接失败。
            // 失败被包装成 `transport / TapTap OAuth request failed`，看着像网络问题，
            // 其实是证书问题。详见 [caBundle]。
            //
            // 两条都设：`TAPTAP_CLI_CA_PATH` 是 CLI **自己的**配置项（一等公民），
            // `SSL_CERT_FILE` 是 Go 的通用约定 —— 实测两者都能生效，都设上更稳。
            caBundle(ctx)?.let { ca ->
                env["TAPTAP_CLI_CA_PATH"] = ca.absolutePath
                env["SSL_CERT_FILE"] = ca.absolutePath
                // 显式清掉：Go 会去遍历 SSL_CERT_DIR 这个**目录**，
                // 留着上一层的值只会往错误信息里掺噪音。
                env.remove("SSL_CERT_DIR")
            }
            // ★★★ 修复坑 2：走本地代理，绕开 CLI 自己解析不了域名的问题 ★★★
            //
            // 代理只由 [run] 显式传进来（直连失败后才会传），**不要**在这里读
            // android.net.Proxy 自作主张 —— 那个 API 在不少新 Android 上返回垃圾值
            // （不是 null），一塞进去就等于「给 CLI 设了个连不上的代理」。
            proxy?.let { p ->
                // CLI 自己的配置项（一等公民，实测有效）。
                // 注意 `TAPTAP_CLI_PROXY_ENABLE=1` 是开关，少了它 ADDRESS 不生效。
                env["TAPTAP_CLI_PROXY_ENABLE"] = "1"
                env["TAPTAP_CLI_PROXY_ADDRESS"] = p
                env["TAPTAP_CLI_NO_PROXY"] = "127.0.0.1,localhost"
                // 通用约定，两条都设上更稳。
                env["HTTP_PROXY"] = p
                env["HTTPS_PROXY"] = p
                env["http_proxy"] = p
                env["https_proxy"] = p
                // 本地服务不要绕代理
                env["NO_PROXY"] = "127.0.0.1,localhost"
                env["no_proxy"] = "127.0.0.1,localhost"
            }

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

        for (o in envelopeCandidates(raw)) {
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

    /*
     * ★★★ 曾经把「成功」判成「失败」的地方 ★★★
     *
     * 老实现是「按行找以 { 开头的行，逐行 JSONObject(...)」——
     * 但 CLI 的 `--json` 输出是**缩进的多行 pretty-print**：
     *
     *     {
     *       "ok": true,
     *       "data": { ... }
     *     }
     *
     * 于是候选行只有孤零零一个 `{`，解析成空对象后被跳过，
     * **永远匹配不到信封** → 一律返回 ok=false。
     *
     * 后果非常隐蔽：CLI 明明**成功**了（raw 里白纸黑字 `"ok": true`、
     * 连 verification_url 都拿到了），代码却当失败处理，还顺手去重试本地代理。
     * 用户看到的永远是「登录失败」。
     *
     * 这个 bug 害我对着证书和 DNS 连修了三版 —— 因为它们**本来就修对了**，
     * 只是结果被这里吃掉了。教训：解析失败时要把 raw 露出来，
     * 不能静默降级成「失败」。
     */
    private fun envelopeCandidates(raw: String): List<JSONObject> {
        // ① 整段就是一个 JSON —— 这才是常态
        runCatching { JSONObject(raw.trim()) }.getOrNull()
            ?.takeIf { it.has("ok") || it.has("data") }
            ?.let { return listOf(it) }

        // ② 扫出所有**配对完整**的 {...} 块（不是「以 { 开头的行」！）
        val found = mutableListOf<JSONObject>()
        var i = 0
        while (i < raw.length) {
            if (raw[i] != '{') { i++; continue }
            val end = matchBrace(raw, i) ?: break
            runCatching { JSONObject(raw.substring(i, end + 1)) }.getOrNull()
                ?.takeIf { it.has("ok") || it.has("data") }
                ?.let { found += it }
            i = end + 1
        }

        // ③ 兜底：单行 JSON（万一某个命令只打一行）
        if (found.isEmpty()) {
            raw.lines().forEach { line ->
                val t = line.trim()
                if (t.startsWith("{") && t.endsWith("}")) {
                    runCatching { JSONObject(t) }.getOrNull()
                        ?.takeIf { it.has("ok") || it.has("data") }
                        ?.let { found += it }
                }
            }
        }

        // 后出现的优先：进度行在前，真正的信封在后
        return found.asReversed()
    }

    /**
     * 从 s[from]（必须是 `{`）开始找配对的那个 `}` 的下标，找不到返回 null。
     * 会正确跳过字符串字面量与转义 —— 否则 data 里的 `}` 会把层级带偏。
     */
    private fun matchBrace(s: String, from: Int): Int? {
        var depth = 0
        var inStr = false
        var esc = false
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return null
    }

    // （原来这里有个 systemProxyUrl()，读 android.net.Proxy.getDefaultHost() 给子进程设代理。
    //   已删除：那个 API 在不少新 Android 上返回垃圾值而不是 null，
    //   于是「没开 VPN 的用户」也被塞了一个连不上的代理，登录必失败。
    //   现在改成直连优先，失败了才用自己起的本地代理 —— 见 run()。）

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
     * 读一个资料模块的当前值（含每个字段的 `current_value` / `image_spec` / `required`）。
     *
     * 写素材字段之前**必须**先读 —— 官方要求：
     *   · 用字段当次返回的 `image_spec` 判断规格，不要凭记忆复述尺寸；
     *   · `replace_one` 要传从最新字段值读出来的 `old_value`；
     *   · `expected` 是「本批变更后的最终值」，得基于当前值算。
     */
    fun getModule(ctx: Context, devId: String, appId: String, module: String): Result =
        run(
            ctx,
            listOf("app", "get-app-module", "--dev-id", devId, "--app-id", appId, "--module", module),
            timeoutMs = 90_000
        )

    /**
     * 清点一个目录里有什么可上传的物料（**只读，不上传**）。
     *
     * 官方把它作为上传编排的第一步：先看清目录里有哪些图/视频/包体、
     * 哪些识别不了、哪些有歧义，再决定传什么。
     * 返回的 `materials[]` 每项带 `path` / `name` / `kind`。
     */
    fun inspectMaterials(ctx: Context, dir: File): Result =
        run(ctx, listOf("materials", "+inspect", dir.name), cwd = dir, timeoutMs = 90_000)

    /**
     * 上传一张图片到素材库，**返回它的 HTTPS URL**（写字段要用这个 URL）。
     *
     * ⚠️ 上传成功 ≠ 资料字段已写入。拿到 URL 之后还要调 [saveChanges]
     * 把它写进具体字段（icon / screenshots / banner_4 …），否则图只是躺在素材库里。
     */
    fun uploadImageUrl(
        ctx: Context,
        file: File,
        devId: String,
        appId: String,
        dryRun: Boolean = false
    ): Pair<Result, String> {
        val r = uploadImage(ctx, file, devId, appId, dryRun)
        // 上传返回里带图片的 https 地址；字段写入要用它
        val url = r.data.optString("url").ifBlank {
            r.obj("result")?.optString("url").orEmpty()
        }.ifBlank {
            Regex("https://[^\\s\"']+?\\.(?:png|jpg|jpeg|webp|gif)")
                .find(r.raw)?.value.orEmpty()
        }
        return r to url
    }

    /**
     * 上传一个视频，返回数字 `videoId`（**不是 URL**）。
     *
     * 官方明确：拿到 videoId 即视为上传完成，直接写字段，
     * **不要**去轮询转码/审核状态（那是异步的，不影响「字段已写入」）。
     */
    fun uploadVideoId(
        ctx: Context,
        file: File,
        devId: String,
        appId: String,
        scene: String,
        dryRun: Boolean = false
    ): Pair<Result, String> {
        val dir = file.parentFile ?: ctx.filesDir
        val args = mutableListOf(
            "asset-library", "+upload-video", file.name,
            "--dev-id", devId, "--app-id", appId,
            "--scene", scene
        )
        if (dryRun) args += "--dry-run" else args += "--yes"
        val r = run(ctx, args, cwd = dir, timeoutMs = 300_000)
        val vid = r.data.optString("video_id").ifBlank {
            r.data.optString("videoId").ifBlank {
                r.obj("result")?.optString("video_id").orEmpty()
            }
        }.ifBlank {
            Regex("\"video_?[iI]d\"\\s*:\\s*\"?(\\d+)").find(r.raw)?.groupValues?.get(1).orEmpty()
        }
        return r to vid
    }

    /**
     * 写一个字段 —— **自动带上官方要求的 `expected`**。
     *
     * 这是写素材/资料字段的**唯一推荐入口**。别绕开它直接拼 changes：
     * 官方强制要求每条 change 带 `expected`（乐观锁），漏了会被直接拒：
     *   `save-changes requires $.changes[0] to include expected unless force=true`
     *
     * `expected` 的语义（官方原文）：**原样回填该字段读取时的 `current_value`**；
     * `current_value` 是 null（首次写入）就传 `[]`。
     * 所以这里先读一次字段，把当前值原样放进去。
     *
     * @param module 字段所在的模块（`basic-info` / `assets-upload` / `profile-promotion` …）
     */
    fun saveField(
        ctx: Context,
        devId: String,
        appId: String,
        module: String,
        fieldId: String,
        op: String,
        value: Any,
        oldValue: String? = null,
        idempotencyKey: String? = null
    ): Result {
        // ① 先读字段当前值 —— expected 要「原样回填」它
        val mod = getModule(ctx, devId, appId, module)
        val field = mod.obj("result")?.optJSONObject("fields")?.optJSONObject(fieldId)
            ?: mod.data.optJSONObject("fields")?.optJSONObject(fieldId)
        val current = field?.opt("current_value")

        val change = JSONObject()
            .put("field_id", fieldId)
            .put("op", op)
            .put("value", value)
        if (oldValue != null) change.put("old_value", oldValue)

        // expected：null（首次写入）→ []；其余原样回传
        when (current) {
            null, JSONObject.NULL -> change.put("expected", org.json.JSONArray())
            else -> change.put("expected", current)
        }

        val changes = org.json.JSONArray().put(change).toString()
        return saveChanges(
            ctx, devId, appId, changes,
            idempotencyKey ?: ("save-" + appId + "-" + fieldId + "-" + System.currentTimeMillis())
        )
    }

    /**
     * 保存资料修改（改简介 / 写素材字段 等）。
     *
     * 官方要求的 `changes[]` 结构，每项：
     *   · `field_id` 字段名（`icon` / `screenshots` / `banner_4` / `square_promo_image` / `trailer` …）
     *   · `op`       操作：`append` 追加 / `remove` 删除 / `replace` 整组重传 / `replace_one` 替换一张
     *   · `value`    新值（图片是 HTTPS URL；视频是数字 videoId）
     *   · `old_value` 仅 `replace_one` 需要，从最新字段值读
     *   · **`expected` 必填**（乐观锁）：**原样回填**该字段读取时的 `current_value`；
     *     `current_value` 是 null（首次写入）时传 `[]`。
     *
     * ⚠️ `expected` 漏了会被直接拒：
     *   `save-changes requires $.changes[0] to include expected unless force=true`
     * —— 我第一版就是漏了它，导致**所有写字段操作都会失败**（实测踩到）。
     * 所以 [saveField] 会自动带上，别绕开它手写。
     *
     * @param changesJson 一个 JSON **数组**串
     */
    fun saveChanges(
        ctx: Context,
        devId: String,
        appId: String,
        changesJson: String,
        idempotencyKey: String,
        dryRun: Boolean = false
    ): Result {
        val data = JSONObject().put("changes", org.json.JSONArray(changesJson))
        val args = mutableListOf(
            "app", "save-changes",
            "--dev-id", devId, "--app-id", appId,
            "--data", data.toString(),
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
