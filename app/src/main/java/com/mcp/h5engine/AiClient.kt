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
    val reasoning: String? = null
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

    /** 每次请求都新建客户端：避免切后台回来复用了已经死掉的连接池（Software caused connection abort） */
    private fun newHttp(direct: Boolean = false): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        // direct=true 时强制直连：忽略系统代理/VPN 设的 HTTP 代理。
        // 这类代理层回的错误（402 / 403 / 各种网关页）经常和「余额不足」长得一模一样。
        if (direct) runCatching { b.proxy(java.net.Proxy.NO_PROXY) }
        return b.build()
    }

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
        // 注意顺序：先按条数裁，再清洗配对。
        // 反过来的话，裁剪会把某个 assistant(tool_calls) 的响应截在窗口外，
        // 服务商直接回 400「An assistant message with 'tool_calls' must be followed by tool messages」。
        val history = sanitizeHistory(limitHistory(rawHistory))
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
            if (err == null) return r          // 成功
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
            if (first != null && first.optString("role") == "system") arr.put(first)
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
        val rb = Request.Builder().url(endpoint)
            .header("Connection", "close")
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
                // 边收边回显：把正在流回来的「思考 / 正文」实时交给界面。
                // 思考版模型长时间推理时可能几十秒不吐正文，没有这个回调，
                // 用户看到的只有一句「思考中…」，只能以为卡死。
                val text = if (r.isSuccessful) readWithProgress(r.body)
                else (r.body?.string() ?: "")
                if (!r.isSuccessful) {
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

        private fun emitProgress(full: String) {
            val cb = onProgress ?: return
            if (full.isEmpty()) return
            val think = jsonField(full, "reasoning_content") ?: jsonField(full, "reasoning")
            val text = jsonField(full, "content") ?: jsonField(full, "text")
            if (!think.isNullOrEmpty()) runCatching { cb("think", think) }
            if (!text.isNullOrEmpty()) runCatching { cb("text", text) }
        }

        /**
         * 从一个「可能还没收完」的 JSON 里取字符串字段。
         * 没闭合就返回已收到的部分 —— 这正是打字机效果需要的。
         */
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
            if (m.optString("role") != "assistant") continue
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
                if (nm.optString("role") != "tool") break
                val tid = nm.optString("tool_call_id")
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
            if (m.optString("role") != "tool") continue
            if (m.optString("tool_call_id") in known) continue
            m.put("role", "user")
            m.remove("tool_call_id")
            val c = m.optString("content")
            m.put("content", "（历史工具结果，对应的调用记录已丢失）\n" + c)
        }
        return msgs
    }

    private fun bodyOpenAi(history: List<ChatMsg>, tools: List<JSONObject>): JSONObject {
        val msgs = JSONArray()
        for (m in history) {
            val o = JSONObject().put("role", m.role)
            if (m.role == "tool") {
                o.put("tool_call_id", m.toolCallId ?: "")
                o.put("content", contentOrParts(m.text, m.images))
            } else if (m.toolCalls.isNotEmpty()) {
                o.put("content", m.text ?: JSONObject.NULL)
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
                o.put("content", contentOrParts(m.text, m.images))
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

        if (system.isNotBlank()) out.put("system", system)

        if (tools.isNotEmpty()) {
            val ts = JSONArray()
            for (t in tools) {
                val f = t.getJSONObject("function")
                ts.put(
                    JSONObject().put("name", f.getString("name"))
                        .put("description", f.optString("description"))
                        .put("input_schema", f.getJSONObject("parameters"))
                )
            }
            out.put("tools", ts)
        }
        return out
    }

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
                        .put("description", f.optString("description"))
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
                    item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    f.optString("name"),
                    f.optString("arguments", "{}")
                )
            }
        }
        val reason = msg.optString("reasoning_content", "")
            .ifBlank { msg.optString("reasoning", "") }
        return ChatReply(
            textOfNode(msg.opt("content")),
            tcs,
            null,
            reason.takeIf { it.isNotBlank() }
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
            when (p.optString("type")) {
                "text" -> sb.append(p.optString("text"))
                "tool_use" -> tcs += ToolCall(
                    p.optString("id", UUID.randomUUID().toString()),
                    p.optString("name"),
                    p.optJSONObject("input")?.toString() ?: "{}"
                )
            }
        }
        return ChatReply(sb.toString().takeIf { it.isNotBlank() }, tcs)
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
                val name = it.optString("name")
                tcs += ToolCall(name, name, it.optJSONObject("args")?.toString() ?: "{}")
            }
        }
        return ChatReply(sb.toString().takeIf { it.isNotBlank() }, tcs)
    }
}