package com.mcp.h5engine

import android.content.Context
import fi.iki.elonen.NanoHTTPD

/**
 * 本地服务管理器（D2）：持有单例 LocalServer，供工具启停。
 * 服务监听 0.0.0.0:8787，局域网内可访问 —— 只有知道 token 的人能调用 /api/。
 */
object LocalServerHost {

    @Volatile
    private var server: LocalServer? = null

    @Synchronized
    fun start(ctx: Context, port: Int = 8787): String {
        server?.let { return "本地服务已在运行，token=${it.token}" }
        val s = LocalServer(ctx, port)
        runCatching { s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            .getOrElse { return "启动失败：${it.message}" }
        server = s
        return "本地服务已启动（端口 $port）。token=${s.token}"
    }

    @Synchronized
    fun stop(): String {
        val s = server ?: return "本地服务未运行"
        runCatching { s.stop() }
        server = null
        return "本地服务已停止"
    }

    fun status(): String = server?.let { "运行中 token=${it.token}" } ?: "未运行"
}