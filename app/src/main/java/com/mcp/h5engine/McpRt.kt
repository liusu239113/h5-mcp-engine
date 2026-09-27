package com.mcp.h5engine

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TapTap 小游戏 MCP 的本地运行时。
 *
 * APK 里带的是「musl 版 Node 20 + 官方 @taptap/instant-games-open-mcp」：
 *   assets/rt.tar            node + musl 全套库 + 官方包（含官方 musl 签名模块）
 *   jniLibs/libmuslrt.so     musl 动态加载器（安卓只允许从 lib/ 目录 exec）
 *
 * 首次启动把 rt.tar 解到私有目录，然后：
 *   libmuslrt.so --library-path <rt> <rt>/node package/dist/server.js
 * 以 Streamable HTTP 模式在 127.0.0.1:3000 起服务，App 再用 MCP 客户端去连。
 */
object McpRt {
    private const val STAMP = "2"
    const val PORT = 3000
    const val URL_BASE = "http://127.0.0.1:3000/"

    @Volatile
    var process: Process? = null
        private set

    private val busy = AtomicBoolean(false)

    fun rtDir(ctx: Context): File = File(ctx.filesDir, "hexrt")

    private fun stampFile(ctx: Context) = File(rtDir(ctx), ".stamp")

    private fun loader(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libmuslrt.so")

    fun ready(ctx: Context): Boolean {
        val s = stampFile(ctx)
        return s.isFile && s.readText().trim() == STAMP && File(rtDir(ctx), "node").isFile
    }

    fun running(): Boolean = process?.isAlive == true

    fun health(): Boolean = try {
        val c = URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 1500
        val code = c.responseCode
        c.disconnect()
        code == 200
    } catch (t: Throwable) {
        false
    }

    /** 解包运行时（阻塞，必须放子线程）。返回 null 表示成功 */
    fun extract(ctx: Context, log: (String) -> Unit): String? {
        if (ready(ctx)) return null
        return try {
            val dir = rtDir(ctx)
            dir.deleteRecursively()
            dir.mkdirs()
            val tar = File(dir, "rt.tar")
            log("首次运行：正在释放运行时（约 60MB，只做一次）…")
            ctx.assets.open("rt.tar").use { ins ->
                FileOutputStream(tar).use { ins.copyTo(it, 1 shl 16) }
            }
            log("正在解包…")
            extractTar(tar, dir)
            tar.delete()
            stampFile(ctx).writeText(STAMP)
            File(dir, "node").setExecutable(true, false)
            log("运行时就绪")
            null
        } catch (t: Throwable) {
            "解包失败：${t.message}"
        }
    }

    /** 拉起服务；返回 null 表示成功（阻塞，放子线程） */
    fun start(ctx: Context, log: (String) -> Unit): String? {
        if (running() && health()) return null
        if (!busy.compareAndSet(false, true)) return "启动中，请稍候"
        return try {
            val dir = rtDir(ctx)
            val ld = loader(ctx)
            val node = File(dir, "node")
            val server = File(dir, "package/dist/server.js")
            if (!ld.isFile) return "缺少 libmuslrt.so（本包只支持 arm64 设备）"
            if (!node.isFile || !server.isFile) return "运行时未解包"
            stop()
            val home = File(dir, "home").apply { mkdirs() }
            writeDnsMap(ctx)
            val pb = ProcessBuilder(
                ld.absolutePath, "--library-path", dir.absolutePath,
                node.absolutePath,
                // Android 沙箱里没有 /etc/ssl/certs，用 node 内置的 Mozilla CA
                "--use-bundled-ca",
                // musl 只读 /etc/resolv.conf（Android 沙箱里没有），改用 node 自己的 UDP 查询
                "-r", File(dir, "dnsfix.js").absolutePath,
                server.absolutePath
            )
            pb.directory(File(dir, "package"))
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["HOME"] = home.absolutePath
            env["TMPDIR"] = home.absolutePath
            env["PATH"] = dir.absolutePath
            env["TAPTAP_MCP_TRANSPORT"] = "http"
            env["TAPTAP_MCP_PORT"] = PORT.toString()
            log("正在启动 TapTap MCP 服务…")
            val p = pb.start()
            process = p
            // 吃掉输出，否则管道写满会卡住子进程
            // 把 node 输出全部落盘（保留尾部），下次「服务没了」时能看到它到底怎么死的
            val logFile = File(dir, "mcp.log")
            runCatching { if (logFile.length() > 200_000) logFile.writeText("") }
            Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (line.contains("error", true) || line.contains("✅")) log(line.take(200))
                        runCatching {
                            if (logFile.length() > 200_000) logFile.writeText("")
                            logFile.appendText(line + "\n")
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                if (health()) {
                    log("服务已就绪（79 个工具）")
                    return null
                }
                if (!p.isAlive) return "服务启动失败（退出码 ${p.exitValue()}）"
                Thread.sleep(600)
            }
            "服务启动超时"
        } catch (t: Throwable) {
            "启动失败：${t.message}"
        } finally {
            busy.set(false)
        }
    }

    /**
     * 用 Android 自己的解析器（走 netd，沙箱里一定可用）预先解析关键域名，
     * 写成 dns.map 给 dnsfix.js 兜底：万一 UDP DNS 被墙/被劫持，还有这条路。
     */
    private fun writeDnsMap(ctx: Context) {
        val hosts = listOf(
            "agent.tapapis.cn", "accounts.tapapis.cn", "www.taptap.cn",
            "api.taptap.cn", "openapi.taptap.cn", "developer.taptap.cn"
        )
        val obj = org.json.JSONObject()
        for (h in hosts) {
            runCatching { obj.put(h, java.net.InetAddress.getByName(h).hostAddress) }
        }
        runCatching { File(rtDir(ctx), "dns.map").writeText(obj.toString()) }
    }

    fun stop() {
        runCatching { process?.destroy() }
        process = null
    }

    // ==================== tar 解包（ustar + GNU longname + pax 跳过） ====================

    private fun extractTar(tar: File, outDir: File) {
        RandomAccessFile(tar, "r").use { raf ->
            val header = ByteArray(512)
            var longName: String? = null
            while (true) {
                if (raf.read(header) != 512) break
                if (header.all { it == 0.toByte() }) break
                val rawName = str(header, 0, 100)
                val sizeStr = str(header, 124, 12).trim()
                val flag = header[156].toInt().toChar()
                val prefix = str(header, 345, 155)
                val linkName = str(header, 157, 100)
                val size = sizeStr.toLongOrNull(8) ?: 0L
                val pad = ((512 - (size % 512)) % 512).toInt()
                if (flag == 'L') {                       // GNU 长文件名
                    val b = ByteArray(size.toInt())
                    raf.readFully(b)
                    longName = String(b, Charsets.UTF_8).trimEnd('\u0000', '\n')
                    if (pad > 0) raf.skipBytes(pad)
                    continue
                }
                if (flag == 'x' || flag == 'g') {        // pax 扩展头：跳过
                    raf.skipBytes(size.toInt() + pad)
                    continue
                }
                val full = (longName ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName)
                    .removePrefix("./")
                longName = null
                if (full.isEmpty()) {
                    if (size > 0) raf.skipBytes(size.toInt() + pad)
                    continue
                }
                val target = File(outDir, full)
                when (flag) {
                    '5' -> target.mkdirs()
                    '2' -> {
                        target.parentFile?.mkdirs()
                        if (!target.exists()) runCatching { Os.symlink(linkName, target.absolutePath) }
                    }
                    '0', '\u0000', '7' -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(1 shl 16)
                            var left = size
                            while (left > 0) {
                                val n = raf.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                left -= n
                            }
                        }
                        if (pad > 0) raf.skipBytes(pad)
                    }
                    else -> if (size > 0) raf.skipBytes(size.toInt() + pad)
                }
            }
        }
    }

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val lim = off + len
        while (end < lim && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }
}