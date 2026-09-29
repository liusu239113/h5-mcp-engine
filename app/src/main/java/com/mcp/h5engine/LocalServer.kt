package com.mcp.h5engine

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.HashMap

/**
 * 本地 HTTP 服务（D2）：web-chat + a2a-server。
 *
 * 让电脑浏览器 / 别的 Agent 通过 HTTP 访问 Hexora 的对话能力：
 *   GET  /health        -> 探活（免鉴权）
 *   POST /api/chat      body {messages:[{role,content}], system?} -> {reply, inTokens, outTokens}
 *   POST /api/a2a       body {from, text}                         -> {ok, from, text, at}
 * 鉴权：除 /health 外，所有请求需带 header `X-Token`，值为 [token]。
 */
class LocalServer(
    private val ctx: Context,
    port: Int = 8787,
    val token: String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
) : NanoHTTPD(port) {

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri
        val m = session.method

        if (uri == "/health") {
            return json(NanoHTTPD.Response.Status.OK, """{"ok":true,"app":"hexora","name":"local"}""")
        }
        if (!uri.startsWith("/api/")) return json(NanoHTTPD.Response.Status.NOT_FOUND, err("not found"))
        if ((session.headers["x-token"] ?: "") != token) {
            return json(NanoHTTPD.Response.Status.UNAUTHORIZED, err("bad token"))
        }
        return try {
            when {
                uri == "/api/chat" && m == NanoHTTPD.Method.POST -> handleChat(session)
                uri == "/api/a2a" && m == NanoHTTPD.Method.POST -> handleA2a(session)
                uri == "/api/info" -> json(NanoHTTPD.Response.Status.OK, """{"ok":true,"app":"hexora"}""")
                else -> json(NanoHTTPD.Response.Status.NOT_FOUND, err("no route"))
            }
        } catch (t: Throwable) {
            json(NanoHTTPD.Response.Status.INTERNAL_ERROR, err("server error: ${t.message}"))
        }
    }

    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val map = HashMap<String, String>()
        return try {
            session.parseBody(map)
            map["postData"] ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    private fun handleChat(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val o = runCatching { JSONObject(readBody(session)) }
            .getOrElse { return json(NanoHTTPD.Response.Status.BAD_REQUEST, err("bad json")) }
        val msgs = mutableListOf<ChatMsg>()
        val sys = o.optString("system", "").trim()
        if (sys.isNotEmpty()) msgs += ChatMsg("system", sys)
        val arr: JSONArray? = o.optJSONArray("messages")
        arr?.let { a ->
            for (i in 0 until a.length()) {
                val mm = a.optJSONObject(i) ?: continue
                val role = mm.optString("role", "user")
                val content = mm.optString("content", mm.optString("text", ""))
                msgs += ChatMsg(role, content)
            }
        }
        if (msgs.none { it.role == "user" }) {
            return json(NanoHTTPD.Response.Status.BAD_REQUEST, err("no user message"))
        }
        val cfg = AiConfigStore(ctx).active()
        val reply = AiClient(cfg).chat(msgs, emptyList())
        val out = JSONObject()
            .put("reply", reply.text ?: "")
            .put("error", reply.error ?: JSONObject.NULL)
            .put("inTokens", reply.inTokens)
            .put("outTokens", reply.outTokens)
        return json(NanoHTTPD.Response.Status.OK, out.toString())
    }

    private fun handleA2a(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val o = runCatching { JSONObject(readBody(session)) }
            .getOrElse { return json(NanoHTTPD.Response.Status.BAD_REQUEST, err("bad json")) }
        val out = JSONObject()
            .put("ok", true)
            .put("from", o.optString("from", "anonymous"))
            .put("text", o.optString("text", ""))
            .put("at", System.currentTimeMillis())
        return json(NanoHTTPD.Response.Status.OK, out.toString())
    }

    private fun err(msg: String): String = JSONObject().put("error", msg).toString()

    private fun json(status: NanoHTTPD.Response.Status, body: String): NanoHTTPD.Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)
}