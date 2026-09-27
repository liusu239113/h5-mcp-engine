package com.mcp.h5engine

import android.content.Context

/**
 * 把「确保内置 MCP 活着」这件事收在一个地方，
 * 供三处共用：App 启动、前台服务守护、工具调用失败后的自动复活。
 *
 * 背景：内置的 TapTap MCP 是本地 node 子进程，Android 冻结/回收后台应用时
 * 会把它一起带走，表现为 "Failed to connect to /127.0.0.1:3000"。
 * 这里做成幂等：先看解包，再看健康，最后挂工具，任一步失败都返回 null（不抛）。
 */
object McpBoot {

    /** 幂等。可在任意线程调用（内部是阻塞的，别放主线程） */
    fun ensure(ctx: Context, log: (String) -> Unit = {}): McpHub? = try {
        val enabled = McpStore.load(ctx).filter { it.enabled }
        if (enabled.isEmpty()) {
            null
        } else {
            var ok = true
            if (!McpRt.ready(ctx)) {
                log("MCP：首次运行，正在释放内置运行时（约 60MB，只做一次）…")
                ok = McpRt.extract(ctx, log) == null
            }
            if (ok && !McpRt.health()) {
                log("MCP：正在启动本地服务…")
                ok = McpRt.start(ctx, log) == null
            }
            if (!ok) {
                null
            } else {
                val hub = EngineTools.mcp ?: McpHub()
                hub.refresh(enabled, log)
                // 工具调用撞上「服务已死」时，会回调这里把它拉起来并重试
                hub.onEnsure = { ensure(ctx) != null }
                EngineTools.mcp = hub
                hub
            }
        }
    } catch (t: Throwable) {
        log("MCP：初始化异常 ${t.javaClass.simpleName}: ${t.message}")
        null
    }
}