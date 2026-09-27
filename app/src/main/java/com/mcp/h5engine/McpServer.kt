package com.mcp.h5engine

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 内嵌 MCP Server（Streamable HTTP）
 *
 * 让 Operit / Claude / Cursor 这类支持 MCP 的 AI 直接连到本机，
 * 通过 tools/call 操控这个 App 做 H5 游戏。
 *
 * 端点：
 *   POST /mcp    JSON-RPC 2.0
 *   GET  /mcp    健康检查（返回 server 信息）
 *
 * 协议要点：
 *   - initialize 返回 protocolVersion / capabilities / serverInfo
 *   - notifications/* 无 id，直接 202
 *   - 响应头带 Mcp-Session-Id
 *   - 同时开 CORS，方便网页端 AI 直连
 */
class McpServer(
    port: Int,
    private val tools: GameTools
) : NanoHTTPD(port) {

    private val sessionId = UUID.randomUUID().toString()
    private val startedAt = System.currentTimeMillis()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"

        val resp: Response = when {
            session.method == Method.OPTIONS ->
                newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")

            !uri.startsWith("/mcp") && uri != "/" ->
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT,
                    "MCP H5 Engine\nPOST /mcp  (JSON-RPC 2.0)\nGET  /mcp  (status)\n"
                )

            session.method == Method.GET -> newFixedLengthResponse(
                Response.Status.OK, "application/json",
                JSONObject()
                    .put("status", "ok")
                    .put("name", SERVER_NAME)
                    .put("version", SERVER_VERSION)
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("uptimeMs", System.currentTimeMillis() - startedAt)
                    .put("tools", tools.listSpec().length())
                    .toString()
            )

            session.method == Method.POST -> handleRpc(session)

            else -> newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "use POST /mcp"
            )
        }

        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Headers", "*")
        resp.addHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        resp.addHeader("Access-Control-Expose-Headers", "Mcp-Session-Id")
        return resp
    }

    private fun handleRpc(session: IHTTPSession): Response {
        val body = try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            map["postData"] ?: ""
        } catch (t: Throwable) {
            ""
        }

        val msg = runCatching { JSONObject(body) }.getOrNull()
            ?: return jsonRpcError(null, -32700, "Parse error")

        val id = msg.opt("id")
        val method = msg.optString("method", "")

        // 通知（无 id）：只确认，不回复
        if (id == null || id == JSONObject.NULL || method.startsWith("notifications/")) {
            return newFixedLengthResponse(Response.Status.ACCEPTED, MIME_PLAINTEXT, "")
        }

        val result: Any = try {
            when (method) {
                "initialize" -> JSONObject()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put(
                        "capabilities",
                        JSONObject().put("tools", JSONObject().put("listChanged", false))
                    )
                    .put(
                        "serverInfo",
                        JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION)
                    )
                    .put(
                        "instructions",
                        "这是装在 Android 手机上的 H5 游戏引擎。你可以用 game_* 系列工具" +
                            "直接创建/读写游戏文件、热重载预览、执行 JS、截图看效果、模拟点击，" +
                            "从而把 H5 游戏做出来。"
                    )

                "ping" -> JSONObject()

                "tools/list" -> JSONObject().put("tools", tools.listSpec())

                "tools/call" -> tools.call(msg.optJSONObject("params") ?: JSONObject())

                "resources/list" -> JSONObject().put("resources", JSONArray())

                "prompts/list" -> JSONObject().put("prompts", JSONArray())

                else -> return jsonRpcError(id, -32601, "Method not found: $method")
            }
        } catch (t: Throwable) {
            JSONObject()
                .put(
                    "content",
                    JSONArray().put(
                        JSONObject().put("type", "text")
                            .put("text", "工具执行异常: ${t.javaClass.simpleName}: ${t.message}")
                    )
                )
                .put("isError", true)
        }

        val out = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)

        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", out.toString())
        resp.addHeader("Mcp-Session-Id", sessionId)
        return resp
    }

    private fun jsonRpcError(id: Any?, code: Int, message: String): Response {
        val err = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
        return newFixedLengthResponse(Response.Status.OK, "application/json", err.toString())
    }

    companion object {
        const val SERVER_NAME = "h5-mcp-engine"
        const val SERVER_VERSION = "1.0.0"
        const val PROTOCOL_VERSION = "2025-06-18"
        const val DEFAULT_PORT = 8765
    }
}