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
import java.util.concurrent.TimeUnit

/**
 * JS <-> 原生 的桥。
 *
 * JS 侧发：  Native.post(JSON.stringify({ id, op, data }))
 * 原生回：    window.__nativeResolve(payload)
 *
 * op：
 *   ping          探活
 *   log           把 console 输出回传给原生（AI 用 console_logs 读）
 *   http          游戏里发起网络请求（需要自己处理 CORS 时用这个）
 *   fs.list / fs.read / fs.write   游戏沙箱内的文件读写（存档用）
 */
class EngineBridge(
    private val web: WebView,
    sandboxRoot: File,
    private val logs: LogBuffer? = null
) {

    @Volatile
    private var sandboxRoot: File = sandboxRoot

    fun setSandbox(dir: File) {
        sandboxRoot = dir
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

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

        "log" -> {
            logs?.add("${d.optString("level", "log")}: ${d.optString("text")}")
            JSONObject().put("ok", true)
        }

        "http" -> httpRequest(d)
        "fs.list" -> fsList(d)
        "fs.read" -> fsRead(d)
        "fs.write" -> fsWrite(d)
        "fs.delete" -> fsDelete(d)
        else -> throw IllegalArgumentException("unknown op: $op")
    }

    private fun httpRequest(d: JSONObject): JSONObject {
        val url = d.getString("url")
        val method = d.optString("method", "GET").uppercase()
        val builder = Request.Builder().url(url)

        d.optJSONObject("headers")?.let { h ->
            val it = h.keys()
            while (it.hasNext()) {
                val k = it.next()
                builder.header(k, h.optString(k))
            }
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

    // ---------------- 游戏沙箱文件（存档） ----------------

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

    private fun fsDelete(d: JSONObject): JSONObject {
        val f = sandbox(d.getString("path"))
        return JSONObject().put("ok", f.delete())
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