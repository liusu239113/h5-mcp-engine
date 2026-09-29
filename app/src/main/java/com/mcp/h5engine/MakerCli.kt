package com.mcp.h5engine

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Maker CLI 的极薄封装。
 *
 * 为什么需要它：Maker 的 MCP 工具能做素材生成，但**授权**（pat / login）、
 * **绑定工程**（init）、**自检**（doctor）这些只有 CLI 有。
 * 而 App 内的 AI 跑在 WebView 沙箱里，`process` / `require` 都是 undefined，
 * **没有任何 exec 通道**（刻意的安全设计，不能放开）。所以由 App 本体代跑。
 *
 * 两个入口，别混用：
 *   · [run]       一次性命令（pat set / init / doctor / apps），跑完返回输出；
 *   · [runStream] **流式命令**（login）—— 它把授权链接**立刻**打在 stdout，
 *                 然后要等用户在浏览器点完才退出（最长 10 分钟）。
 *                 必须逐行回调，绝不能等它退出再回读输出 —— 旧版就是死在这，链接永远显示不出来。
 */
object MakerCli {

    data class Result(val ok: Boolean, val output: String)

    /** 当前正在跑的 CLI 进程（供取消用） */
    @Volatile
    var current: Process? = null
        private set

    /**
     * Maker 凭据目录：TAPTAP_MAKER_HOME。
     * **必须和 McpRt.startMaker 用同一个**，否则桥服务读不到授权，生成素材会报未授权。
     */
    fun home(ctx: Context): File = File(McpRt.rtDir(ctx), "home/maker").apply { mkdirs() }

    fun patFile(ctx: Context): File = File(home(ctx), "pat.json")

    /** pat.json 里有 token = 已授权。不起进程，判断几乎零成本 */
    fun hasPat(ctx: Context): Boolean = runCatching {
        val f = patFile(ctx)
        f.isFile && f.length() > 8 && f.readText().contains("\"token\"")
    }.getOrDefault(false)

    fun cancelCurrent() {
        runCatching { current?.destroy() }
        current = null
    }

    /**
     * musl 运行时前缀，与 McpRt.startMaker 同源（dnsfix 修 DNS + 内置 CA）。
     * @param cwd 工作目录。**必须给当前工程目录** —— 否则 doctor 会报 doctor_cwd_alignment=not_bound。
     */
    private fun newProcess(ctx: Context, cmd: List<String>, cwd: File?, stdin: String?): Process? {
        val dir = McpRt.rtDir(ctx)
        val ld = File(ctx.applicationInfo.nativeLibraryDir, "libmuslrt.so")
        val node = File(dir, "node")
        val maker = File(dir, "maker/dist/maker.js")
        if (!ld.isFile || !node.isFile || !maker.isFile) return null

        val args = mutableListOf(
            ld.absolutePath, "--library-path", dir.absolutePath,
            node.absolutePath,
            "--use-bundled-ca",
            "-r", File(dir, "dnsfix.js").absolutePath,
            maker.absolutePath
        )
        args += cmd

        val pb = ProcessBuilder(args)
        pb.directory(cwd?.takeIf { it.isDirectory } ?: dir)
        pb.redirectErrorStream(true)

        val h = File(dir, "home").apply { mkdirs() }
        val env = pb.environment()
        env["HOME"] = h.absolutePath
        env["TMPDIR"] = h.absolutePath
        env["PATH"] = dir.absolutePath + ":" + File(dir, "gitrt").absolutePath
        env["TAPTAP_MAKER_HOME"] = home(ctx).absolutePath
        // ---- 自带 git（见 gitrt/git 的 wrapper）：Maker 的 init / clone / push 都要真 git ----
        env["HEXORA_RT"] = dir.absolutePath
        env["HEXORA_LD"] = ld.absolutePath
        env["GIT_EXEC_PATH"] = File(dir, "gitrt/git-core").absolutePath
        env["TAPTAP_MAKER_GIT_BIN"] = File(dir, "gitrt/git").absolutePath
        env["GIT_SSL_CAINFO"] = File(dir, "gitrt/cacert.pem").absolutePath
        env["GIT_TEMPLATE_DIR"] = File(dir, "gitrt/templates").absolutePath
        env["GIT_TERMINAL_PROMPT"] = "0"
        env["GIT_CONFIG_NOSYSTEM"] = "1"

        val p = pb.start()
        runCatching {
            if (stdin != null) {
                p.outputStream.use {
                    it.write(stdin.toByteArray(Charsets.UTF_8))
                    it.flush()
                }
            } else {
                p.outputStream.close()
            }
        }
        current = p
        return p
    }

    /**
     * 阻塞执行一次性命令（必须放子线程）。
     *
     * @param stdin   需要喂给进程的标准输入（如 PAT 明文），null 表示直接关闭 stdin。
     *                PAT 走管道是为了不出现在命令行（防 ps / 日志泄露）。
     * @param cwd     工作目录，默认当前工程目录
     */
    fun run(
        ctx: Context,
        cmd: List<String>,
        stdin: String? = null,
        timeoutMs: Long = 90_000,
        cwd: File? = null
    ): Result {
        val p = newProcess(ctx, cmd, cwd, stdin)
            ?: return Result(false, "Maker 未随运行时解包（缺少 node / maker/dist/maker.js / libmuslrt.so，请重装本版本）")

        val sb = StringBuilder()
        val reader = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { sb.appendLine(it) } }
        }.apply { isDaemon = true }
        reader.start()

        if (!waitExit(p, timeoutMs)) {
            runCatching { p.destroy() }
            runCatching { p.waitFor(2, TimeUnit.SECONDS) }
            current = null
            return Result(false, sb.toString().ifBlank { "（无输出）" } + "\n[执行超时 ${timeoutMs / 1000}s，已终止]")
        }
        reader.join(1500)
        current = null
        return Result(p.exitValue() == 0, sb.toString().ifBlank { "（无输出）" })
    }

    /**
     * 流式执行（login 专用）：每读到一行就回调，进程该等多久等多久。
     *
     * @param timeoutMs 默认 10 分 30 秒 —— CLI 自己的轮询上限就是 10 分钟，我们比它多留 30 秒。
     */
    fun runStream(
        ctx: Context,
        cmd: List<String>,
        cwd: File? = null,
        timeoutMs: Long = 10 * 60_000 + 30_000,
        onLine: (String) -> Unit
    ): Result {
        val p = newProcess(ctx, cmd, cwd, null)
            ?: return Result(false, "Maker 未随运行时解包（缺少 node / maker/dist/maker.js / libmuslrt.so，请重装本版本）")

        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    sb.appendLine(line)
                    runCatching { onLine(line) }
                }
            }
        }.apply { isDaemon = true }
        reader.start()

        if (!waitExit(p, timeoutMs)) {
            runCatching { p.destroy() }
            current = null
            return Result(false, sb.toString() + "\n[等待超时 ${timeoutMs / 1000}s，已终止]")
        }
        reader.join(1500)
        current = null
        return Result(p.exitValue() == 0, sb.toString().ifBlank { "（无输出）" })
    }

    private fun waitExit(p: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue()
                return true
            } catch (e: IllegalThreadStateException) {
                Thread.sleep(150)
            }
        }
        return false
    }
}