package com.mcp.h5engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 一个 MCP 服务器（TapTap 小游戏是内置的那个） */
data class McpServer(val name: String, val url: String, var enabled: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("url", url).put("enabled", enabled)

    companion object {
        fun from(o: JSONObject) = McpServer(
            o.optString("name", "MCP"),
            o.optString("url", ""),
            o.optBoolean("enabled", true)
        )
    }
}

data class McpToolInfo(val name: String, val description: String, val schema: JSONObject)

/** 服务器配置持久化（默认带一个指向本机运行时的 TapTap 预设） */
object McpStore {
    private const val PREF = "hexora_mcp"
    private const val KEY = "servers"

    fun defaultServers(): MutableList<McpServer> = mutableListOf(
        McpServer("TapTap 小游戏（官方 MCP）", McpRt.URL_BASE, true)
    )

    fun load(ctx: Context): MutableList<McpServer> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null) ?: return defaultServers()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<McpServer>()
            for (i in 0 until arr.length()) out += McpServer.from(arr.getJSONObject(i))
            if (out.isEmpty()) defaultServers() else out
        } catch (t: Throwable) {
            defaultServers()
        }
    }

    fun save(ctx: Context, list: List<McpServer>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

/**
 * 极简 MCP 客户端，只实现了 Streamable HTTP 传输：
 *   initialize → notifications/initialized → tools/list → tools/call
 * 响应可能是 application/json，也可能是 SSE（event: message / data: {...}），两种都吃。
 */
class McpClient(private val url: String) {

    private var session: String? = null
    private var seq = 0
    var serverName: String = ""
        private set

    private fun rpc(method: String, params: JSONObject?, notify: Boolean = false, timeoutMs: Int = 60_000): JSONObject? {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (params != null) body.put("params", params)
        if (!notify) body.put("id", ++seq)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            session?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val sid = conn.getHeaderField("Mcp-Session-Id")
        if (!sid.isNullOrBlank()) session = sid
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        val payload = pickJson(text)
        if (code !in 200..299) throw RuntimeException("HTTP $code ${(payload ?: text).take(200)}")
        if (notify) return null
        val obj = JSONObject(payload ?: throw RuntimeException("空响应"))
        obj.optJSONObject("error")?.let { throw RuntimeException(it.optString("message", it.toString())) }
        return obj.optJSONObject("result") ?: JSONObject()
    }

    fun initialize() {
        val r = rpc(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "hexora").put("version", "1.9"))
        )
        serverName = r?.optJSONObject("serverInfo")?.optString("name", "") ?: ""
        runCatching { rpc("notifications/initialized", JSONObject(), notify = true, timeoutMs = 10_000) }
    }

    fun listTools(): List<McpToolInfo> {
        val r = rpc("tools/list", JSONObject()) ?: return emptyList()
        val arr = r.optJSONArray("tools") ?: return emptyList()
        val out = ArrayList<McpToolInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            out += McpToolInfo(
                t.optString("name"),
                t.optString("description"),
                t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject())
            )
        }
        return out
    }

    fun callTool(name: String, argsJson: String): String {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val r = rpc(
            "tools/call",
            JSONObject().put("name", name).put("arguments", args),
            timeoutMs = 300_000
        ) ?: return "(无返回)"
        val sb = StringBuilder()
        r.optJSONArray("content")?.let { arr ->
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                when (it.optString("type")) {
                    "text" -> sb.append(it.optString("text"))
                    else -> sb.append(it.toString())
                }
                sb.append('\n')
            }
        }
        if (sb.isEmpty()) sb.append(r.toString())
        if (r.optBoolean("isError")) sb.insert(0, "[工具报错] ")
        return sb.toString().trim().take(20_000)
    }

    private fun pickJson(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("{")) return t
        var last: String? = null
        t.lineSequence().forEach { line ->
            if (line.startsWith("data:")) {
                val d = line.removePrefix("data:").trim()
                if (d.startsWith("{")) last = d
            }
        }
        return last
    }
}

/** 把启用的 MCP 服务器的工具挂给 AI。工具名统一加 mcp_ 前缀，避免和引擎工具撞名 */
class McpHub {

    private data class Entry(val server: McpServer, val tool: McpToolInfo)

    private val index = LinkedHashMap<String, Entry>()
    private val clients = HashMap<String, McpClient>()

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastInfo: String = "未连接"
        private set

    val size: Int get() = index.size

    fun refresh(servers: List<McpServer>, log: (String) -> Unit) {
        index.clear()
        clients.clear()
        val errs = mutableListOf<String>()
        var okServers = 0
        for (s in servers) {
            if (!s.enabled) continue
            try {
                val c = McpClient(s.url)
                c.initialize()
                val list = c.listTools()
                for (t in list) index["mcp_" + t.name] = Entry(s, t)
                clients[s.url] = c
                okServers++
                log("MCP「${s.name}」已连接：${list.size} 个工具")
            } catch (t: Throwable) {
                errs += "${s.name}: ${t.message}"
                log("MCP「${s.name}」连接失败：${t.message}")
            }
        }
        lastError = errs.joinToString("；").ifBlank { null }
        lastInfo = if (okServers == 0) "未连接" else "$okServers 个服务器 · ${index.size} 个工具"
    }

    fun clear() {
        index.clear()
        clients.clear()
        lastInfo = "未连接"
    }

    fun specs(): List<JSONObject> = index.map { (exposed, e) ->
        JSONObject().put("type", "function").put(
            "function",
            JSONObject()
                .put("name", exposed)
                .put("description", ("[TapTap 官方 MCP] " + e.tool.description.ifBlank { e.tool.name }).take(1200))
                .put("parameters", e.tool.schema)
        )
    }

    fun handles(name: String): Boolean = index.containsKey(name)

    /** 由 App 注入：确保服务在跑（必要时拉起并重建连接）。返回是否已可用 */
    @Volatile
    var onEnsure: (() -> Boolean)? = null

    fun call(name: String, argsJson: String): String {
        if (!index.containsKey(name)) return "未知的 MCP 工具：$name"
        return try {
            direct(name, argsJson)
        } catch (t: Throwable) {
            // 本地 node 被系统冻结/杀掉是常态：让守护逻辑把它拉回来，再重试一次
            val ok = runCatching { onEnsure?.invoke() == true }.getOrDefault(false)
            if (!ok) {
                "[MCP 调用失败] ${t.message}（本地服务可能已退出，可到设置 → MCP 服务器点「启动 / 重连」）"
            } else {
                runCatching { direct(name, argsJson) }
                    .getOrElse { "[MCP 调用失败] 服务已重启但仍不可用：${it.message}" }
            }
        }
    }

    private fun direct(name: String, argsJson: String): String {
        val e = index[name] ?: throw IllegalStateException("工具未注册：$name")
        val c = clients[e.server.url] ?: throw IllegalStateException("服务器未连接：${e.server.name}")
        return c.callTool(e.tool.name, argsJson)
    }
}