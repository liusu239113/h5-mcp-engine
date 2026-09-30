package com.mcp.h5engine

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简本地 CONNECT 代理 —— 用来救那些**自己解析不了域名**的子进程。
 *
 * ## 为什么需要它
 *
 * TapTap CLI 是个**静态链接的原生二进制**，它读 `/etc/resolv.conf` 做 DNS ——
 * 而 Android 沙箱里**根本没有这个文件**（系统的 DNS 走的是 netd，不是文件）。
 * 结果就是：能连 IP，但连不上域名，报 `transport failed / 运输失败`。
 * （同样的坑 Maker 那边踩过，当时用 node 的 dnsfix.js 绕开了；
 *   但 taptap-cli 不是 node 脚本，塞不进 -r 参数。）
 *
 * ## 它怎么救
 *
 * 让 CLI 把 HTTPS 请求发到我们这儿（`HTTPS_PROXY=http://127.0.0.1:port`），
 * 由**我们**去解析域名 —— App 进程走的是 Android 的 resolver，解析是好的。
 * 解析出来之后开一条 TCP 隧道，把字节原样来回搬。
 *
 * 因为是 CONNECT 隧道，TLS 是**端到端**的：我们只看得到域名和端口，
 * 看不到也改不了任何请求内容。**这不是中间人**。
 *
 * ## 边界
 *
 * · 只监听 127.0.0.1，外部访问不到；
 * · 只做 CONNECT（HTTPS 隧道），不支持明文 HTTP 转发 —— 用不上；
 * · 端口由系统分配（bind 0），避免和别的服务撞。
 */
object LocalProxy {

    private const val TAG = "hexoraProxy"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var port = 0
    private val running = AtomicBoolean(false)

    /** 代理当前监听的端口；0 = 没起来 */
    val listenPort: Int get() = port

    /** 起代理（幂等）。返回端口，失败返回 0。 */
    fun ensure(): Int {
        if (running.get() && port > 0) return port
        synchronized(this) {
            if (running.get() && port > 0) return port
            return runCatching {
                val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
                server = ss
                port = ss.localPort
                running.set(true)
                Thread({ acceptLoop(ss) }, "hexora-proxy").apply { isDaemon = true }.start()
                Log.i(TAG, "本地代理已启动，端口 $port")
                port
            }.getOrElse {
                Log.e(TAG, "代理启动失败: ${it.message}")
                0
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        port = 0
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val client = runCatching { ss.accept() }.getOrNull() ?: break
            Thread({ handle(client) }, "hexora-proxy-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30_000
            val ins = client.getInputStream()
            val out = client.getOutputStream()

            // 读请求头（CONNECT host:port HTTP/1.1 ... 直到空行）
            val head = readHeaders(ins) ?: return
            val first = head.lineSequence().firstOrNull().orEmpty()
            if (!first.startsWith("CONNECT ", ignoreCase = true)) {
                out.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            val target = first.split(' ').getOrNull(1).orEmpty()   // host:port
            val host = target.substringBeforeLast(':')
            val p = target.substringAfterLast(':').toIntOrNull() ?: 443

            // **关键这一步**：由 App 来解析域名（走 Android 的 resolver，能解析）
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (addr == null) {
                Log.w(TAG, "解析不了 $host")
                out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val upstream = Socket(addr, p)
            upstream.soTimeout = 60_000
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            out.flush()

            // 双向搬运。TLS 端到端，中间看不到内容。
            val up = upstream.getOutputStream()
            val down = upstream.getInputStream()
            val t1 = Thread { pipe(ins, up) }
            val t2 = Thread { pipe(down, out) }
            t1.start(); t2.start()
            t1.join()
            runCatching { upstream.close() }
            t2.join(2000)
        } catch (t: Throwable) {
            Log.w(TAG, "连接处理出错: ${t.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readHeaders(ins: InputStream): String? {
        val sb = StringBuilder()
        val buf = ByteArray(1)
        while (sb.length < 8192) {
            val n = ins.read(buf)
            if (n <= 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(buf[0].toInt().toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
        }
        return sb.toString()
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n <= 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Throwable) {
            // 对端关闭 / 超时，正常
        }
    }
}
