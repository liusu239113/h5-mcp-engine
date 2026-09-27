package com.mcp.h5engine

import android.content.Context
import java.io.File

/**
 * 把「确保内置 MCP 活着」这件事收在一个地方，
 * 供三处共用：App 启动、前台服务守护、工具调用失败后的自动复活。
 *
 * 背景：内置的 TapTap MCP 是本地 node 子进程，Android 冻结/回收后台应用时
 * 会把它一起带走，表现为 "Failed to connect to /127.0.0.1:3000"。
 * 这里做成幂等：先看解包，再看健康，最后挂工具，任一步失败都返回 null（不抛）。
 *
 * 现在有两条服务：
 *   3000 官方 MCP（Streamable HTTP，官方包自己支持）
 *   3011 Maker（原生只给 stdio，经 bridge.js 包成 HTTP）
 */
object McpBoot {

    /** 当前项目目录（Maker 要靠它知道素材落哪儿） */
    fun projectDirOf(ctx: Context): String {
        val id = runCatching { AiConfigStore(ctx).lastGame }.getOrDefault("").ifBlank { "demo" }
        return File(File(ctx.filesDir, "games"), id).apply { mkdirs() }.absolutePath
    }

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
                // Maker 是独立进程：它死了不该连累官方 MCP，反之亦然
                if (enabled.any { it.url.contains(McpRt.MAKER_PORT.toString()) }) {
                    val proj = projectDirOf(ctx)
                    if (!McpRt.makerReady(proj)) {
                        val e = McpRt.startMaker(ctx, log, proj)
                        if (e != null) log("MCP：Maker 未就绪（$e）")
                    }
                }
                val hub = EngineTools.mcp ?: McpHub()
                hub.refresh(enabled, log)
                // 工具调用撞上「服务已死」时，会回调这里把它拉起来并重试
                hub.onEnsure = { ensure(ctx) != null }
                EngineTools.mcp = hub
                // 连上了、但只回了桥的本地工具（生成类工具还没注册上来）：过几秒自己重抓
                retryWhenStarved(ctx, hub, log)
                hub
            }
        }
    } catch (t: Throwable) {
        log("MCP：初始化异常 ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** 同一时间只跑一条「工具没注册齐 → 自动重抓」的后台线程，避免 ensure 递归把自己点着 */
    private val starvedRetry = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Maker 通道「连上了、但只有桥的本地工具」时，隔几秒自己重抓一遍清单。
     *
     * 这是用户报「生图工具一会儿能用一会儿不能用」的第二道保险：
     * 工具清单只在 App 启动 / 掉线时才抓一次，那一次要是正好撞上子进程重启，
     * 抓到的清单里就没有 generate_image 这类工具，然后被永久缓存住。
     * 桥侧已经会在 tools/list 时给子进程一个等待窗口（waitChild），这里再兜一层。
     */
    private fun retryWhenStarved(ctx: Context, hub: McpHub, log: (String) -> Unit) {
        if (!hub.makerStarved) return
        if (!starvedRetry.compareAndSet(false, true)) return
        Thread {
            try {
                // 逐步拉长间隔：子进程重启通常几秒内就好，别一上来就疯狂重试
                for (d in longArrayOf(3_000, 6_000, 10_000, 15_000, 20_000)) {
                    Thread.sleep(d)
                    val cur = EngineTools.mcp
                    if (cur == null || !cur.makerStarved) return@Thread
                    log("MCP：Maker 只回了本地工具，正在自动重抓工具清单…")
                    ensure(ctx, log)
                }
            } catch (t: Throwable) {
                // 重抓失败不重要：下一轮守护巡检、或用户下一次调用还会再来
            } finally {
                starvedRetry.set(false)
            }
        }.apply { isDaemon = true; name = "mcp-starved-retry" }.start()
    }
}