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
 * **没有任何 exec 通道**（刻意的安全设计，不能放开）。
 *
 * 所以由 App 本体代跑：设置页点一下按钮 → 这里起一个一次性 node 进程。
 * 全程走我们自己的 musl 运行时（含 dnsfix / 内置 CA），和内置服务同一套环境。
 */
object MakerCli {

    data class Result(val ok: Boolean, val output: String)

    /**
     * 阻塞执行（必须放子线程）。
     *
     * @param cmd    Maker 子命令，例如 listOf("pat","set","--pat-stdin")
     * @param stdin  需要喂给进程的标准输入（如 PAT 明文），null 表示直接关闭 stdin
     */
    fun run(ctx: Context, cmd: List<String>, stdin: String? = null, timeoutMs: Long = 90_000): Result {
        val dir = McpRt.rtDir(ctx)
        val ld = File(ctx.applicationInfo.nativeLibraryDir, "libmuslrt.so")
        val node = File(dir, "node")
        val maker = File(dir, "maker/dist/maker.js")
        if (!ld.isFile) return Result(false, "缺少 libmuslrt.so（仅支持 arm64 设备）")
        if (!node.isFile || !maker.isFile) return Result(false, "Maker 未随运行时解包（请重新安装本版本）")

        val home = File(dir, "home").apply { mkdirs() }
        val args = mutableListOf(
            ld.absolutePath, "--library-path", dir.absolutePath,
            node.absolutePath,
            "--use-bundled-ca",
            "-r", File(dir, "dnsfix.js").absolutePath,
            maker.absolutePath
        )
        args += cmd

        return try {
            val pb = ProcessBuilder(args)
            pb.directory(dir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["HOME"] = home.absolutePath
            env["TMPDIR"] = home.absolutePath
            env["PATH"] = dir.absolutePath
            env["TAPTAP_MAKER_HOME"] = File(home, "maker").apply { mkdirs() }.absolutePath
            val p = pb.start()

            // stdin：PAT 走管道喂进去，避免出现在命令行（防 ps / 日志泄露）
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

            val sb = StringBuilder()
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { line -> sb.appendLine(line) } }
            }.apply { isDaemon = true }
            reader.start()

            val deadline = System.currentTimeMillis() + timeoutMs
            var exited = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    p.exitValue()
                    exited = true
                    break
                } catch (e: IllegalThreadStateException) {
                    Thread.sleep(200)
                }
            }
            if (!exited) {
                runCatching { p.destroy() }
                runCatching { p.waitFor(2, TimeUnit.SECONDS) }
                return Result(false, sb.toString().ifBlank { "无输出" } + "\n[执行超时 ${timeoutMs / 1000}s，已终止]")
            }
            reader.join(1500)
            Result(p.exitValue() == 0, sb.toString().ifBlank { "（无输出）" })
        } catch (t: Throwable) {
            Result(false, "执行失败：${t.javaClass.simpleName}: ${t.message}")
        }
    }
}