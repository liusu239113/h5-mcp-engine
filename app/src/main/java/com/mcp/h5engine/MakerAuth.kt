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

    // ==================== 换号 / 退出 / 直存 token ====================

    /**
     * 退出授权：掐断在跑的 login、删掉 pat.json、状态复位。
     * 返回是否删干净（没文件也算干净）。
     */
    fun logout(ctx: Context): Boolean {
        cancel()
        val ok = runCatching {
            val f = MakerCli.patFile(ctx)
            if (f.exists()) f.delete() else true
        }.getOrDefault(false)
        url = null
        lastError = ""
        fire("idle", null)
        return ok
    }

    /**
     * 换号：先清掉旧凭证，再立刻开一轮全新授权，返回新链接。
     *
     * 用户场景：手上有多个 TapTap 号，要给不同号 / 不同 Maker 账号授权。
     * 旧版只会「已授权就别再授权」，于是换不了号 —— 这里先 logout 再 start。
     */
    fun switch(ctx: Context, waitMs: Long = 15_000): String? {
        logout(ctx)
        return start(ctx, waitMs)
    }

    /**
     * 直接写入 token（用户在 maker.taptap.cn 生成后把整串粘进来），不走浏览器回跳。
     *
     * 为什么需要：浏览器回跳那条路依赖内置浏览器 + 服务端轮询，遇到网络/版本问题很容易卡住；
     * 让用户去官网手动建 token 再粘回来，是最稳的兜底。
     */
    fun setToken(ctx: Context, token: String): String {
        val t = token.trim()
        if (t.length <= 8 || !t.contains("token")) {
            return "token 看起来不对（长度≤8 或缺少 token 字样）。请到 https://maker.taptap.cn/pat-tokens " +
                "登录后「创建 token」，把完整一串复制过来。"
        }
        return runCatching {
            val f = MakerCli.patFile(ctx)
            f.parentFile?.mkdirs()
            val esc = t.replace("\\", "\\\\").replace("\"", "\\\"")
            f.writeText("{\"token\":\"$esc\"}")
            url = null
            lastError = ""
            fire("idle", null)
            "已写入 Maker 授权（pat.json）。现在可以直接生图 / 生音乐 / 生音效了，不用重启。"
        }.getOrElse { "写入失败：${it.message}" }
    }

    /** 给 AI / 设置页看的一句话状态 */
    fun statusText(ctx: Context): String = when {
        MakerCli.hasPat(ctx) -> "已授权（pat.json 已存在）"
        running -> "授权中（链接：" + (url ?: "还没拿到") + "）"
        state == "failed" -> "上次授权没成功：" + lastError.replace("\n", " ").take(160)
        else -> "未授权"
    }
}
