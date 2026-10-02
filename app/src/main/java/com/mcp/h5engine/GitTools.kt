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
}
