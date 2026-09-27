package com.mcp.h5engine

import android.content.Context
import java.io.File

/**
 * Maker 授权会话（全局单例）。
 *
 * 为什么要做成"会话"，而不是设置页里的一次性命令：
 * `taptap-maker login` 是 **浏览器授权 + 服务端轮询** 模型 ——
 *   1) 它把授权链接**立刻**打在 stdout（json 模式是第一行 JSON，message 字段里）；
 *   2) 然后每 1 秒轮询 `https://agent.tapapis.cn/cli-auth/result?code=…`，**最长 10 分钟**；
 *   3) 直到用户在网页 `https://maker.taptap.cn/pat-tokens?code=…` 上登录并点「创建 token」，
 *      它才写出 pat.json 并退出。
 *
 * 所以：
 *   · 必须**流式**读输出 —— 等进程退出再回读，链接永远显示不出来（旧版就死在这）；
 *   · 必须让进程活满 10 分钟，不能 90 秒掐掉；
 *   · 链接要能同时喂给 AI（写进回复）和 UI（点开浏览器）。
 *
 * 授权成功后 pat.json 落在 `TAPTAP_MAKER_HOME`，和 McpRt.startMaker 起的那条桥**同一目录**，
 * 因此桥不需要重启就能用上（桥是懒加载凭据的）。
 */
object MakerAuth {

    /** 链接里认 pat-tokens，避免把别的 URL 当授权链接 */
    private const val URL_RE = "https?://[^\\s\"']*pat-tokens[^\\s\"']*"

    /** idle | running | done | failed */
    @Volatile
    var state: String = "idle"
        private set

    @Volatile
    var url: String? = null
        private set

    @Volatile
    var lastError: String = ""
        private set

    private var worker: Thread? = null
    private val listeners = mutableListOf<(String, String?) -> Unit>()

    val running: Boolean get() = worker?.isAlive == true

    /** state / url 变化时回调（UI 用，回调可能来自任意线程，内部已切主线程） */
    fun addListener(l: (String, String?) -> Unit) {
        synchronized(listeners) { listeners += l }
    }

    fun removeListener(l: (String, String?) -> Unit) {
        synchronized(listeners) { listeners -= l }
    }

    private fun fire(st: String, u: String?) {
        state = st
        val copy = synchronized(listeners) { listeners.toList() }
        for (l in copy) runCatching { l(st, u) }
    }

    fun cancel() {
        MakerCli.cancelCurrent()
        worker = null
        if (state == "running") fire("idle", url)
    }

    /**
     * 开始（或复用）授权流程，返回授权链接；阻塞最多 [waitMs] 等链接出现。
     * 链接通常 <2 秒就有（CLI 起完 node 就打），所以放子线程调用、给 15 秒余量足够。
     */
    fun start(ctx: Context, waitMs: Long = 15_000): String? {
        val app = ctx.applicationContext
        url?.let { if (running) return it }
        synchronized(this) {
            if (!running) {
                url = null
                lastError = ""
                fire("running", null)
                val t = Thread {
                    val r = MakerCli.runStream(
                        app,
                        listOf("login", "--json"),
                        timeoutMs = 10 * 60_000 + 30_000
                    ) { line ->
                        if (url == null) {
                            val m = Regex(URL_RE).find(line)
                            if (m != null) {
                                val u = m.value.trim().trimEnd('.', ',', ';', ')')
                                url = u
                                fire("running", u)
                            }
                        }
                    }
                    if (r.ok || r.output.contains("login completed")) {
                        fire("done", url)
                    } else {
                        lastError = r.output.takeLast(300)
                        fire("failed", url)
                    }
                }
                t.isDaemon = true
                t.name = "maker-auth"
                worker = t
                t.start()
            }
        }
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            url?.let { return it }
            // 注意：线程可能还没被调度起来，isAlive 会瞬时为 false，
            // 所以这里用 state 判断（fire("running") 在线程 start 之前就置好了）。
            if (state != "running") break
            runCatching { Thread.sleep(200) }
        }
        return url
    }

    /** 给 AI / 设置页看的一句话状态 */
    fun statusText(ctx: Context): String = when {
        MakerCli.hasPat(ctx) -> "已授权（pat.json 已存在）"
        running -> "授权中（链接：" + (url ?: "还没拿到") + "）"
        state == "failed" -> "上次授权没成功：" + lastError.replace("\n", " ").take(160)
        else -> "未授权"
    }
}
