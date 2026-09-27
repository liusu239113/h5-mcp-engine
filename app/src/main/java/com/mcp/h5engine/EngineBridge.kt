package com.mcp.h5engine

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * JS <-> 原生 的桥
 *
 * JS 侧发：
 *   Native.post(JSON.stringify({ id, op, data }))
 * 原生回（异步，走主线程）：
 *   window.__nativeResolve(id, JSON.stringify(payload))
 *
 * payload = { id, result } 或 { id, error }
 *
 * op 列表：
 *   ping                 探活
 *   http                 代发 HTTP 请求（顺手绕过 WebView 的 CORS）
 *   mcp.open             连接 MCP 服务（initialize）
 *   mcp.request          通用 JSON-RPC 方法派发
 *   fs.list/read/write   沙箱文件读写（存档）
 */
class EngineBridge(
    private val web: WebView,
    /** 沙箱根目录，一般是 context.filesDir/games/<gameId> */
    private val sandboxRoot: File
) {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val sessions = ConcurrentHashMap<String, McpClient>()

    @JavascriptInterface
    fun post(raw: String) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = msg.optString("id")
        if (id.isEmpty()) return
        val op = msg.optString("op")
        val data = msg.optJSONObject("data") ?: JSONObject()

        scope.launch {
            try {
                resolve(id, dispatch(op, data), null)
            } catch (t: Throwable) {
                resolve(id, null, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun dispatch(op: String, d: JSONObject): JSONObject = when (op) {
        "ping" -> JSONObject().put("ok", true).put("ts", System.currentTimeMillis())
        "http" -> httpRequest(d)
        "mcp.open" -> mcpOpen(d)
        "mcp.request" -> mcpRequest(d)
        "fs.list" -> fsList(d)
        "fs.read" -> fsRead(d)
        "fs.write" -> fsWrite(d)
        else -> throw IllegalArgumentException("unknown op: $op")
    }

    // ---------------- http ----------------

    private fun httpRequest(d: JSONObject): JSONObject {
        val url = d.getString("url")
        val method = d.optString("method", "GET").uppercase()
        val builder = Request.Builder().url(url)

        d.optJSONObject("headers")?.let { h ->
            h.keys().forEach { k -> builder.header(k, h.optString(k)) }
        }

        val bodyStr = d.optString("body", "")
        val rb = if (method == "GET" || method == "HEAD") null
        else bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())

        builder.method(method, rb)
        http.newCall(builder.build()).execute().use { r ->
            return JSONObject()
                .put("status", r.code)
                .put("body", r.body?.string().orEmpty())
        }
    }

    // ---------------- mcp ----------------

    private fun mcpOpen(d: JSONObject): JSONObject {
        val url = d.getString("url")
        val headers = HashMap<String, String>()
        d.optJSONObject("headers")?.let { h ->
            val it = h.keys()
            while (it.hasNext()) {
                val k = it.next()
                headers[k] = h.optString(k)
            }
        }

        val client = McpClient(url, headers)
        val info = client.initialize(d.optString("name", "h5-game"), "1.0.0")

        val key = d.optString("id").ifEmpty { url }
        sessions[key] = client

        return JSONObject()
            .put("serverInfo", client.serverInfo ?: JSONObject())
            .put("protocolVersion", client.negotiatedProtocol ?: "")
            .put("raw", info)
    }

    private fun mcpRequest(d: JSONObject): JSONObject {
        val client = sessions[d.optString("session")]
            ?: sessions.values.firstOrNull()
            ?: throw IllegalStateException("MCP 未连接，先调 mcp.open")

        val method = d.getString("method")
        val params = d.optJSONObject("params") ?: JSONObject()

        return when (method) {
            "tools/list" -> client.listTools()
            "tools/call" -> client.callTool(
                params.getString("name"),
                params.optJSONObject("arguments") ?: JSONObject()
            )
            "resources/list" -> client.listResources()
            "resources/read" -> client.readResource(params.getString("uri"))
            "prompts/list" -> client.listPrompts()
            else -> throw IllegalArgumentException("unsupported MCP method: $method")
        }
    }

    // ---------------- 沙箱文件 ----------------

    private fun sandbox(path: String): File {
        val clean = path.trimStart('/').replace("..", "_")
        return File(sandboxRoot, clean)
    }

    private fun fsList(d: JSONObject): JSONObject {
        val dir = sandbox(d.optString("path", ""))
        val arr = JSONArray()
        dir.listFiles()?.sortedBy { it.name }?.forEach { arr.put(it.name) }
        return JSONObject().put("files", arr)
    }

    private fun fsRead(d: JSONObject): JSONObject {
        val f = sandbox(d.getString("path"))
        return JSONObject().put("text", if (f.exists()) f.readText() else "")
    }

    private fun fsWrite(d: JSONObject): JSONObject {
        val f = sandbox(d.getString("path"))
        f.parentFile?.mkdirs()
        f.writeText(d.optString("text"))
        return JSONObject().put("bytes", f.length())
    }

    // ---------------- 回传 ----------------

    private fun resolve(id: String, result: JSONObject?, error: String?) {
        val payload = JSONObject().put("id", id)
        if (error != null) payload.put("error", error)
        else payload.put("result", result ?: JSONObject())

        val js = "window.__nativeResolve(${payload})"
        main.post { runCatching { web.evaluateJavascript(js, null) } }
    }
}