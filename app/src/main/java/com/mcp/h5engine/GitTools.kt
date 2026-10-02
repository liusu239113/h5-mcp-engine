package com.mcp.h5engine

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 对**任意项目目录**做 git 操作（不只是 Maker 工程）。
 *
 * ## 复用 App 自带的 git，不另外装
 *
 * 手机上没有 git。App 里其实**早就带了一份**（`assets/gitrt.tar` 解出来的
 * musl/aarch64 版 git + 一层 shell wrapper），只是以前只给 Maker 工程用
 * （见 McpRt / MakerCli）。这里把同一份暴露出来，指向用户选的目录即可。
 *
 * ## 为什么环境变量这么多
 *
 * 那个 git 是 musl 静态链接的，靠 `HEXORA_LD`（libmuslrt.so）当加载器起，
 * 还得告诉它 git-core / 模板 / CA 在哪 —— 少一个就报 ENOENT 或 SSL 失败。
 * 这些值与 McpRt 里给 Maker 用的**完全一致**，照抄即可，别自己编。
 *
 * ## 凭据
 *
 * 走 HTTPS + token 的方式（GitHub 的 PAT 放 URL 里，或用户自己配 credential）。
 * 不做 OAuth —— 用户要的是「能对项目做 git 操作」，不是完整的 GitHub 客户端。
 */
object GitTools {

    /** git 可执行文件（wrapper）在不在 */
    fun available(ctx: Context): Boolean = runCatching {
        gitBin(ctx).isFile
    }.getOrDefault(false)

    private fun gitBin(ctx: Context): File = File(McpRt.rtDir(ctx), "gitrt/git")

    data class Res(val code: Int, val out: String) {
        val ok: Boolean get() = code == 0
        /** 给用户/模型看的一行结论 */
        fun brief(maxLines: Int = 40): String {
            val lines = out.trim().lines()
            return if (lines.size <= maxLines) out.trim()
            else lines.take(maxLines).joinToString("\n") + "\n…（共 ${lines.size} 行，已截断）"
        }
    }

    /**
     * 在 [dir] 里跑一条 git 命令。
     *
     * @param dir 项目目录 —— **必须是真实存在的目录**，否则 git 会退到别处
     */
    fun run(ctx: Context, dir: File, args: List<String>, timeoutMs: Long = 120_000): Res {
        val rt = McpRt.rtDir(ctx)
        val ld = File(ctx.applicationInfo.nativeLibraryDir, "libmuslrt.so")
        if (!gitBin(ctx).isFile || !ld.isFile) {
            return Res(-1, "内置 git 还没解包（缺少 gitrt/git 或 libmuslrt.so）。打开一次 App 让它释放运行时，或重装本版本。")
        }
        return runCatching {
            val cmd = mutableListOf(gitBin(ctx).absolutePath)
            cmd += args
            val pb = ProcessBuilder(cmd)
            pb.directory(dir.takeIf { it.isDirectory } ?: ctx.filesDir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            // 与 McpRt 里给 Maker 的那套**逐条一致**（见该文件的注释）
            env["HEXORA_RT"] = rt.absolutePath
            env["HEXORA_LD"] = ld.absolutePath
            env["GIT_EXEC_PATH"] = File(rt, "gitrt/git-core").absolutePath
            env["TAPTAP_MAKER_GIT_BIN"] = gitBin(ctx).absolutePath
            env["GIT_SSL_CAINFO"] = File(rt, "gitrt/cacert.pem").absolutePath
            env["GIT_TEMPLATE_DIR"] = File(rt, "gitrt/templates").absolutePath
            // 别让 git 卡在交互式要密码 / 弹凭据框上（手机上没有终端可以输入）
            env["GIT_TERMINAL_PROMPT"] = "0"
            env["GIT_CONFIG_NOSYSTEM"] = "1"
            env["HOME"] = File(rt, "home").apply { mkdirs() }.absolutePath

            val p = pb.start()
            runCatching { p.outputStream.close() }
            val sb = StringBuilder()
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { sb.appendLine(it) } }
            }.apply { isDaemon = true }
            reader.start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                runCatching { p.destroy() }
                return@runCatching Res(-1, sb.toString() + "\n[超时 ${timeoutMs / 1000}s，已终止]")
            }
            reader.join(800)
            Res(p.exitValue(), sb.toString())
        }.getOrElse { Res(-1, "跑不起来：${it.javaClass.simpleName}: ${it.message}") }
    }

    // ==================== 高层操作 ====================

    fun isRepo(ctx: Context, dir: File): Boolean =
        File(dir, ".git").isDirectory

    /** 初始化仓库（已存在就什么都不做） */
    fun init(ctx: Context, dir: File): Res {
        if (isRepo(ctx, dir)) return Res(0, "已经是 git 仓库了，跳过 init")
        val r = run(ctx, dir, listOf("init"))
        // 顺手把默认分支设成 main（新版 git 可能已经是，老的是 master）
        if (r.ok) runCatching { run(ctx, dir, listOf("branch", "-M", "main")) }
        return r
    }

    fun status(ctx: Context, dir: File): Res = run(ctx, dir, listOf("status", "--short", "--branch"))

    /** 看改动统计（比 status 更适合给模型看「改了什么」） */
    fun diffStat(ctx: Context, dir: File): Res = run(ctx, dir, listOf("diff", "--stat"))

    fun log(ctx: Context, dir: File, n: Int = 20): Res =
        run(ctx, dir, listOf("log", "--oneline", "--graph", "-n", n.toString()))

    fun addAll(ctx: Context, dir: File): Res = run(ctx, dir, listOf("add", "-A"))

    fun commit(ctx: Context, dir: File, message: String): Res =
        run(ctx, dir, listOf("commit", "-m", message))

    /** 当前远端（没有就返回空串） */
    fun remoteUrl(ctx: Context, dir: File): String {
        val r = run(ctx, dir, listOf("remote", "get-url", "origin"))
        return if (r.ok) r.out.trim() else ""
    }

    fun setRemote(ctx: Context, dir: File, url: String): Res {
        val has = remoteUrl(ctx, dir).isNotBlank()
        return if (has) run(ctx, dir, listOf("remote", "set-url", "origin", url))
        else run(ctx, dir, listOf("remote", "add", "origin", url))
    }

    /**
     * 推送到远端。
     *
     * ⚠️ 会把**当前分支**推上去（`push -u origin HEAD`），
     * 第一次推会自动建立 upstream 跟踪，省得用户再配。
     */
    fun push(ctx: Context, dir: File): Res =
        run(ctx, dir, listOf("push", "-u", "origin", "HEAD"), timeoutMs = 300_000)

    fun pull(ctx: Context, dir: File): Res =
        run(ctx, dir, listOf("pull", "--rebase=false"), timeoutMs = 300_000)

    /** 克隆到 [destParent] 下（目录名取仓库名） */
    fun clone(ctx: Context, destParent: File, url: String): Res =
        run(ctx, destParent, listOf("clone", url), timeoutMs = 600_000)

    /**
     * 把 token 塞进 https URL —— GitHub 私有仓库要这个。
     *
     * `https://github.com/u/r.git` + token → `https://<token>@github.com/u/r.git`
     * （GitHub 现在接受「token 当用户名、密码留空」的写法。）
     */
    fun withToken(url: String, token: String): String {
        if (token.isBlank() || !url.startsWith("https://")) return url
        if (url.contains("@")) return url          // 已经带过凭据了
        return url.replaceFirst("https://", "https://$token@")
    }

    /** 把 URL 里的 token 抹掉，免得它出现在界面 / 日志 / 模型上下文里 */
    fun maskToken(s: String): String =
        s.replace(Regex("https://[^@/\\s]+@"), "https://***@")

    // ==================== 测试连接 ====================

    /** 测试结果：ok = 能不能用；title = 一行结论；detail = 细节（原因 / 权限） */
    data class TestResult(val ok: Boolean, val title: String, val detail: String)

    /**
     * 测试「这个远端 + 这个 token」到底能不能用。
     *
     * ## 为什么单独做这个
     *
     * 以前只能「设置远端 → 推送 → 看报错」，而 git 的报错在手机上看不懂
     * （`fatal: Authentication failed` 之后没有下文，用户不知道是 token 过期、
     * 还是仓库名写错、还是网络不通）。这里把三类失败分开说清楚：
     *
     *   1. **仓库能不能访问** —— 走 GitHub API，顺便把「有没有推送权限」也问出来
     *      （GitHub 会在 permissions 里明确给 push=true/false）；
     *   2. **git 本身通不通** —— 跑一次 `ls-remote`，这才是推送真正走的链路；
     *   3. **失败归类** —— 401 token 无效 / 403 无权限 / 404 仓库名错或没权限 / 网络不通。
     *
     * 非 GitHub 的远端（Gitee / 自建）跳过第 1 步，只做 ls-remote —— 照样能验证凭据。
     */
    fun testConnection(ctx: Context, dir: File, url: String, token: String): TestResult {
        val u = url.trim()
        if (u.isBlank()) return TestResult(false, "没有填仓库地址", "形如 https://github.com/用户名/仓库.git")
        if (!u.startsWith("https://") && !u.startsWith("git@")) {
            return TestResult(false, "地址格式不对", "只支持 https:// 或 git@ 开头的地址，当前是：${u.take(40)}")
        }

        // ---- 1) GitHub：问 API 要仓库信息和权限 ----
        val gh = parseGitHub(u)
        if (gh != null) {
            val (owner, repo) = gh
            val api = "https://api.github.com/repos/$owner/$repo"
            val r = httpGet(api, token.ifBlank { tokenInUrl(u) })
            when (r.code) {
                200 -> {
                    val push = r.body.contains("\"push\":true") || r.body.contains("\"push\": true")
                    val admin = r.body.contains("\"admin\":true") || r.body.contains("\"admin\": true")
                    return if (push) {
                        TestResult(
                            true,
                            "连接正常，有推送权限",
                            "$owner/$repo 可读写（admin=$admin）。可以直接「推送到远端」。"
                        )
                    } else {
                        TestResult(
                            false,
                            "能连上，但没有推送权限",
                            "$owner/$repo 能读、不能写。token 需要勾选 repo 权限，" +
                                "或你的账号在这个仓库里不是协作者。"
                        )
                    }
                }
                401 -> return TestResult(
                    false, "token 无效或已过期（401）",
                    "去 GitHub → Settings → Developer settings → Personal access tokens 重新生成一个，" +
                        "勾选 repo（私有仓库还要 workflow）。"
                )
                403 -> return TestResult(
                    false, "被 GitHub 拒绝（403）",
                    "多半是 token 权限不足，或触发了速率限制。确认 token 勾了 repo 权限。"
                )
                404 -> return TestResult(
                    false, "仓库不存在或无权访问（404）",
                    "核对两件事：① 仓库名是不是写对了（$owner/$repo）；" +
                        "② 私有仓库必须带有效 token —— 不带 token 时 GitHub 一律回 404，不区分「不存在」和「没权限」。"
                )
                -1 -> return TestResult(false, "连不上 GitHub", r.body)
                else -> return TestResult(false, "GitHub 返回 ${r.code}", r.body.take(300))
            }
        }

        // ---- 2) 其它远端 / GitHub 兜底：直接跑 git ls-remote ----
        val withTok = withToken(u, token)
        val r = run(ctx, dir, listOf("ls-remote", "--heads", withTok), timeoutMs = 60_000)
        return if (r.ok) {
            val n = r.out.trim().lines().count { it.isNotBlank() }
            TestResult(true, "连接正常", "远端可达，读到 $n 个分支。可以直接推送。")
        } else {
            val out = maskToken(r.out)
            val why = when {
                out.contains("Authentication failed") || out.contains("could not read Username") ->
                    "认证失败：token 不对、或没勾 repo 权限。"
                out.contains("not found") || out.contains("Repository not found") ->
                    "仓库不存在，或 token 没有访问它的权限。"
                out.contains("Could not resolve host") || out.contains("unable to access") ->
                    "网络不通：检查手机网络 / 代理。"
                out.contains("超时") -> "超时：网络太慢或地址不可达。"
                else -> "git 报错如下。"
            }
            TestResult(false, "连不上", "$why\n\n$out".take(600))
        }
    }

    /** 从 https://github.com/owner/repo(.git) 里抠出 owner / repo；不是 GitHub 返回 null */
    private fun parseGitHub(url: String): Pair<String, String>? {
        val m = Regex("github\\.com[/:]([^/]+)/([^/\\s]+?)(?:\\.git)?$").find(url.trim())
            ?: return null
        val owner = m.groupValues[1]
        val repo = m.groupValues[2].removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null
        return owner to repo
    }

    /** URL 里如果已经带了 token（https://<token>@github.com/…），取出来复用 */
    private fun tokenInUrl(url: String): String {
        val m = Regex("https://([^@/\\s]+)@").find(url) ?: return ""
        val v = m.groupValues[1]
        return if (v.contains(':')) v.substringAfter(':') else v
    }

    /**
     * 本 App 自己的发布仓库 —— 用来拿「最新一版 APK 的下载直链」。
     *
     * 为什么要内置：CI 每次构建都会自动发 Release，但用户要拿到那个 APK，
     * 以前得自己开浏览器 → 进 GitHub → 找 Releases → 挑版本 → 下载。
     * 手机上这一步很烦，而且 Actions 的 artifact 还必须登录才能下。
     * 这里直接问 GitHub API 要 latest release 的资产直链，一次点击就能下。
     */
    private const val SELF_REPO = "liusu239113/h5-mcp-engine"

    /** 最新版信息：tag = 版本号，apkUrl = 公开直链（不用登录） */
    data class LatestRelease(val tag: String, val apkUrl: String, val sizeBytes: Long)

    /**
     * 问 GitHub 要本 App 的最新 Release。
     *
     * 失败一律返回 null（网络不通 / 还没发过 release），调用方给一句人话即可 ——
     * 「查不到新版本」不该变成一个错误弹窗。
     */
    fun latestRelease(): LatestRelease? = runCatching {
        val r = httpGet("https://api.github.com/repos/$SELF_REPO/releases/latest", "")
        if (r.code != 200) return@runCatching null
        val o = org.json.JSONObject(r.body)
        val tag = o.optString("tag_name", "")
        val assets = o.optJSONArray("assets") ?: return@runCatching null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return@runCatching LatestRelease(
                    tag,
                    a.optString("browser_download_url", ""),
                    a.optLong("size", 0)
                )
            }
        }
        null
    }.getOrNull()

    private data class Http(val code: Int, val body: String)

    /** 极简 GET（不引依赖）：只用来问 GitHub API 的仓库权限 */
    private fun httpGet(url: String, token: String): Http = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 12_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Hexora")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        Http(code, body)
    }.getOrElse { e ->
        Http(-1, "网络请求失败：${e.javaClass.simpleName}: ${e.message}")
    }
}
