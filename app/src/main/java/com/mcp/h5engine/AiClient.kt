package com.mcp.h5engine

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** AI 要求调用某个工具 */
data class ToolCall(val id: String, val name: String, val argsJson: String)

/** 对话里的一条消息。images 就是多模态输入（截图） */
data class ChatMsg(
    val role: String,
    val text: String? = null,
    val images: List<ByteArray> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null
)

data class ChatReply(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val error: String? = null,
    /** 部分模型（deepseek-reasoner 等）会把思维链单独返回，拿出来给「思考过程」面板用 */
    val reasoning: String? = null,
    /** 本次请求的输入/输出 token（服务商 usage 字段；拿不到就是 0） */
    val inTokens: Int = 0,
    val outTokens: Int = 0
)

/**
 * 一套接口适配三家协议：
 *   OPENAI    -> POST {base}/chat/completions      （国内 95% 厂商都兼容这个）
 *   ANTHROPIC -> POST {base}/messages
 *   GEMINI    -> POST {base}/models/{model}:generateContent
 *
 * 同步阻塞，调用方必须放在子线程。
 */
class AiClient(private val cfg: ProviderConfig) {

    /**
     * 拿共享的 OkHttpClient（整个 App 进程一份）。
     *
     * 以前是**每次请求**都 build() 一个新客户端：连接池、线程池全扔掉，等于每一步都要
     * 重新做一次 TCP + TLS 握手（手机上 0.3~1.5 秒）。一轮任务几十步，光握手就白等几十秒 ——
     * 而且这跟模型快慢无关。OkHttp 官方也明确要求 client 应当复用。
     *
     * direct=true 时走直连（忽略系统代理/VPN 设的 HTTP 代理）——
     * 这类代理层回的错误（402 / 403 / 各种网关页）经常和「余额不足」长得一模一样。
     */
    private fun newHttp(direct: Boolean = false): OkHttpClient =
        if (direct) directHttp else sharedHttp

    /** 当前是否走了系统代理（Android 的系统代理设置，VPN/代理类 App 会写入） */
    private fun systemProxyInUse(): Boolean = runCatching {
        val sel = java.net.ProxySelector.getDefault() ?: return false
        sel.select(java.net.URI(endpoint)).any { it.type() != java.net.Proxy.Type.DIRECT }
    }.getOrDefault(false)

    /** 正在飞的请求。点「停止」时要立刻掐断，而不是干等 240 秒读超时 */
    @Volatile
    private var inflight: okhttp3.Call? = null

    @Volatile
    private var aborted = false

    fun abort() {
        aborted = true
        runCatching { inflight?.cancel() }
    }

    private val endpoint: String
        get() {
            val base = cfg.baseUrl.trim()
                .removeSuffix("/")
                .ifBlank { cfg.provider.baseUrl.trimEnd('/') }
            return when (cfg.provider.protocol) {
                Protocol.OPENAI -> "$base/chat/completions"
                Protocol.ANTHROPIC -> "$base/messages"
                Protocol.GEMINI -> "$base/models/${cfg.model}:generateContent"
            }
        }

    /**
     * 带自动重试的对话请求。
     * 重试只针对「网络类」错误（切后台 / 切网 / 连接被系统掐断），
     * 鉴权、模型名这种硬错误会立刻返回，不做无意义重试。
     */
    /**
     * 修掉「被掐断的工具参数」。
     *
     * tool_calls 的 arguments 必须是**合法 JSON 字符串**，否则服务商直接 400：
     *   「Assistant tool call ... arguments must be valid JSON.」（Agnes 实测报过这条）
     *
     * 什么时候会不合法：流式返回被中途掐断（点停止 / 切后台 / 网络断），
     * 参数只拼到一半 —— `{"path":"js/sc` 这种残串会被原样存进历史。
     * 而它**不是一次性故障**：只要这条消息还在历史窗口里，之后每一条请求都会被拒，
     * 用户看到的就是「AI 老是中断，然后一直报错」。这比报一次错严重得多。
     *
     * 修法：参数不合法就换成 `{}` —— 保住 tool_calls 与 tool 的配对关系
     * （比把整轮拆掉安全）。真执行时会拿到一个空参数调用，工具层如实报错、模型自己会重调。
     */
    private fun repairToolArgs(src: List<ChatMsg>): List<ChatMsg> {
        if (src.none { it.toolCalls.isNotEmpty() }) return src
        return src.map { m ->
            if (m.toolCalls.isEmpty()) return@map m
            m.copy(toolCalls = m.toolCalls.map { tc ->
                if (isJsonObject(tc.argsJson)) tc else ToolCall(tc.id, tc.name, "{}")
            })
        }
    }

    /** arguments 得是个合法的 JSON **对象**串（`{}` 起步、能被 JSONObject 解析） */
    private fun isJsonObject(s: String): Boolean {
        val t = s.trim()
        if (!t.startsWith("{")) return false
        return runCatching { JSONObject(t); true }.getOrDefault(false)
    }

    /**
     * 历史清洗：保证 tool_calls / tool 消息严格成对。
     *
     * 触发场景：会话落盘时旧版本只存了 role+text，assistant 的 tool_calls 丢了，
     * 回读后 tool 消息变成「孤儿」，DeepSeek 直接 400：
     *   Messages with role 'tool' must be a response to a preceding message with 'tool_calls'
     * 这里统一兜底：一轮工具调用只要没拿全响应，就整轮拆掉（退化成纯文本），
     * 孤儿 tool 消息降级成一条注明出处的 user 文本 —— 宁可少点上下文，也不要整段会话打不开。
     * 注意：UI 渲染用的是原始 history，这里只影响发出去的请求体。
     */
    private fun sanitizeHistory(src: List<ChatMsg>): List<ChatMsg> {
        if (src.none { it.role == "tool" || it.toolCalls.isNotEmpty() }) return src
        // 第一遍：一轮工具调用必须「每个 id 都有响应」才算完整
        val ok = BooleanArray(src.size)
        for (i in src.indices) {
            val m = src[i]
            if (m.role != "assistant" || m.toolCalls.isEmpty()) continue
            val missing = m.toolCalls.map { it.id }.toMutableSet()
            var j = i + 1
            while (j < src.size && src[j].role == "tool") {
                src[j].toolCallId?.let { missing.remove(it) }
                j++
            }
            ok[i] = missing.isEmpty()
        }
        // 第二遍：按顺序重建
        val out = ArrayList<ChatMsg>(src.size)
        for (i in src.indices) {
            val m = src[i]
            when {
                // 残缺的一轮：拆掉 tool_calls，只留文本
                m.role == "assistant" && m.toolCalls.isNotEmpty() && !ok[i] ->
                    if (!m.text.isNullOrBlank()) out += ChatMsg("assistant", m.text, m.images)

                m.role == "tool" -> {
                    val prev = out.lastOrNull()
                    val paired = prev != null && prev.role == "assistant" && m.toolCallId != null &&
                        prev.toolCalls.any { it.id == m.toolCallId }
                    if (paired) out += m
                    else if (!m.text.isNullOrBlank())
                        out += ChatMsg("user", "（历史工具结果，对应的调用记录已丢失）\n" + m.text)
                }

                else -> out += m
            }
        }
        return out
    }

    fun chat(rawHistory: List<ChatMsg>, tools: List<JSONObject>): ChatReply {
        aborted = false
        // 顺序有讲究：
        //   ① 按条数裁窗口 → ② 清洗 tool 配对（裁剪会把某个 assistant(tool_calls) 的响应截到
        //   窗口外，不洗的话服务商直接 400）→ ③ 长工具结果压占位 → ④ 旧截图清掉。
        // ③④ 只改消息**内容**、不动条数也不动配对关系，所以放最后，不会破坏 ② 的成果。
        val history = trimImages(
            compactToolResults(sanitizeHistory(repairToolArgs(limitHistory(rawHistory))))
        )
        // 设置里可以直接关掉工具定义：50+ 个工具的 JSON 是 token 大头，
        // 免费档模型（Groq 8000 TPM 这类）关掉立刻就能用。
        val toolsUse = if (cfg.sendTools) tools else emptyList()
        val base = when (cfg.provider.protocol) {
            Protocol.OPENAI -> bodyOpenAi(history, toolsUse)
            Protocol.ANTHROPIC -> bodyAnthropic(history, toolsUse)
            Protocol.GEMINI -> bodyGemini(history, toolsUse)
        }
        // 出错后「原样重试」几乎没用（余额、请求体量都没变），必须换一种形状再试：
        //   ① 换出口：绕过系统代理直连
        //   ② 变小：去掉工具定义 + 压小输出上限
        //   ③ 再小：连历史一起砍到最近几条
        var body = base
        var lightTried = false
        var ultraTried = false
        var directTried = false
        var direct = false

        var last: ChatReply = ChatReply(null, emptyList(), "未发起请求")
        for (attempt in 1..4) {
            val r = once(
                body, direct,
                when {
                    ultraTried -> " · 已精简(去工具+砍历史)"
                    lightTried -> " · 已精简(去工具)"
                    else -> ""
                }
            )
            last = r
            val err = r.error
            if (err == null) {                    // 成功
                TokenStats.add(r.inTokens, r.outTokens)
                return r
            }
            if (aborted) return ChatReply(null, emptyList(), "已取消")   // 用户点了停止
            // ① 手机开着 VPN / 代理类 App 时，OkHttp 会继承 Android 系统代理，
            // 请求可能根本没到服务商，是代理层回的错误（402/413 都见过）。
            if (!direct && !directTried && systemProxyInUse()) {
                directTried = true
                direct = true
                continue
            }
            // ②③ 请求体积超限（413 / TPM / 上下文超长）：越试越小。
            // 这是免费档/小模型最常见的失败，重试同一个请求永远是同一个错。
            if (cfg.autoSlim && isTooLargeError(err)) {
                if (!lightTried) {
                    lightTried = true
                    body = lightweight(base)
                    continue
                }
                if (!ultraTried) {
                    ultraTried = true
                    body = ultraLight(lightweight(base))
                    continue
                }
            }
            if (isBalanceError(err) && !lightTried) {
                lightTried = true
                body = lightweight(base)
                continue                        // 立刻换轻量请求再试，不 sleep
            }
            if (!isTransient(err)) return r    // 硬错误（鉴权/模型名）不重试
            if (attempt < 4) {
                runCatching { Thread.sleep(if (attempt == 1) 500L else 1500L) }
            }
        }
        return last
    }

    /** 历史裁剪：保留首条 system + 最近 historyLimit 条。上下文超长时的第一道闸 */
    private fun limitHistory(src: List<ChatMsg>): List<ChatMsg> {
        val limit = cfg.historyLimit.coerceAtLeast(4)
        if (src.size <= limit + 1) return src
        val head = src.firstOrNull()?.takeIf { it.role == "system" }
        val rest = if (head != null) src.drop(1) else src
        var tail = rest.takeLast(limit)
        // 起点尽量落在「用户消息」上：从 assistant/tool 中间开始的话，
        // 前面那半轮会变成孤儿（sanitizeHistory 能兜住，但白丢一整轮上下文）。
        val firstUser = tail.indexOfFirst { it.role == "user" }
        if (firstUser > 0) tail = tail.drop(firstUser)
        return if (head != null) listOf(head) + tail else tail
    }

    /**
     * 较早的「长」工具结果压成一行占位。
     *
     * 一条 16000 字符的工具结果 ≈ 6k token，而且它会在**之后每一步**的请求里被重复发送 ——
     * 十步前读过的文件，模型基本不会再回头看，但每一轮都在为它付钱。
     *
     * 只压「长」的（>400 字）：像「已写入 x（行数 120 → 135）」这种短回执原样留着 ——
     * 那是任务的进度线索，丢了模型会以为自己没干过。被压的全是大文件 / 搜索结果，
     * 占位文字里明确写了「要细节就重新读一次」，它自己会去补。
     */
    private fun compactToolResults(src: List<ChatMsg>): List<ChatMsg> {
        val keep = cfg.keepToolResults
        val idx = src.indices.filter { src[it].role == "tool" }
        if (idx.size <= keep) return src
        val recent = idx.takeLast(keep).toHashSet()
        return src.mapIndexed { i, m ->
            val n = m.text?.length ?: 0
            if (m.role != "tool" || i in recent || n <= 400) m
            else m.copy(
                text = "（较早的工具结果共 " + n + " 字，已省略以省上下文；需要细节请重新调用一次对应工具）"
            )
        }
    }

    /**
     * 历史里的截图只保留最近 N 张，更早的换成一行文字说明。
     *
     * 图片是按 base64 **直接塞进请求体**的：一张 720px JPEG ≈ 60~150 KB，base64 后还要 ×1.37，
     * 而且**每一步**都连同历史重发一遍。历史里攒十张 = 每步上传 1~2 MB ——
     * 手机上行带宽是瓶颈，光这一项就能让每步多等十几秒，token 也照样计费。
     *
     * AI 自检看的是**最新**那一帧，旧截图留着既没用又贵。但必须留一行文字说明：
     * 直接抹掉的话模型会以为自己「本来就没截过图」，转头重复劳动。
     */
    private fun trimImages(src: List<ChatMsg>): List<ChatMsg> {
        val keep = cfg.keepImages
        val withImg = src.indices.filter { src[it].images.isNotEmpty() }
        if (withImg.size <= keep) return src
        val dropped = withImg.dropLast(keep).toHashSet()
        return src.mapIndexed { i, m ->
            if (i !in dropped) m
            else m.copy(
                text = (m.text ?: "").trimEnd() +
                    "\n（较早的截图已省略以省流量，内容就是当时那一帧；需要再看请重新截图）",
                images = emptyList()
            )
        }
    }

    /** 请求体积超限：413 / TPM 限流 / 上下文超长。这类错误必须换小请求，重试同一条没意义 */
    private fun isTooLargeError(err: String): Boolean {
        val e = err.lowercase()
        return e.contains("http 413") || e.contains("http413") ||
            e.contains("request too large") || e.contains("tokens per minute") ||
            e.contains("rate_limit_exceeded") || e.contains("context length") ||
            e.contains("maximum context") || e.contains("too many tokens") ||
            e.contains("reduce your message size") || e.contains("tpm")
    }

    /** 余额 / 额度类错误（只有这类才值得降级重试） */
    private fun isBalanceError(err: String): Boolean {
        val e = err.lowercase()
        return e.contains("http 402") || e.contains("insufficient balance") ||
            e.contains("insufficient_balance") || e.contains("exceeded your current quota")
    }

    /**
     * 轻量版请求体：去掉全部工具定义、把输出上限压到 512。
     * 工具定义（官方 MCP 50+ 个）本身就是几万 token 的大头，去掉后请求能小一个量级。
     */
    private fun lightweight(body: JSONObject): JSONObject {
        val o = JSONObject(body.toString())
        o.remove("tools")
        o.remove("tool_choice")
        when (cfg.provider.protocol) {
            Protocol.OPENAI, Protocol.ANTHROPIC -> o.put("max_tokens", 512)
            Protocol.GEMINI -> {}
        }
        return o
    }

    /**
     * 最狠的一档：只剩「系统提示 + 最近 6 条消息 + 256 输出」。
     * 专治 413 / TPM 超限（Groq 免费档 8000 TPM、部分中转 4k 上下文之类）。
     * 代价是 AI 会「忘掉」细节，所以只在自动降级链的最后一步用。
     */
    private fun ultraLight(body: JSONObject): JSONObject {
        val o = JSONObject(body.toString())
        o.remove("tools")
        o.remove("tool_choice")
        val msgs = o.optJSONArray("messages")
        if (msgs != null && msgs.length() > 6) {
            val arr = JSONArray()
            val first = msgs.optJSONObject(0)
            if (first != null && strOf(first, "role") == "system") arr.put(first)
            for (i in (msgs.length() - 6).coerceAtLeast(0) until msgs.length()) arr.put(msgs.get(i))
            o.put("messages", arr)
        }
        when (cfg.provider.protocol) {
            Protocol.OPENAI, Protocol.ANTHROPIC -> o.put("max_tokens", 256)
            Protocol.GEMINI -> {}
        }
        return o
    }

    private fun once(body: JSONObject, direct: Boolean = false, note: String = ""): ChatReply {
        // 流式优先：OpenAI 兼容协议默认开 stream —— 让「正在生成」的字实时出现在对话里。
        // 服务商不认 SSE 时下面会自动退回非流式整段读，但那是**把整个请求体去掉 stream 重发一遍**：
        // 每步都白付一次 token 和时间。所以一家试出来不支持就记下来，同一个 Base URL 不再试。
        val streamKey = cfg.provider.id + "|" + cfg.baseUrl
        val wantStream = cfg.provider.protocol == Protocol.OPENAI &&
            !body.has("stream") && !streamKnownOff(streamKey)
        if (wantStream) {
            body.put("stream", true)
            // OpenAI 规定：**流式响应默认不带 usage**。不显式要，TokenStats 就永远是 0 ——
            // 用户看到的「token 统计是个假功能」根子在这儿。
            // 部分中转/老网关不认这个字段，下面遇到相关报错会去掉它重试一次。
            runCatching { body.put("stream_options", JSONObject().put("include_usage", true)) }
        }
        // 注意这里**不再写** Connection: close —— 那句会让每条请求用完就断，
        // 共享连接池等于白建，每一步都要重新握手。
        val rb = Request.Builder().url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))

        when (cfg.provider.protocol) {
            Protocol.OPENAI ->
                rb.header("Authorization", "Bearer ${cfg.apiKey}")
            Protocol.ANTHROPIC ->
                rb.header("x-api-key", cfg.apiKey).header("anthropic-version", "2023-06-01")
            Protocol.GEMINI ->
                rb.header("x-goog-api-key", cfg.apiKey)
        }

        val call = newHttp(direct).newCall(rb.build())
        inflight = call
        return try {
            call.execute().use { r ->
                if (wantStream && r.isSuccessful && r.body != null) {
                    val sj = runCatching { readSse(r.body) }.getOrNull()
                    if (sj != null) return parse(sj)
                    // 服务商没按 SSE 回（200 但不是 SSE —— 这是个稳定信号，不是偶发抖动）：
                    // 记进「这家不支持流式」，顺手去掉 stream 重发一次，走下面的整段读。
                    // 记下之后，后面每一步就不必先白试一遍了。
                    markStreamOff(streamKey)
                    body.remove("stream")
                    return once(body, direct, note)
                }
                // 边收边回显：把正在流回来的「思考 / 正文」实时交给界面。
                // 思考版模型长时间推理时可能几十秒不吐正文，没有这个回调，
                // 用户看到的只有一句「思考中…」，只能以为卡死。
                val text = if (r.isSuccessful) readWithProgress(r.body)
                else (r.body?.string() ?: "")
                if (!r.isSuccessful) {
                    // 有些中转/老网关不认 stream_options（我们为了拿 token 统计加的）：
                    // 去掉它重试一次。宁可这次没有统计，也不能因为这个可选字段把请求整个搞挂。
                    val low = text.lowercase()
                    if (body.has("stream_options") &&
                        (low.contains("stream_options") || low.contains("include_usage") ||
                            low.contains("unknown") || low.contains("unsupported"))
                    ) {
                        body.remove("stream_options")
                        return once(body, direct, note)
                    }
                    // 把「这条请求到底发给了谁、带了多大东西」一并带出来。
                    // 以前只显示服务商原文，用户和 AI 都无从判断是 Key / Base URL / 请求体量哪一环的问题
                    // （比如「明明有钱却报余额不足」，很可能根本是另一把 Key 或另一条 URL）。
                    val diag = "[诊断] url=$endpoint · model=${cfg.model} · key=${AiConfigStore.mask(cfg.apiKey)}" +
                        " · 请求体 ${body.toString().length} 字节" +
                        " · 消息 ${body.optJSONArray("messages")?.length() ?: 0} 条" +
                        " · 工具 ${body.optJSONArray("tools")?.length() ?: 0} 个" +
                        (if (direct) " · 直连(已绕过系统代理)" else " · 走系统代理") + note
                    ChatReply(null, emptyList(), "HTTP ${r.code} ${text.take(600)}\n$diag")
                } else {
                    // 诊断：这个服务商到底给不给思考内容。
                    // 不给的话，界面上再怎么改都不会有「思考文字」可显示 —— 该换模型，不是改 UI。
                    runCatching {
                        android.util.Log.i(
                            "hexoraAi",
                            "proto=" + cfg.provider.protocol + " model=" + cfg.model +
                                " len=" + text.length +
                                " reasoningLike=" + (text.contains("reasoning") ||
                                text.contains("\"thinking\"")) +
                                " head=" + text.take(240).replace("\n", " ")
                        )
                    }
                    parse(text)
                }
            }
        } catch (t: Throwable) {
            ChatReply(null, emptyList(), "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            inflight = null
        }
    }

    /** 发一条极短消息，用来验证 Key / Base URL / 模型名是否可用 */
    fun test(): String {
        val started = System.currentTimeMillis()
        val body = when (cfg.provider.protocol) {
            Protocol.OPENAI -> JSONObject()
                .put("model", cfg.model)
                .put("temperature", 0.0)
                .put("max_tokens", 16)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
                )
            Protocol.ANTHROPIC -> JSONObject()
                .put("model", cfg.model)
                .put("max_tokens", 16)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("content", "ping")
                    )
                )
            Protocol.GEMINI -> JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", "ping")))
                    )
                )
        }

        val r = once(body)
        val ms = System.currentTimeMillis() - started
        return if (r.error == null) {
            val echo = (r.text ?: "").trim().take(30).replace("\n", " ")
            "连通正常 · ${ms}ms" + if (echo.isNotEmpty()) " · 回：$echo" else ""
        } else {
            // 原始返回一并展示：文案映射只是辅助，用户要看得到服务商到底说了什么，
            // 免得再出现「明明有钱却被告知去充值」这种误导。
            "连接失败 · " + friendly(r.error!!) + " · ${ms}ms\n\n原始返回：\n" + r.error!!.take(500)
        }
    }

    companion object {

        // ==================== 连接复用 ====================

        /** 全进程共用一条连接池：相邻两步通常只隔几秒，TCP+TLS 只握手一次 */
        private val pool = okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES)

        private val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(240, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                // 复用连接后，偶发的失效连接（切网 / 切后台被系统掐断）由它兜底重试
                .retryOnConnectionFailure(true)
                .connectionPool(pool)
                .build()
        }

        /** 直连版：和共享客户端**共用**同一个连接池（newBuilder 会继承），只是不继承系统代理 */
        private val directHttp: OkHttpClient by lazy {
            sharedHttp.newBuilder().proxy(java.net.Proxy.NO_PROXY).build()
        }

        /**
         * 清一次连接池。**在每轮任务开始时调**，不是每步调 ——
         * 上一轮可能已经过去很久（切后台、切网），池里那几条多半死了，
         * 留着只会让新一轮的第一步白等一次超时。清掉之后本轮内照样复用。
         */
        fun evictConnections() {
            runCatching { pool.evictAll() }
        }

        // ==================== 流式能力记忆 ====================

        /**
         * 已确认「不认 SSE 流式」的厂商（键 = 厂商 id + Base URL）。
         *
         * 以前每步都先发一次 stream=true，等对方回不了 SSE，再**把整个请求体去掉 stream 重发一遍** ——
         * 代码注释自己也写着「只多花一次请求」。也就是说这类厂商每步的请求量直接翻倍，
         * token 和时间都翻倍。第一次试出来之后记下来，后面这一步就不试了。
         */
        private val streamOff = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun streamKnownOff(key: String): Boolean = streamOff.contains(key)

        fun markStreamOff(key: String) {
            streamOff.add(key)
        }

        /**
         * 实时进度回调：kind = "think" / "text"，text = 目前已收到的完整文本（覆盖式，不是增量）。
         * UI 侧直接整段 setText 即可，不用自己拼。
         */
        @Volatile
        var onProgress: ((String, String) -> Unit)? = null

        /** 边收边回显：读响应体的同时把已收到的部分解析出「思考 / 正文」，节流丢给界面 */
        fun readWithProgress(b: okhttp3.ResponseBody?): String {
            if (b == null) return ""
            val sb = StringBuilder()
            val ins = java.io.InputStreamReader(b.byteStream(), Charsets.UTF_8)
            val buf = CharArray(4096)
            var lastPaint = 0L
            try {
                while (true) {
                    val n = ins.read(buf, 0, buf.size)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                    val now = System.currentTimeMillis()
                    if (now - lastPaint >= 150) {
                        lastPaint = now
                        emitProgress(sb.toString())
                    }
                }
            } catch (t: Throwable) {
                // 被 cancel / 网络断：把已收到的先吐出去，再照原样抛给上层按错误处理
                runCatching { emitProgress(sb.toString()) }
                throw t
            }
            runCatching { emitProgress(sb.toString()) }
            return sb.toString()
        }

        /** 流式（SSE）节流时间戳 */
        @Volatile
        private var lastDeltaAt = 0L

        /** 流式增量直接推给界面（节流 120ms） */
        private fun emitDelta(kind: String, text: String) {
            val cb = onProgress ?: return
            if (text.isEmpty()) return
            val now = System.currentTimeMillis()
            if (now - lastDeltaAt < 120) return
            lastDeltaAt = now
            runCatching { cb(kind, text) }
        }

        /**
         * 读 SSE（stream:true）流：把正在生成的正文/思考实时推给界面，
         * 同时把增量拼成一份标准响应体，交回 parse() 复用解析。
         * 返回 null = 服务商没按 SSE 回 —— 调用方退回非流式整段读。
         */
        private fun readSse(b: okhttp3.ResponseBody?): String? {
            if (b == null) return null
            val src = b.source()
            val text = StringBuilder()
            val think = StringBuilder()
            val tools = LinkedHashMap<Int, JSONObject>()
            var sawData = false
            var usable = false
            // 流式响应里 usage 只出现在**最后一个 chunk**，而那个 chunk 的 choices 是空数组 ——
            // 必须在下面 `?: continue` 之前接住，否则永远读不到（token 统计一直是 0 的原因之一）
            var usage: JSONObject? = null
            while (true) {
                val line = runCatching { src.readUtf8Line() }.getOrNull() ?: break
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") {
                    if (payload == "[DONE]") break else continue
                }
                val j = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                sawData = true
                // 收 usage：它所在的那个 chunk 没有 choices，下面那句就会 continue 掉，
                // 所以必须先在这里接住
                j.optJSONObject("usage")?.let { u -> if (u.length() > 0) usage = u }
                val d = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val t0 = strOf(d, "reasoning_content").ifBlank { strOf(d, "reasoning") }
                    .ifBlank { strOf(d, "thinking") }
                if (t0.isNotEmpty()) {
                    usable = true
                    think.append(t0)
                    emitDelta("think", think.toString())
                }
                val c0 = strOf(d, "content")
                if (c0.isNotEmpty()) {
                    usable = true
                    text.append(c0)
                    emitDelta("text", text.toString())
                }
                d.optJSONArray("tool_calls")?.let { arr ->
                    for (k in 0 until arr.length()) {
                        val tc = arr.optJSONObject(k) ?: continue
                        val idx = tc.optInt("index", tools.size)
                        val cur = tools.getOrPut(idx) {
                            JSONObject().put("id", "").put("type", "function")
                                .put("function", JSONObject().put("name", "").put("arguments", ""))
                        }
                        val id0 = strOf(tc, "id")
                        if (id0.isNotEmpty()) cur.put("id", id0)
                        val f = tc.optJSONObject("function") ?: continue
                        val fn = cur.getJSONObject("function")
                        val nm = strOf(f, "name")
                        if (nm.isNotEmpty()) fn.put("name", nm)
                        val ar = strOf(f, "arguments")
                        if (ar.isNotEmpty()) fn.put("arguments", strOf(fn, "arguments") + ar)
                    }
                }
            }
            if (!sawData) return null
            if (!usable && tools.isEmpty()) return null
            val msg = JSONObject().put("role", "assistant").put("content", text.toString())
            if (think.isNotEmpty()) msg.put("reasoning_content", think.toString())
            if (tools.isNotEmpty()) {
                val arr = JSONArray()
                tools.toSortedMap().forEach { (_, v) ->
                    v.optJSONObject("function")?.let { f ->
                        if (strOf(f, "name").isEmpty()) f.put("name", "unknown")
                    }
                    if (strOf(v, "id").isEmpty()) v.put("id", "call_" + System.nanoTime())
                    arr.put(v)
                }
                msg.put("tool_calls", arr)
            }
            android.util.Log.i(
                "hexoraAi",
                "sse ok text=" + text.length + " think=" + think.length + " tools=" + tools.size
            )
            val out = JSONObject().put(
                "choices",
                JSONArray().put(
                    JSONObject().put("index", 0).put("message", msg).put("finish_reason", "stop")
                )
            )
            // 把流里收到的 usage 原样带给 parseOpenAi —— 不带的话 TokenStats 永远记 0，
            // 用户看到的「token 统计是个假功能」就是这儿断的
            usage?.let { out.put("usage", it) }
            return out.toString()
        }

        private fun emitProgress(full: String) {
            val cb = onProgress ?: return
            if (full.isEmpty()) return
            val think = jsonField(full, "reasoning_content")
                ?: jsonField(full, "reasoning")
                ?: jsonField(full, "thinking")
            val text = jsonField(full, "content") ?: jsonField(full, "text")
            if (!think.isNullOrEmpty()) runCatching { cb("think", think) }
            if (!text.isNullOrEmpty()) runCatching { cb("text", text) }
        }

        /**
         * 从一个「可能还没收完」的 JSON 里取字符串字段。
         * 没闭合就返回已收到的部分 —— 这正是打字机效果需要的。
         */
        /**
         * org.json 的坑：字段值是 JSON null 时，optString 会吐出字面量字符串 "null"。
         * DeepSeek 流式里「只出推理、还没开始写正文」的 chunk 就是 content:null，
         * 用 optString 接会把几百个 "null" 拼进正文（界面上就是一大坨 null）。
         */
        private fun strOf(o: JSONObject?, k: String): String {
            if (o == null) return ""
            val v = o.opt(k) ?: return ""
            if (v === JSONObject.NULL) return ""
            return v.toString()
        }

        private fun jsonField(json: String, key: String): String? {
            val k = "\"" + key + "\""
            var i = json.indexOf(k)
            while (i >= 0) {
                var j = i + k.length
                while (j < json.length && json[j].isWhitespace()) j++
                if (j >= json.length) return null
                if (json[j] == ':') {
                    j++
                    while (j < json.length && json[j].isWhitespace()) j++
                    if (j >= json.length) return null
                    if (json[j] != '"') return null      // 数组 / 对象形式，交给整段解析
                    val sb = StringBuilder()
                    j++
                    while (j < json.length) {
                        val c = json[j]
                        if (c == '\\') {
                            if (j + 1 >= json.length) break
                            val e = json[j + 1]
                            when (e) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                'r' -> sb.append('\r')
                                '"' -> sb.append('"')
                                '\\' -> sb.append('\\')
                                '/' -> sb.append('/')
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                'u' -> {
                                    if (j + 5 < json.length) {
                                        val v = json.substring(j + 2, j + 6).toIntOrNull(16)
                                        if (v != null) sb.append(v.toChar()) else sb.append(e)
                                        j += 4
                                    } else break
                                }
                                else -> sb.append(e)
                            }
                            j += 2
                        } else if (c == '"') {
                            return sb.toString()
                        } else {
                            sb.append(c)
                            j++
                        }
                    }
                    return sb.toString()   // 还没收完：先给已收到的部分
                }
                i = json.indexOf(k, i + k.length)
            }
            return null
        }

        private val NET_HINTS = listOf(
            "socket", "connection abort", "connection reset", "broken pipe",
            "timeout", "timed out", "unknownhost", "unable to resolve host",
            "stream reset", "unexpected end of stream", "eof", "closed",
            "软件导致连接中止", "failed to connect", "network is unreachable"
        )

        /** 网络类错误才重试 */
        fun isTransient(err: String): Boolean {
            val e = err.lowercase()
            if (NET_HINTS.any { e.contains(it) }) return true
            val m = Regex("HTTP (\\d{3})").find(err) ?: return false
            val code = m.groupValues[1].toIntOrNull() ?: return false
            return code >= 500 || code == 408 || code == 429
        }

        /** 把英文堆栈翻译成人话，别让用户在黑底上看一段 SocketException */
        fun friendly(err: String): String {
            val e = err.lowercase()
            return when {
                e.contains("connection abort") || e.contains("broken pipe") ->
                    "网络连接被系统掐断（多为切后台或切网导致）\n→ 已自动重试仍失败，直接再点一次发送即可。"

                e.contains("connection reset") ->
                    "连接被对端重置，通常是网络抖动或代理不稳定。\n→ 稍等两秒再发一次。"

                e.contains("unknownhost") || e.contains("unable to resolve") ||
                    e.contains("network is unreachable") ->
                    "连不上服务器：域名解析失败。\n→ 检查网络，或核对设置里的 Base URL。"

                e.contains("timeout") || e.contains("timed out") ->
                    "请求超时：模型没在 240 秒内返回。\n→ 换更快的模型，或把问题拆小一点。"

                e.contains("http 401") || e.contains("unauthorized") ->
                    "API Key 不对或没有该模型的权限（401）。\n→ 到设置里重新粘贴 Key，并点「测试连通」。"

                // 注意：绝对不能用裸的 contains("insufficient") 当作「余额不足」判据。
                // insufficient_quota（配额/限速）、insufficient_user_quota（Key 额度上限）、
                // insufficient permission（权限不足）都不是「没钱」，而且老代码这条排在 403 之前，
                // 会把「403 权限不足」误报成「账户余额不足，去充值」——用户明明有钱却一直被叫充值。
                // 所以先精确区分配额 / 权限，再判真正的余额不足，并把服务商原文附上。
                e.contains("insufficient_quota") || e.contains("insufficient quota") ||
                    e.contains("insufficient_user_quota") ->
                    "额度/配额用尽（不是余额）：这把 Key 触发了限速或额度上限。\n" +
                        "→ 等一会儿再试；或到服务商后台看「配额/用量」。\n原文：" + err.take(240)

                e.contains("insufficient_permission") || e.contains("insufficient permission") ->
                    "权限不足：这把 Key 不能用于该模型或该接口。\n" +
                        "→ 换一个模型，或换一把有权限的 Key。\n原文：" + err.take(240)

                e.contains("http 402") || e.contains("insufficient balance") ||
                    e.contains("insufficient_balance") ->
                    "服务商判定账户余额不足（402）。\n" +
                        "→ 若你确认账户里有钱：多半是这把 Key 属于**另一个账号**，或走的是第三方中转。\n" +
                        "→ 设置里点「测试连通」核对；确认 Base URL 与服务商一致。\n原文：" + err.take(240)

                e.contains("http 403") ->
                    "被拒绝（403）：Key 权限不足，或该地区/该模型不可用。"

                e.contains("http 404") ->
                    "找不到接口或模型（404）。\n→ 多半是 Base URL 或模型名写错了，用「测试连通」验证。"

                e.contains("tool_calls") && e.contains("must be followed") ->
                    "本地对话里的「工具调用记录」不完整（某个工具调用的返回丢了），服务商拒绝接收。\n" +
                        "→ 多为历史被裁剪导致，App 已自动修配对；若还出现，去设置里把「历史条数上限」调大。\n" +
                        "原文：" + err.take(300)

                e.contains("http 400") ->
                    "请求被拒（400：参数或格式问题）。\n原文：" + err.take(300)

                e.contains("http 413") || e.contains("http413") ||
                    e.contains("request too large") || e.contains("tokens per minute") ->
                    "这条请求超过了该模型能收的大小（413 / TPM 限流 / 上下文超长）。\n" +
                        "→ App 已自动精简并重试（先去工具定义，再砍历史，最后压小输出）。\n" +
                        "→ 想彻底避免：设置里把「输出上限」「历史条数」调小，或关掉「发送工具定义」" +
                        "（50+ 个工具的清单本身就是几万 token，关掉最省）。\n原文：" + err.take(300)

                e.contains("http 429") -> {
                    // 429 有两层意思：「太频繁」和「这个账号没额度了」。
                    // 智谱/部分中转在余额为 0 时也用 429 表达（code 1113「账户已欠费」），
                    // 一律翻译成「请求太频繁」会让人完全找不到北。
                    val quota = e.contains("欠费") || e.contains("余额") || e.contains("充值") ||
                        e.contains("insufficient") || e.contains("balance") || e.contains("1113")
                    if (quota)
                        "额度已用尽（429，服务商原文见下）。\n" +
                            "→ 到服务商后台看「余额 / 用量 / 免费额度是否过期」。\n原文：" + err.take(240)
                    else
                        "请求太频繁或超出限流（429）。\n→ 等一会儿再试，或换一家模型。\n原文：" + err.take(240)
                }

                e.matches(Regex(".*http 5\\d\\d.*")) ->
                    "服务端故障（5xx），不是你的问题。\n→ 换一家或过会儿再试。"

                e.contains("unsupported") || e.contains("does not support") ->
                    "该模型不支持当前请求形态（比如不吃图片/工具调用）。\n→ 换成带「看图」标记的模型。"

                else -> err.take(500)
            }
        }
    }

    // ======================= 请求体 =======================

    private fun dataUrl(img: ByteArray): String =
        "data:image/jpeg;base64," + Base64.encodeToString(img, Base64.NO_WRAP)

    private fun b64(img: ByteArray): String =
        Base64.encodeToString(img, Base64.NO_WRAP)

    private fun contentOrParts(text: String?, images: List<ByteArray>): Any {
        // 0 字节的图片绝不能进请求体：截图失败时（画面没加载完）会产出空 Bitmap，
        // 服务商一律回 400 invalid_request_error（"unsupported image"），白烧一次请求，
        // 而且这类错误文案跟「余额/权限」很像，最容易把人带偏。
        val imgs = images.filter { it.isNotEmpty() }
        if (imgs.isEmpty()) return text ?: ""
        val parts = JSONArray()
        if (!text.isNullOrBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", text))
        }
        for (img in imgs) {
            parts.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl(img)))
            )
        }
        return parts
    }

    /**
     * 发出去之前的最后一道保险：任何 assistant(tool_calls) 都必须被**紧随其后**的 tool 消息
     * 逐条回应，否则就地拆掉 tool_calls；反过来，找不到对应 tool_calls 的孤儿 tool 消息
     * 降级成普通文本。
     *
     * 为什么要在这一层再做一次：上游历史可能被裁剪/被异常中断（工具没返回结果就存了记录），
     * 服务商对这种请求只会回一句 400「must be followed by tool messages」，
     * 用户看到的就是「请求失败」——宁可丢一点上下文，也不能让整条请求发不出去。
     */
    private fun fixToolPairing(msgs: JSONArray): JSONArray {
        // 第一遍：拆掉「响应不完整」的 tool_calls
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            if (strOf(m, "role") != "assistant") continue
            val tcs = m.optJSONArray("tool_calls") ?: continue
            if (tcs.length() == 0) continue
            val need = HashSet<String>()
            for (k in 0 until tcs.length()) {
                val id = tcs.optJSONObject(k)?.optString("id") ?: ""
                if (id.isNotEmpty()) need.add(id)
            }
            var j = i + 1
            while (j < msgs.length()) {
                val nm = msgs.optJSONObject(j) ?: break
                if (strOf(nm, "role") != "tool") break
                val tid = strOf(nm, "tool_call_id")
                if (tid.isNotEmpty()) need.remove(tid)
                j++
            }
            if (need.isNotEmpty()) m.remove("tool_calls")
        }
        // 第二遍：把补齐后仍然「无主」的 tool 消息降级成用户文本
        val known = HashSet<String>()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val tcs = m.optJSONArray("tool_calls") ?: continue
            for (k in 0 until tcs.length()) {
                val id = tcs.optJSONObject(k)?.optString("id") ?: ""
                if (id.isNotEmpty()) known.add(id)
            }
        }
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            if (strOf(m, "role") != "tool") continue
            if (strOf(m, "tool_call_id") in known) continue
            m.put("role", "user")
            m.remove("tool_call_id")
            val c = strOf(m, "content")
            m.put("content", "（历史工具结果，对应的调用记录已丢失）\n" + c)
        }
        // 最后一道保险：拆完 tool_calls 之后变成空壳的 assistant，绝不能再原样发出去。
        // DeepSeek 会直接 400：Invalid assistant message: content or tool_calls must be set。
        // （长会话中途工具记录不完整时必现，用户已经踩到过。）
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            if (strOf(m, "role") != "assistant") continue
            val hasTc = (m.optJSONArray("tool_calls")?.length() ?: 0) > 0
            val c = m.opt("content")
            val blank = c == null || c === JSONObject.NULL ||
                c.toString().replace("null", "").isBlank()
            if (!hasTc && blank) {
                // 兜底文案必须**可执行**。
                // 原来写的是「（上一步的工具记录不完整，这里省略）」—— 模型读到这句话
                // 只知道自己缺了东西，但不知道该怎么办，通常就停在这儿不动了
                // （用户报的「老是中断任务、一两句就停」）。
                // 现在直接告诉它：缺了就重新调一次，别停。
                m.put(
                    "content",
                    "（上一步的工具调用没有拿到完整结果，可能是被中断了。" +
                        "如果你还需要那份信息，请重新调用一次对应工具；" +
                        "不要因为这段缺失就停止推进任务。）"
                )
            }
        }
        return msgs
    }

    private fun bodyOpenAi(history: List<ChatMsg>, tools: List<JSONObject>): JSONObject {
        val msgs = JSONArray()
        for (m in history) {
            // 严格服务商（DeepSeek / 火山 / 智谱等）会校验：assistant 消息必须带 content 或 tool_calls。
            // **关键**：历史里躺着旧版本 optString 的锅 —— 字面量 "null"。
            // 它既不是 null 也不是空白，只判 isNullOrBlank 会漏过去，照样 400。
            // 所以先抹掉 "null" 再判断，空壳一律不发。
            val t = (m.text ?: "").replace("null", "").trim()
            val hasTools = m.toolCalls.isNotEmpty()
            val hasImg = !m.images.isNullOrEmpty()
            if (m.role == "assistant" && t.isEmpty() && !hasTools && !hasImg) continue
            val o = JSONObject().put("role", m.role)
            if (m.role == "tool") {
                o.put("tool_call_id", m.toolCallId ?: "")
                // 旧版本的坑：历史里可能已经存进了字面量 "null"，
                // 这里统一抹掉，免得模型读到一坨 null 之后自己也糊了。
                o.put("content", contentOrParts(t, m.images))
            } else if (m.toolCalls.isNotEmpty()) {
                o.put("content", if (t.isEmpty()) JSONObject.NULL else t)
                val tcs = JSONArray()
                for (t in m.toolCalls) {
                    tcs.put(
                        JSONObject().put("id", t.id).put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", t.name).put("arguments", t.argsJson)
                            )
                    )
                }
                o.put("tool_calls", tcs)
            } else {
                // 旧版本的坑：历史里可能已经存进了字面量 "null"，
                // 这里统一抹掉，免得模型读到一坨 null 之后自己也糊了。
                o.put("content", contentOrParts(t, m.images))
            }
            msgs.put(o)
        }

        val out = JSONObject()
            .put("model", cfg.model)
            .put("messages", fixToolPairing(msgs))
            .put("temperature", cfg.temperature)

        // 输出上限：设置里调（0 = 不发送，交服务商决定）。
        // 注意很多厂商会按 max_tokens 预扣额度，调小它同时能救「余额不足」与「TPM 超限」。
        if (cfg.maxOutTokens > 0) out.put("max_tokens", cfg.maxOutTokens)

        if (tools.isNotEmpty()) {
            out.put("tools", JSONArray(tools.toTypedArray()))
            out.put("tool_choice", "auto")
        }
        return out
    }

    private fun bodyAnthropic(history: List<ChatMsg>, tools: List<JSONObject>): JSONObject {
        val system = history.filter { it.role == "system" }
            .joinToString("\n") { it.text ?: "" }

        val msgs = JSONArray()
        for (m in history) {
            if (m.role == "system") continue
            val role = if (m.role == "assistant") "assistant" else "user"
            val blocks = anthBlocks(m)

            val last = if (msgs.length() > 0) msgs.getJSONObject(msgs.length() - 1) else null
            if (last != null && last.getString("role") == role) {
                val c = last.getJSONArray("content")
                for (i in 0 until blocks.length()) c.put(blocks.get(i))
            } else {
                msgs.put(JSONObject().put("role", role).put("content", blocks))
            }
        }

        val out = JSONObject()
            .put("model", cfg.model)
            .put("messages", msgs)
            // 输出上限：设置里可调；默认 4096（原来硬编码 8192，免费档/小配额模型容易被判超额）
            .put("max_tokens", if (cfg.maxOutTokens > 0) cfg.maxOutTokens else 4096)
            .put("temperature", cfg.temperature)

        // 提示缓存：系统提示 + 工具清单是一轮任务里**逐字不变**的前缀，但每一步都要重发。
        // 打上 cache_control 之后，第 2 步起这段按「缓存命中」计费（便宜一个数量级），
        // 首字延迟也明显更短。断点最多 4 个，这里用 2 个：系统提示 + 工具清单最后一个
        // （断点打在某个元素上表示「到这里为止都缓存」，所以打在最后那个工具上就把整块工具都盖住了）。
        if (system.isNotBlank()) {
            if (cacheOk) {
                // 官方的 system 字段既接受纯字符串，也接受「内容块数组」——
                // 要打 cache_control 就必须用数组形式。
                out.put(
                    "system",
                    JSONArray().put(
                        JSONObject().put("type", "text").put("text", system)
                            .put("cache_control", JSONObject().put("type", "ephemeral"))
                    )
                )
            } else {
                out.put("system", system)
            }
        }

        if (tools.isNotEmpty()) {
            val ts = JSONArray()
            for ((i, t) in tools.withIndex()) {
                val f = t.getJSONObject("function")
                val o = JSONObject().put("name", f.getString("name"))
                    .put("description", strOf(f, "description"))
                    .put("input_schema", f.getJSONObject("parameters"))
                if (cacheOk && i == tools.lastIndex) {
                    o.put("cache_control", JSONObject().put("type", "ephemeral"))
                }
                ts.put(o)
            }
            out.put("tools", ts)
        }
        return out
    }

    /**
     * 能不能给请求打 Anthropic 的缓存标记。
     *
     * 只认官方端点：中转站 / 自建代理对 `cache_control` 的支持参差不齐，
     * 打上去万一被拒，用户看到的是「本来好好的突然报错」——
     * 省下的钱远不够赔这个体验，所以拿不准就不打。
     */
    private val cacheOk: Boolean
        get() = cfg.provider.protocol == Protocol.ANTHROPIC &&
            cfg.baseUrl.contains("api.anthropic.com")

    private fun anthBlocks(m: ChatMsg): JSONArray {
        val parts = JSONArray()

        if (m.role == "tool") {
            val inner = JSONArray()
            m.text?.let { inner.put(JSONObject().put("type", "text").put("text", it)) }
            for (img in m.images) {
                if (img.isEmpty()) continue   // 空图片（截图失败的产物）不进请求体
                inner.put(
                    JSONObject().put("type", "image")
                        .put(
                            "source",
                            JSONObject().put("type", "base64")
                                .put("media_type", "image/jpeg").put("data", b64(img))
                        )
                )
            }
            parts.put(
                JSONObject().put("type", "tool_result")
                    .put("tool_use_id", m.toolCallId ?: "").put("content", inner)
            )
            return parts
        }

        if (!m.text.isNullOrBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", m.text))
        }
        for (t in m.toolCalls) {
            parts.put(
                JSONObject().put("type", "tool_use").put("id", t.id).put("name", t.name)
                    .put("input", runCatching { JSONObject(t.argsJson) }.getOrDefault(JSONObject()))
            )
        }
        for (img in m.images) {
            if (img.isEmpty()) continue   // 空图片（截图失败的产物）不进请求体
            parts.put(
                JSONObject().put("type", "image")
                    .put(
                        "source",
                        JSONObject().put("type", "base64")
                            .put("media_type", "image/jpeg").put("data", b64(img))
                    )
            )
        }
        return parts
    }

    private fun bodyGemini(history: List<ChatMsg>, tools: List<JSONObject>): JSONObject {
        val system = history.filter { it.role == "system" }
            .joinToString("\n") { it.text ?: "" }

        val contents = JSONArray()
        for (m in history) {
            if (m.role == "system") continue
            val role = if (m.role == "assistant") "model" else "user"
            val parts = JSONArray()

            if (!m.text.isNullOrBlank()) {
                parts.put(JSONObject().put("text", m.text))
            }
            for (img in m.images) {
                parts.put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject().put("mimeType", "image/jpeg").put("data", b64(img))
                    )
                )
            }
            for (t in m.toolCalls) {
                parts.put(
                    JSONObject().put(
                        "functionCall",
                        JSONObject().put("name", t.name)
                            .put("args", runCatching { JSONObject(t.argsJson) }
                                .getOrDefault(JSONObject()))
                    )
                )
            }
            if (m.role == "tool") {
                parts.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject().put("name", m.toolCallId ?: "tool")
                            .put("response", JSONObject().put("result", m.text ?: ""))
                    )
                )
            }

            val last = if (contents.length() > 0) contents.getJSONObject(contents.length() - 1) else null
            if (last != null && last.getString("role") == role) {
                val p = last.getJSONArray("parts")
                for (i in 0 until parts.length()) p.put(parts.get(i))
            } else {
                contents.put(JSONObject().put("role", role).put("parts", parts))
            }
        }

        val out = JSONObject()
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject().put("temperature", cfg.temperature)
                    .put("maxOutputTokens", if (cfg.maxOutTokens > 0) cfg.maxOutTokens else 4096)
            )

        if (system.isNotBlank()) {
            out.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
        }

        if (tools.isNotEmpty()) {
            val decls = JSONArray()
            for (t in tools) {
                val f = t.getJSONObject("function")
                decls.put(
                    JSONObject().put("name", f.getString("name"))
                        .put("description", strOf(f, "description"))
                        .put("parameters", f.getJSONObject("parameters"))
                )
            }
            out.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }
        return out
    }

    // ======================= 响应解析 =======================

    private fun parse(raw: String): ChatReply = when (cfg.provider.protocol) {
        Protocol.OPENAI -> parseOpenAi(raw)
        Protocol.ANTHROPIC -> parseAnthropic(raw)
        Protocol.GEMINI -> parseGemini(raw)
    }

    private fun errOf(j: JSONObject): String? =
        j.optJSONObject("error")?.let { it.optString("message", it.toString()) }

    private fun textOfNode(v: Any?): String? = when (v) {
        is String -> v.takeIf { it.isNotBlank() }
        is JSONArray -> buildString {
            for (i in 0 until v.length()) {
                val p = v.optJSONObject(i) ?: continue
                val t = p.optString("text", "")
                if (t.isNotBlank()) append(t)
            }
        }.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun parseOpenAi(raw: String): ChatReply {
        val j = JSONObject(raw)
        errOf(j)?.let { return ChatReply(null, emptyList(), it) }

        val choices = j.optJSONArray("choices")
            ?: return ChatReply(null, emptyList(), "响应缺少 choices: ${raw.take(400)}")
        if (choices.length() == 0) return ChatReply(null, emptyList(), "choices 为空")

        val msg = choices.getJSONObject(0).optJSONObject("message")
            ?: return ChatReply(null, emptyList(), "响应缺少 message: ${raw.take(400)}")

        val tcs = mutableListOf<ToolCall>()
        msg.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val f = item.optJSONObject("function") ?: continue
                tcs += ToolCall(
                    // 有些中转会把 id 回成空串，这时 optString 的默认值不会生效 ——
                    // 空 id 会让 tool 消息无法与 tool_calls 配对，服务商直接 400。
                    strOf(item, "id").ifBlank { UUID.randomUUID().toString() },
                    strOf(f, "name"),
                    f.optString("arguments", "{}")
                )
            }
        }
        val reason = msg.optString("reasoning_content", "")
            .ifBlank { msg.optString("reasoning", "") }
            .ifBlank { msg.optString("thinking", "") }
        val u = j.optJSONObject("usage")
        return ChatReply(
            textOfNode(msg.opt("content")),
            tcs,
            null,
            reason.takeIf { it.isNotBlank() },
            u?.optInt("prompt_tokens", 0) ?: 0,
            u?.optInt("completion_tokens", 0) ?: 0
        )
    }

    private fun parseAnthropic(raw: String): ChatReply {
        val j = JSONObject(raw)
        errOf(j)?.let { return ChatReply(null, emptyList(), it) }

        val parts = j.optJSONArray("content")
            ?: return ChatReply(null, emptyList(), "响应缺少 content: ${raw.take(400)}")

        val sb = StringBuilder()
        val tcs = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            when (strOf(p, "type")) {
                "text" -> sb.append(strOf(p, "text"))
                "tool_use" -> tcs += ToolCall(
                    p.optString("id", UUID.randomUUID().toString()),
                    strOf(p, "name"),
                    p.optJSONObject("input")?.toString() ?: "{}"
                )
            }
        }
        val au = j.optJSONObject("usage")
        return ChatReply(
            sb.toString().takeIf { it.isNotBlank() }, tcs, null, null,
            au?.optInt("input_tokens", 0) ?: 0,
            au?.optInt("output_tokens", 0) ?: 0
        )
    }

    private fun parseGemini(raw: String): ChatReply {
        val j = JSONObject(raw)
        errOf(j)?.let { return ChatReply(null, emptyList(), it) }

        val cands = j.optJSONArray("candidates")
            ?: return ChatReply(null, emptyList(), "响应缺少 candidates: ${raw.take(400)}")
        if (cands.length() == 0) return ChatReply(null, emptyList(), "candidates 为空")

        val parts = cands.getJSONObject(0).optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return ChatReply(null, emptyList(), "响应缺少 parts: ${raw.take(400)}")

        val sb = StringBuilder()
        val tcs = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val t = p.optString("text", "")
            if (t.isNotBlank()) sb.append(t)
            p.optJSONObject("functionCall")?.let {
                val name = strOf(it, "name")
                tcs += ToolCall(name, name, it.optJSONObject("args")?.toString() ?: "{}")
            }
        }
        val gu = j.optJSONObject("usageMetadata")
        return ChatReply(
            sb.toString().takeIf { it.isNotBlank() }, tcs, null, null,
            gu?.optInt("promptTokenCount", 0) ?: 0,
            gu?.optInt("candidatesTokenCount", 0) ?: 0
        )
    }
}