package com.mcp.h5engine

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 极简 MCP (Model Context Protocol) 客户端
 *
 * 依赖只有 OkHttp + org.json，不引官方 SDK，方便魔改。
 * 传输：Streamable HTTP（POST JSON-RPC 2.0，响应体可能是 application/json 或 text/event-stream）
 *
 * 手写要点：
 *  - initialize 之后必须发 notifications/initialized
 *  - 服务端可能用响应头 Mcp-Session-Id 发会话，后续请求要带上
 *  - 后续请求要带 MCP-Protocol-Version
 *  - Accept 必须同时声明 application/json 和 text/event-stream，否则部分服务端直接 406
 */
class McpClient(
    private val serverUrl: String,
    private val extraHeaders: Map<String, String> = emptyMap()
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)   // SSE 可能一直挂着
        .callTimeout(300, TimeUnit.SECONDS)   // 工具调用可能很慢（等 AI 出结果）
        .build()

    private val idGen = AtomicLong(1L)

    @Volatile
    private var sessionId: String? = null

    var negotiatedProtocol: String? = null
        private set

    var serverInfo: JSONObject? = null
        private set

    @Volatile
    var initialized = false
        private set

    // ---------------- 对外 API ----------------

    fun initialize(clientName: String = "h5-mcp-engine", clientVersion: String = "1.0.0"): JSONObject {
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", clientName).put("version", clientVersion))

        val res = rpc("initialize", params)
        negotiatedProtocol = res.optString("protocolVersion", PROTOCOL_VERSION)
        serverInfo = res.optJSONObject("serverInfo")

        // 协议要求：初始化完成后必须补一条通知，否则大多数服务端拒绝后续请求
        notify("notifications/initialized", JSONObject())
        initialized = true
        return res
    }

    fun listTools(): JSONObject = rpc("tools/list", JSONObject())

    fun callTool(name: String, args: JSONObject): JSONObject =
        rpc("tools/call", JSONObject().put("name", name).put("arguments", args))

    fun listResources(): JSONObject = rpc("resources/list", JSONObject())

    fun readResource(uri: String): JSONObject =
        rpc("resources/read", JSONObject().put("uri", uri))

    fun listPrompts(): JSONObject = rpc("prompts/list", JSONObject())

    // ---------------- 内部实现 ----------------

    private fun rpc(method: String, params: JSONObject): JSONObject {
        val id = idGen.getAndIncrement()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()

        http.newCall(buildRequest(body)).execute().use { resp ->
            resp.header("Mcp-Session-Id")?.let { sessionId = it }

            val contentType = resp.header("Content-Type").orEmpty()
            val text = resp.body?.string().orEmpty()

            if (!resp.isSuccessful) {
                throw McpException(resp.code, "HTTP ${resp.code}: ${text.take(300)}")
            }

            val payload =
                if (contentType.contains("text/event-stream")) parseSse(text, id)
                else JSONObject(text)

            payload.optJSONObject("error")?.let {
                throw McpException(it.optInt("code"), it.optString("message"))
            }
            return payload.optJSONObject("result") ?: JSONObject()
        }
    }

    private fun notify(method: String, params: JSONObject) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .toString()
        runCatching { http.newCall(buildRequest(body)).execute().close() }
    }

    private fun buildRequest(json: String): Request {
        val b = Request.Builder()
            .url(serverUrl)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .post(json.toRequestBody(JSON))

        sessionId?.let { b.header("Mcp-Session-Id", it) }
        negotiatedProtocol?.takeIf { it.isNotEmpty() }?.let { b.header("MCP-Protocol-Version", it) }
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    /** 从 SSE 文本里挑出 id 匹配的那条 JSON-RPC 响应 */
    private fun parseSse(text: String, id: Long): JSONObject {
        var fallback: JSONObject? = null
        for (line in text.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
            if (obj.optLong("id", -1L) == id) return obj
            if (fallback == null) fallback = obj
        }
        return fallback ?: throw McpException(-1, "SSE 流中没有匹配的 JSON-RPC 响应")
    }

    companion object {
        /** 想兼容旧服务端可以改成 "2024-11-05" */
        const val PROTOCOL_VERSION = "2025-06-18"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class McpException(val code: Int, message: String) : Exception(message)