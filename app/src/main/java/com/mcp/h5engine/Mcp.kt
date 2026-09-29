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
        McpServer("TapTap 小游戏（官方 MCP）", McpRt.URL_BASE, true),
        McpServer("TapTap Maker（本地制造开发）", McpRt.MAKER_URL_BASE, true)
    )

    /** 老用户配置里没有 Maker 这条：自动补上，不动他已有的增删 */
    private fun ensureMaker(list: MutableList<McpServer>) {
        if (list.none { it.url.contains(McpRt.MAKER_PORT.toString()) }) {
            list += McpServer("TapTap Maker（本地制造开发）", McpRt.MAKER_URL_BASE, true)
        }
    }

    fun load(ctx: Context): MutableList<McpServer> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null) ?: return defaultServers()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<McpServer>()
            for (i in 0 until arr.length()) out += McpServer.from(arr.getJSONObject(i))
            if (out.isEmpty()) defaultServers() else {
                ensureMaker(out)
                out
            }
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
                .put("clientInfo", JSONObject().put("name", "hexora").put("version", "1.10"))
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

    /**
     * 桥自己塞进来的本地工具。
     *
     * 它们永远会回（只走 Maker HTTP API，子进程死着也能答），所以**不能**拿它们当
     * 「Maker 已就绪」的证据 —— 否则「子进程活着但生成类工具还没注册」会被误判成正常。
     */
    private val BRIDGE_LOCAL_TOOLS = setOf(
        "maker_ensure_project", "maker_list_apps", "maker_ui_list_kits", "maker_ui_apply_kit",
        // 抠图（去背景）：只走 HTTPS 直连抠抠图接口，子进程死了也能用 —— 所以它**同样不算**
        // 「Maker 生成类工具已就绪」的证据。桥的 LOCAL_TOOLS 加一个，这里就必须跟一个。
        "maker_remove_bg"
    )

    /** Maker 通道连上没（没连上是另一码事，用不着重抓清单） */
    @Volatile
    var makerConnected: Boolean = false
        private set

    /**
     * Maker 通道「连上了，但生成类工具还没注册上来」。
     *
     * 用户报的「生图工具一会儿能用一会儿不能用」就是这个：工具清单只在启动 / 掉线时抓一次，
     * 抓到的那一次正好撞上子进程重启，清单里就只剩桥的本地工具，
     * 然后被永久缓存 —— 授权明明是好的，`maker_generate_image` 却永远不出现。
     */
    @Volatile
    var makerStarved: Boolean = false
        private set


    /**
     * 两台服务器的工具名不能长得一样。
     *
     * 以前不管来自哪台，全部统一加 "mcp_" 前缀 —— 结果模型（以及用户看界面）根本分不清
     * 「Maker 生图」和「TapTap 开放平台的开发者接口」，于是问 Maker 的事它跑去调
     * list_developers_and_apps / complete_oauth_authorization，还把 H5 的 OAuth 授权链接
     * 甩给用户当答案。现在按服务器分流前缀：Maker = maker_，H5 开放平台 = mcp_。
     */
    private fun isMakerServer(s: McpServer): Boolean = s.url.contains(McpRt.MAKER_PORT.toString())

    private fun prefixFor(s: McpServer): String = if (isMakerServer(s)) "maker_" else "mcp_"

    /**
     * 前缀只在「工具名还没有这个前缀」时补。
     *
     * Maker 自己已经带了两个 maker_ 开头的工具（maker_status_lite、maker_build_current_directory），
     * 桥的本地工具也叫 maker_ensure_project / maker_list_apps —— 无脑拼前缀会变成
     * maker_maker_ensure_project，跟提示词里写的名字对不上，模型反而更糊涂。
     */
    private fun expose(s: McpServer, toolName: String): String {
        // H5 开放平台无条件加 mcp_：它自己也暴露了一个叫 maker_build_current_directory 的工具，
        // 不加前缀会伪装成 Maker 的能力，正是用户骂的那种混乱。
        if (!isMakerServer(s)) return "mcp_" + toolName
        return if (toolName.startsWith("maker_")) toolName else "maker_" + toolName
    }


    fun refresh(servers: List<McpServer>, log: (String) -> Unit) {
        index.clear()
        clients.clear()
        makerConnected = false
        makerStarved = false
        val errs = mutableListOf<String>()
        var okServers = 0
        for (s in servers) {
            if (!s.enabled) continue
            try {
                val c = McpClient(s.url)
                c.initialize()
                val list = c.listTools()
                for (t in list) index[expose(s, t.name)] = Entry(s, t)
                clients[s.url] = c
                okServers++
                if (isMakerServer(s)) {
                    makerConnected = true
                    // 只算子进程真正注册的工具：桥的本地工具永远在，
                    // 拿它们当就绪证据就会把「生成类工具还没起来」误判成正常。
                    val real = list.count { !BRIDGE_LOCAL_TOOLS.contains(it.name) }
                    makerStarved = real == 0
                    log("MCP「${s.name}」已连接：${list.size} 个工具（生成类 $real 个）")
                } else {
                    log("MCP「${s.name}」已连接：${list.size} 个工具")
                }
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
        makerConnected = false
        makerStarved = false
        lastInfo = "未连接"
    }

    /** 当前可用工具名（给报错文案用，太长了就截断） */
    private fun availableNames(): String {
        val all = index.keys.toList()
        return if (all.size <= 40) all.joinToString("、") else all.take(40).joinToString("、") + " …"
    }

    fun specs(): List<JSONObject> = index.map { (exposed, e) ->
        // 描述里把「归属」写在最前面：模型挑工具时先看的是描述，
        // 旧版只写服务器名，它照样把 H5 开放平台的开发者接口当成 Maker 的能力。
        val tag = if (isMakerServer(e.server))
            "[TapTap Maker / 素材生成 · 无需授权] "
        else
            "[TapTap 小游戏开放平台 / H5 上架与开发者数据 · 需 OAuth 授权，与 Maker 无关] "
        JSONObject().put("type", "function").put(
            "function",
            JSONObject()
                .put("name", exposed)
                .put("description", (tag + e.tool.description.ifBlank { e.tool.name }).take(1200))
                .put("parameters", e.tool.schema)
        )
    }

    fun handles(name: String): Boolean = index.containsKey(name)

    /** 由 App 注入：确保服务在跑（必要时拉起并重建连接）。返回是否已可用 */
    @Volatile
    var onEnsure: (() -> Boolean)? = null

    fun call(name: String, argsJson: String): String {
        if (!index.containsKey(name)) {
            // 工具名不在索引里，先别急着跟模型说「未知工具」。
            //
            // 用户报的「生图一会儿能用一会儿不能用」就是这里：MCP 服务刚起 / 正在重启的那一两秒
            // 把清单抓走了，清单里没有 maker_generate_image；模型第二次调就被判「未知工具」，
            // 它很听话地放弃了 —— 于是表现为「明明授权好了，生图工具却不在」。
            //
            // 现在：先把服务拉一次、把清单重抓一遍，抓到了就直接执行，用户完全无感。
            val revived = runCatching { onEnsure?.invoke() == true }.getOrDefault(false)
            if (revived && index.containsKey(name)) {
                return runCatching { direct(name, argsJson) }
                    .getOrElse { "[MCP 调用失败] ${it.message}" + authOwnershipHint(it.message, name) }
            }
            val why = if (makerStarved && !makerConnected) {
                "（本地 Maker 服务还没起来）"
            } else if (makerStarved) {
                "（Maker 通道已连上，但生成类工具还在注册 —— 通常几秒内就好）"
            } else {
                ""
            }
            return "未知的 MCP 工具：$name$why\n" +
                "【重要】不要就此放弃，也不要跟用户说「我没有这个能力 / 工具不存在」。" +
                "这是工具清单的注册时序问题，正确做法是：**等 5 秒左右，用完全相同的工具名再调用一次**" +
                "（最多重试 3 次）。三次都失败，就告诉用户「Maker 工具通道还在注册，请稍等片刻再让我试一次」。" +
                "当前清单里可用的工具：${availableNames()}"
        }
        return try {
            direct(name, argsJson)
        } catch (t: Throwable) {
            // 本地 node 被系统冻结/杀掉是常态：让守护逻辑把它拉回来，再重试一次
            val ok = runCatching { onEnsure?.invoke() == true }.getOrDefault(false)
            if (!ok) {
                "[MCP 调用失败] ${t.message}（本地服务可能已退出，App 正在后台自动重连，过几秒再试一次）" +
                    authOwnershipHint(t.message, name)
            } else {
                runCatching { direct(name, argsJson) }
                    .getOrElse { "[MCP 调用失败] 服务已重启但仍不可用：${it.message}" + authOwnershipHint(it.message, name) }
            }
        }
    }

    /**
     * 授权类报错必须标明「归属」。
     *
     * 以前这类报错原样抛给模型，模型就顺手把 TapTap 开放平台的 OAuth 授权链接塞给用户 ——
     * 而用户问的往往是 Maker（本地生图），两边一混就被骂「乱搞」。这里明确说清：
     * 这条属于 H5 开放平台，Maker 的凭证在设置 → Maker 面板（PAT），两码事。
     */
    private fun authOwnershipHint(msg: String?, tool: String): String {
        val m = (msg ?: "") + tool
        val authLike = listOf("授权", "认证", "oauth", "OAuth", "-32603", "unauthorized", "401", "token")
            .any { m.contains(it) }
        if (!authLike) return ""
        return "\n注：这条报错来自 TapTap 小游戏开放平台（H5 上架 / 开发者数据）的 OAuth 授权链路，" +
            "跟 TapTap Maker 是两套东西。要查/用 Maker（素材生成、Maker 项目列表）请用 maker_ 开头的工具" +
            "（maker_list_apps / maker_ensure_project），Maker 凭证在设置 → Maker 面板。" +
            "不要把这条授权链接当成 Maker 的授权发给用户。"
    }

    private fun direct(name: String, argsJson: String): String {
        val e = index[name] ?: throw IllegalStateException("工具未注册：$name")
        val c = clients[e.server.url] ?: throw IllegalStateException("服务器未连接：${e.server.name}")
        val out = c.callTool(e.tool.name, argsJson)
        // 「构建过 Maker」是判型唯一骗不了人的事实：构建成功就把当前工程钉成 Maker
        // （写 .hexora-kind，projKind() 优先读它）。按「去前缀后的真名」判，
        // H5 开放平台那个同名工具不会被误算成 Maker 构建。
        if (e.tool.name == "maker_build_current_directory" && !out.startsWith("[工具报错]")) {
            runCatching { EngineTools.onMakerBuiltProject?.invoke() }
        }
        return out
    }
}