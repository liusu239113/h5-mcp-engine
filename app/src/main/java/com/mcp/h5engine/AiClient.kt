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
    val error: String? = null
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

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    fun chat(history: List<ChatMsg>, tools: List<JSONObject>): ChatReply {
        val base = cfg.provider.baseUrl.trimEnd('/')
        val url = when (cfg.provider.protocol) {
            Protocol.OPENAI -> "$base/chat/completions"
            Protocol.ANTHROPIC -> "$base/messages"
            Protocol.GEMINI -> "$base/models/${cfg.model}:generateContent"
        }

        val body = when (cfg.provider.protocol) {
            Protocol.OPENAI -> bodyOpenAi(history, tools)
            Protocol.ANTHROPIC -> bodyAnthropic(history, tools)
            Protocol.GEMINI -> bodyGemini(history, tools)
        }

        val rb = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))

        when (cfg.provider.protocol) {
            Protocol.OPENAI ->
                rb.header("Authorization", "Bearer ${cfg.apiKey}")
            Protocol.ANTHROPIC ->
                rb.header("x-api-key", cfg.apiKey).header("anthropic-version", "2023-06-01")
            Protocol.GEMINI ->
                rb.header("x-goog-api-key", cfg.apiKey)
        }

        return try {
            http.newCall(rb.build()).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    ChatReply(null, emptyList(), "HTTP ${r.code}  ${text.take(700)}")
                } else {
                    parse(text)
                }
            }
        } catch (t: Throwable) {
            ChatReply(null, emptyList(), "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ======================= 请求体 =======================

    private fun dataUrl(img: ByteArray): String =
        "data:image/jpeg;base64," + Base64.encodeToString(img, Base64.NO_WRAP)

    private fun b64(img: ByteArray): String =
        Base64.encodeToString(img, Base64.NO_WRAP)

    private fun contentOrParts(text: String?, images: List<ByteArray>): Any {
        if (images.isEmpty()) return text ?: ""
        val parts = JSONArray()
        if (!text.isNullOrBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", text))
        }
        for (img in images) {
            parts.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl(img)))
            )
        }
        return parts
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
            .put("messages", msgs)
            .put("temperature", cfg.temperature)

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
            .put("max_tokens", 8192)
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
                JSONObject().put("temperature", cfg.temperature).put("maxOutputTokens", 8192)
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
                    item.optString("id", UUID.randomUUID().toString()),
                    f.optString("name"),
                    f.optString("arguments", "{}")
                )
            }
        }
        return ChatReply(textOfNode(msg.opt("content")), tcs)
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