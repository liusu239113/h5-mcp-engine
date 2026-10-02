package com.mcp.h5engine

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shell 执行后端抽象（C4）。
 *
 * 项目里现在所有「起进程」的地方（McpRt 起 node、MakerCli 调 git）都是各自直接 ProcessBuilder。
 * 这里抽出一层，好处：
 *   1. 统一超时 / 输出采集 / 环境变量注入，避免每处重复踩坑；
 *   2. 将来要接 Shizuku / Root（su）时，只需新增一个实现，调用方不动；
 *   3. 便于测试（可注入假实现）。
 */
interface ShellBackend {

    val id: String

    data class Res(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
        fun text(): String {
            val sb = StringBuilder(out.trimEnd())
            if (err.isNotBlank()) sb.append(if (sb.isEmpty()) "" else "\n").append("[stderr] ").append(err.trimEnd())
            return sb.toString().trim()
        }
    }

    fun run(
        cmd: List<String>,
        cwd: File? = null,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000
    ): Res
}

/** 应用进程内直接 exec。native 可执行文件走私有 lib 目录（已解决 SELinux 禁 execve 私有目录）。 */
class DirectShellBackend : ShellBackend {
    override val id = "direct"

    override fun run(cmd: List<String>, cwd: File?, env: Map<String, String>, timeoutMs: Long): ShellBackend.Res {
        val pb = ProcessBuilder(cmd)
        if (cwd != null) pb.directory(cwd)
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.close()
        val out = StringBuilder()
        val err = StringBuilder()
        // 用独立线程抽干两个流，否则大输出会把管道写满、进程卡死、waitFor 永不返回
        val t1 = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        val t2 = Thread { runCatching { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
        t1.isDaemon = true; t2.isDaemon = true
        t1.start(); t2.start()
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            p.destroyForcibly()
            return ShellBackend.Res(-1, out.toString(), err.toString() + "\n[超时 ${timeoutMs}ms，已强杀]")
        }
        t1.join(500); t2.join(500)
        return ShellBackend.Res(p.exitValue(), out.toString(), err.toString())
    }
}

/** 提权后端（Shizuku / Root）。接口就位；需设备侧授权后再启用。 */
class SuShellBackend(private val suPath: String = "su") : ShellBackend {
    override val id = "su"

    override fun run(cmd: List<String>, cwd: File?, env: Map<String, String>, timeoutMs: Long): ShellBackend.Res {
        val line = (if (cwd != null) "cd ${cwd.absolutePath} && " else "") + cmd.joinToString(" ")
        return DirectShellBackend().run(listOf(suPath, "-c", line), null, env, timeoutMs)
    }
}

/**
 * Shizuku 后端：借 ADB 级权限执行。
 *
 * 为什么需要：App 自身是 `untrusted_app`，scoped storage 会把 `/sdcard` 下
 * 的文件级访问整个过滤掉（目录名看得见、文件看不见，cat 一律 Permission denied）。
 * 走 Shizuku 就是以 shell（uid 2000）身份跑，绕开这层过滤。
 *
 * 命令拼接与 [SuShellBackend] 一致：`sh -c "cd <cwd> && <cmd...>"`。
 */
class ShizukuShellBackend : ShellBackend {
    override val id = "shizuku"

    override fun run(cmd: List<String>, cwd: File?, env: Map<String, String>, timeoutMs: Long): ShellBackend.Res {
        val line = buildString {
            if (cwd != null) append("cd ").append(Shizuku2.shellQuote(cwd.absolutePath)).append(" && ")
            // 环境变量前缀（Shizuku 的 newProcess 不吃 env 参数，只能自己拼）
            env.forEach { (k, v) -> append(k).append('=').append(Shizuku2.shellQuote(v)).append(' ') }
            append(cmd.joinToString(" ") { Shizuku2.shellQuote(it) })
        }
        val (code, text) = Shizuku2.sh(line, timeoutMs)
        return if (code == null) {
            ShellBackend.Res(-1, "", text)
        } else {
            ShellBackend.Res(code, text, "")
        }
    }
}

/**
 * 全局入口。
 *
 * **自动选后端**：能用 Shizuku 就用 Shizuku（权限更高，能读受保护目录），
 * 否则退回进程内直接 exec。这样调用方（`shell_run` 工具等）完全不用关心
 * 用户有没有装 Shizuku —— 装了能力自动变强，没装就还是原来的样子。
 */
object Shell {
    @Volatile
    var backend: ShellBackend = DirectShellBackend()

    /** 强制指定后端（调试 / 测试用） */
    fun useBackend(b: ShellBackend) { backend = b }

    /** 当前该用哪个后端 */
    fun pick(): ShellBackend =
        if (Shizuku2.isReady()) ShizukuShellBackend() else backend

    /** 当前是否跑在提权模式下（UI 显示用） */
    fun isElevated(): Boolean = Shizuku2.isReady()

    fun run(
        cmd: List<String>,
        cwd: File? = null,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000
    ): ShellBackend.Res = pick().run(cmd, cwd, env, timeoutMs)
}