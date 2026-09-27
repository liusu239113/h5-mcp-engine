package com.mcp.h5engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 网页 console 日志环形缓冲 */
class LogBuffer(private val cap: Int = 400) {
    private val q = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        q.addLast(line.take(600))
        while (q.size > cap) q.removeFirst()
    }

    @Synchronized
    fun tail(n: Int): List<String> = q.toList().takeLast(n.coerceAtLeast(1))

    @Synchronized
    fun clear() = q.clear()
}

/**
 * 截图疑似空白时跑的「页面自检」。
 *
 * 为什么要有它：WebGL / canvas / 视频是硬件加速内容，某些路径下截图可能抓不到像素，
 * 截出来是纯色。模型看到纯色图很容易下结论「引擎没加载 / Three.js 不存在」，
 * 然后把好代码改坏。这段脚本从**运行时事实**出发回答「页面到底加载没有」，
 * 让模型有依据，而不是靠一张图瞎猜。
 */
private const val SHOT_PROBE_JS = """
(function(){
  try {
    var out = {};
    out.readyState = document.readyState;
    out.url = location.href;
    var cvs = [].slice.call(document.querySelectorAll('canvas'));
    out.canvasCount = cvs.length;
    out.canvases = cvs.slice(0, 6).map(function(c){
      var ctx3 = null;
      try { ctx3 = !!(c.getContext && (c.getContext('webgl') || c.getContext('webgl2') || c.getContext('experimental-webgl'))); } catch(e){}
      return c.width + 'x' + c.height + (ctx3 ? ' [webgl]' : ' [2d/other]') +
             ' vis=' + (c.offsetWidth > 0 && c.offsetHeight > 0) +
             ' px=' + c.offsetWidth + 'x' + c.offsetHeight;
    });
    out.scripts = document.scripts.length;
    out.bodyChildren = document.body ? document.body.children.length : -1;
    out.bodyText = document.body ? (document.body.innerText || '').replace(/\s+/g,' ').slice(0, 200) : '';
    // 常见 3D / 游戏引擎是否露头
    var g = window;
    out.engine = {
      THREE: typeof g.THREE !== 'undefined',
      BABYLON: typeof g.BABYLON !== 'undefined',
      Phaser: typeof g.Phaser !== 'undefined',
      PIXI: typeof g.PIXI !== 'undefined',
      CocosEngine: typeof g.cc !== 'undefined' || typeof g.CocosEngine !== 'undefined',
      Laya: typeof g.Laya !== 'undefined'
    };
    out.err = (window.__hxErrors && window.__hxErrors.length) ? window.__hxErrors.slice(-5) : [];
    return JSON.stringify(out);
  } catch (e) {
    return JSON.stringify({probeError: String(e)});
  }
})()
"""

/** 引擎与正在运行的 WebView 交互的能力，由 MainActivity 实现 */
interface GameUi {
    fun currentGameId(): String
    fun openGame(id: String)
    fun reloadGame()

    /** 同步执行 JS，返回结果 JSON 字符串。必须从子线程调用 */
    fun runJsSync(code: String, timeoutMs: Int = 6000): String

    /**
     * 截图。返回的是 **CSS 像素坐标系** 的 JPEG，
     * 因此模型在图上读出的坐标可以直接喂给 pointer()。
     */
    fun snapshotCss(maxWidth: Int = 720, quality: Int = 72): ByteArray?

    /**
     * 上一次 screenshot 是否疑似「空白 / 纯色」。
     *
     * 为什么需要它：WebGL / canvas / 视频这类硬件加速画面，某些机型或时机下抓不到像素，
     * 截出来就是一片纯色。模型看到白图会直接断言「引擎没加载 / 画面没渲染」，
     * 然后去瞎改本来没问题的代码 —— 所以工具层必须把这件事说清楚。
     */
    fun lastShotWasBlank(): Boolean = false

    /**
     * 上一次整屏截图的换算信息：
     * `[scale, winW, winH, density, webLeft, webTop, webW, webH]`
     * （scale = 图片px / 窗口px；web* 都是窗口 px）。
     */
    fun lastShotMeta(): FloatArray = FloatArray(0)

    /**
     * **离屏**抓一张「游戏预览」画面：不切页、不改可见性、不点屏幕。
     * 这是 AI 自己的调试眼 —— 用户明确要求"截图验证必须有"，但"不许动我的屏幕"。
     * 返回 null = 拿不到（游戏页没加载过 / 该机型不允许离屏画 WebView / 图是纯色空白）。
     */
    fun snapshotGameOffscreen(maxWidth: Int = 720): ByteArray? = null

    /** 把「截图里的像素坐标」换算成网页 CSS 坐标；没截过图返回 null */
    fun shotToCss(x: Float, y: Float): FloatArray? = null

    /** kind: 0=down 1=up 2=move */
    fun pointer(x: Float, y: Float, kind: Int)

    fun inputText(text: String)
    fun consoleTail(n: Int): List<String>
    fun consoleClear()

    /** 当前停在哪一页：0 = 对话、1 = 游戏预览、2 = 发布 */
    fun currentTab(): Int = 0

    /**
     * 切到某一页。
     * **对话 / 预览 / 发布 这三页是 App 的原生控件，不在 webview 的 DOM 里**，
     * 所以模型用 click / js_eval 永远点不到它们。
     * **这里不提供切页能力** —— 切页会动用户正在看的屏幕（用户明确不许）。
     * 只提供只读的 currentTab()，让 AI 知道自己截到的到底是哪一页。
     */
}

/**
 * AI 能调用的工具集（对应 OpenAI function calling 的工具声明）。
 * 所有文件操作都在工程根目录内，且做了目录穿越防护。
 */
class EngineTools(private val ui: GameUi, private val root: File) {

    data class ToolResult(val text: String, val images: List<ByteArray> = emptyList())
    companion object {
        /** MCP 工具桥（TapTap 小游戏等），App 启动时注入；声明与执行都会带上它 */
        @Volatile
        var mcp: McpHub? = null

        /**
         * MCP 工具准入开关：**默认关**。
         *
         * AI 平时【看不到】这些工具声明，也就无从「自动识别 / 自动调一圈」。
         * 只有用户这轮明确表达相关意图（广告 / 排行榜 / TapTap / 素材 / 构建…）才临时放行。
         *
         * 原因：TapTap 部分接口会在「只是改个信息」时**顺带把游戏发布上线**，回执还不提示，
         * 模型自己都不知道已经发布了。所以宁可平时不给它工具。
         */
        @Volatile
        var mcpAllowed: Boolean = false

        /**
         * 写 / 发布类 MCP 工具黑名单 —— 真正常驻挡住的只有这些。
         *
         * 为什么从「白名单放行」改成「黑名单拦住」：
         * 以前是「默认全关 + 关键词放行」，只要用户说法和词表差一个字（比如他说「看看我的开发者应用」，
         * 词表里只有「应用信息」），App 就当没这回事，AI 那边连工具**声明**都收不到，
         * 只能回「未知工具 / 我没有这个能力」—— 它没撒谎，是我们没递过去，
         * 用户看到的就是「这功能时好时坏」。
         *
         * 现在反过来：只读查询、引导文档、素材生成全部常驻；
         * 剩下这些会改线上数据 / 发布上线的，才需要用户明确意图（或一句「可以」）才放行。
         * 原因还是那条：TapTap 部分接口会**顺带把游戏发布上线**，回执不提示。
         */
        val MCP_WRITE_TOOLS = setOf(
            // H5 上传 / 发布链路
            "upload_h5_game", "update_app_info", "create_app", "create_developer",
            "clear_auth_data", "upload_image",
            // 排行榜写操作
            "create_leaderboard", "publish_leaderboard",
            // 社区写操作
            "like_current_app_review", "reply_current_app_review",
            // Maker 侧写操作
            "maker_build_current_directory", "build_current_directory", "add_test_whitelist", "confirm_character_voice"
        )

        /** 门禁按「去掉前缀后的真名」判：现在 Maker 通道暴露成 maker_xxx、H5 通道是 mcp_xxx */
        private fun isWriteTool(name: String): Boolean {
            if (MCP_WRITE_TOOLS.contains(name)) return true
            var s = name
            for (p in listOf("mcp_", "maker_")) {
                if (s.startsWith(p)) {
                    s = s.substring(p.length)
                    if (MCP_WRITE_TOOLS.contains(s)) return true
                }
            }
            return false
        }

        /**
         * App 上下文（MainActivity 注入）。
         * Maker 的授权与凭据检查要跑 CLI、读 pat.json，必须有 Context。
         */
        @Volatile
        var ctxRef: Context? = null
    }

    // ==================== 工具声明 ====================

    private fun fn(name: String, desc: String, props: String, required: List<String>): JSONObject {
        val params = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject(props))
        if (required.isNotEmpty()) params.put("required", JSONArray(required.toTypedArray()))
        return JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("parameters", params))
    }

    private val allSpecs: List<JSONObject> by lazy {
        listOf(
            fn("engine_status", "查看引擎状态：当前游戏、屏幕尺寸、游戏列表",
                """{"game":{"type":"string","description":"可选，指定游戏 id"}}""",
                emptyList()),

            fn("game_list", "列出工程里所有游戏", "{}", emptyList()),

            fn("game_create", "创建新游戏骨架（index.html + game.js），已存在则不动",
                """{"id":{"type":"string","description":"英文 id，如 my_game"},
                   "title":{"type":"string"}}""",
                listOf("id")),

            fn("game_write", "写入/覆盖游戏文件，写完自动热重载。path 相对游戏目录，如 index.html",
                """{"game":{"type":"string","description":"可选，默认当前游戏"},
                   "path":{"type":"string"},
                   "content":{"type":"string"}}""",
                listOf("path", "content")),

            fn("game_read", "读取游戏文件；不传 path 则返回文件清单",
                """{"game":{"type":"string"},"path":{"type":"string"}}""",
                emptyList()),

            fn("game_patch", "按行号替换文件片段（改大文件时优先用它，省 token）",
                """{"path":{"type":"string"},"startLine":{"type":"integer"},
                   "endLine":{"type":"integer"},"content":{"type":"string"}}""",
                listOf("path", "startLine", "endLine", "content")),

            fn("game_launch", "启动/切换游戏，让它显示在手机屏幕上",
                """{"game":{"type":"string"}}""",
                listOf("game")),

            fn("game_reload", "热重载当前游戏，改完代码立刻看效果", "{}", emptyList()),

            fn("js_eval", "在当前游戏里执行 JS 并返回结果。用于取状态、改数值、调内部函数",
                """{"code":{"type":"string","description":"表达式或 IIFE，返回值需可 JSON 化"}}""",
                listOf("code")),

            fn("game_shot", "**离屏抓一张「游戏画面」**（不切页、不动用户屏幕、不点击）。" +
                "这是你自己的调试眼：用来确认游戏画面有没有白屏 / 错位 / 被遮挡 / 有没有渲染出来。" +
                "返回的是游戏 WebView 当前渲染内容的截图；拿不到图（纯色空白）会在文字里说明，" +
                "**不要**据此断言「游戏坏了」。" +
                """{"maxWidth":{"type":"integer","description":"图片最长边，默认 720"}}""",
                emptyList()),
            fn("screenshot", "截取**整屏**画面（跟手机自带截图一样，含 App 顶栏/底栏/游戏画面，返回图片）。" +
                "用它检查 UI 有没有白屏/错位/遮挡。返回文本里带「图内坐标 → 点击坐标」的换算，" +
                "也可以直接把图内像素喂给 tap(space=\"shot\")",
                """{"maxWidth":{"type":"integer","description":"整屏图宽度，默认 720"}}""",
                emptyList()),

            fn("tap", "点击画面。默认坐标是网页 CSS 像素；space=\"shot\" 时直接用在 screenshot 图上量到的像素坐标" +
                "（推荐，少一次换算、不会算错）",
                """{"x":{"type":"number"},"y":{"type":"number"},
                   "space":{"type":"string","description":"css（默认）或 shot（截图内像素）"}}""",
                listOf("x", "y")),

            fn("swipe", "从一点滑到另一点（拖拽/翻页）",
                """{"x1":{"type":"number"},"y1":{"type":"number"},
                   "x2":{"type":"number"},"y2":{"type":"number"},
                   "ms":{"type":"integer","description":"时长，默认 300"}}""",
                listOf("x1", "y1", "x2", "y2")),

            fn("type_text", "往当前聚焦的输入框打字",
                """{"text":{"type":"string"}}""",
                listOf("text")),

            fn("console_logs", "读取游戏 console 输出（log/warn/error），排查运行时报错",
                """{"lines":{"type":"integer","description":"默认 40"},
                   "clear":{"type":"boolean"}}""",
                emptyList()),

            fn("game_libs", "查看内置的 H5 游戏框架（Phaser/PixiJS/Three/Matter/p5/Howler）及用法。" +
                "写较完整的游戏前先调它，别自己从零造轮子",
                "{}", emptyList()),
            fn("lib_usage", "同 game_libs：查看内置框架清单与引用方式", "{}", emptyList()),

            fn("maker_auth",
                "【云素材必须先授权】管理 TapTap Maker 授权（生图 / 音乐 / 音效 / 配音 / 3D / 视频都要它）。" +
                    "action=status 先查状态；没授权就用 action=start —— 它会**立刻返回一个授权链接**，" +
                    "你把链接单独一行、原样贴进回复，让用户点开、登录、点「创建 token」。" +
                    "不要让用户自己去设置页找入口（App 会在对话里把链接递给他）。",
                """{"action":{"type":"string","description":"status=查状态；start=开始授权并拿到链接"}}""",
                listOf("action")),

            fn("ad_guide",
                "【接广告必调】一次给全：TapTap 激励视频官方契约 + adkit.js 模板位置 + 八条硬纪律 + 验收清单。" +
                    "用户只要提到广告 / 激励视频 / 发奖 / 变现，先调它，别凭印象写，更不许用「模拟广告」糊过去",
                "{}", emptyList())
        )
    }

    /**
     * allow 为 null 或空集合都表示全开；技能白名单只管引擎工具。
     *
     * MCP 工具**默认不附带**：只有本轮用户明确表达了相关意图（mcpAllowed=true）才注入声明，
     * 否则模型连这些工具的存在都看不到 —— 从源头杜绝「自动识别一圈 / 顺手发布」。
     */
    fun specs(allow: Set<String>? = null): List<JSONObject> {
        val base = if (allow.isNullOrEmpty()) allSpecs
        else allSpecs.filter { allow.contains(it.getJSONObject("function").getString("name")) }
        val extra = mcp?.specs()?.filter {
            val n = it.getJSONObject("function").getString("name")
            mcpAllowed || !isWriteTool(n)
        } ?: emptyList()
        return if (extra.isEmpty()) base else base + extra
    }

    fun names(): List<String> = allSpecs.map { it.getJSONObject("function").getString("name") }

    // ==================== 执行 ====================

    fun call(name: String, argsJson: String): ToolResult {
        // MCP 工具（TapTap / Maker）转给对应服务器。
        // 只读类常驻；写 / 发布类要用户放行 —— 模型可能凭记忆猜工具名，猜中就等于绕过闸门，
        // 所以这里也要拦，并且把「为什么不能调」直说给它，免得它转头跟用户说「我没有这个能力」。
        mcp?.let { hub ->
            if (hub.handles(name)) {
                if (mcpAllowed || !isWriteTool(name)) {
                    return ToolResult(hub.call(name, argsJson))
                }
                return ToolResult(
                    "「$name」属于写 / 发布类接口，本轮没有放行（TapTap 侧有些接口会顺带把游戏发布上线，回执不提示）。" +
                        "正确做法：先用一句话告诉用户你打算做什么、会改动什么，等他明确同意" +
                        "（他说「可以 / 好 / 确认」这类，下一轮工具就会放行给你）。"
                )
            }
            // 名字明显是 MCP 工具（maker_xxx / mcp_xxx）却没挂在清单里 —— 十有八九不是「没有这个工具」，
            // 而是「服务刚起、工具还没注册完」。交给 McpHub：它会先自动重抓一次清单（抓到就直接执行），
            // 实在还没有才回一段带「必须重试」指引的话。
            // 以前这里直接掉到 exec，回「未知工具: xxx」，模型就乖乖放弃了。
            if (name.startsWith("maker_") || name.startsWith("mcp_")) {
                return ToolResult(hub.call(name, argsJson))
            }
        }
        return try {
            exec(name, runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject()))
        } catch (t: Throwable) {
            ToolResult("工具执行失败 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 广告硬纪律：即使文档读不到，也要把正确做法塞给模型 */
    private val AD_DISCIPLINE = """
1. 只有用户点击能触发广告，文件加载期零副作用；
2. 单飞：同一时间只允许一条流程，重复点击只提示、不重复拉起；
3. 成功唯一定义：onClose(res) 里 res.isEnded === true（另一条通道为 code===200 且 data.rewarded===true）；
4. 失败绝不补发奖励；
5. 看门狗 90s：平台 Promise 永不 settle 时复位，否则玩家只能刷新页面；
6. 流程纪元：迟到结果丢弃，防一次广告发两份奖励；
7. 配额只在 onReward 回调里消耗（失败不扣，玩家可重试）；
8. 每条出口都有玩家可读的中文提示，绝不静默。
""".trimIndent()

    private val AD_GUIDE_FALLBACK = """
【TapTap 小游戏激励视频：官方契约】
- 创建：tap.createRewardedVideoAd({ adUnitId }) —— 单例组件，只创建一次全程复用；
- 创建后自动拉素材：成功 onLoad()，失败 onError(err)；
- 播放：show() 返回 Promise；素材未就绪会 rejected → 按官方建议 load().then(show) 重试一次；
- 发奖判据只有一条：onClose(res) 里 res.isEnded === true；中途关掉不发奖；
- 开发者不能主动隐藏或关闭广告；看完或关闭后素材清空并自动加载下一份；
- 第二条通道（本 App 场景）：window.ColorboxAI.vatask.completeRewardVideo()，恒零参数，成功判据 code===200 且 data.rewarded===true；
- 两条桥都探测不到（纯网页 / 预览环境）：mode() 返回 blocked / broken → 只提示、不发奖，
  绝不允许降级成「点击即发奖」或「模拟广告」当作交付；
- 真机真实广告必须在 TapTap 容器内验证；本地联调用地址后加 ?admock=1，发布前把 adkit.js 里 MOCK_ENABLED 改为 false。
""".trimIndent()

    private fun gameDir(game: String?): File {
        val g = game?.takeIf { it.isNotBlank() } ?: ui.currentGameId()
        return File(root, g)
    }

    /** 防目录穿越 */
    private fun safe(base: File, rel: String): File {
        val b = base.canonicalFile
        val f = File(b, rel).canonicalFile
        require(!rel.contains("..") && f.path.startsWith(b.path)) { "非法路径: $rel" }
        return f
    }

    /**
     * 解析文件路径。
     * 关键：adkit.js / AD_KIT.md / engine.js 都在项目根的 _shared/ 里，
     * 原来只会去「当前游戏目录」找，导致 AI 按技能提示去读却永远「文件不存在」，
     * 最后自己发明了模拟广告。这里允许 _shared/ 前缀（以及裸文件名）直接命中共享资产。
     */
    private fun resolve(game: String?, rel: String): File {
        val r = rel.trim().trimStart('/')
        return when {
            r == "_shared" -> File(root, "_shared")
            r.startsWith("_shared/") -> safe(File(root, "_shared"), r.removePrefix("_shared/"))
            r == "adkit.js" || r == "AD_KIT.md" -> safe(File(root, "_shared"), r)
            r == "_uploads" || r.startsWith("_uploads/") -> {
                // 工作区是「每个项目独立」的：素材/文档放在当前项目目录下，
                // 这样游戏代码用相对路径（_uploads/media/x.png）就引用得到。
                // 旧版本把它们放在工程根（所有项目混在一起），这里按
                // 「项目内优先 → 工程根兜底」兼容，老工程不会突然读不到自己的素材。
                val tail = if (r == "_uploads") "" else r.removePrefix("_uploads/")
                val inGame = safe(File(gameDir(game), "_uploads"), tail)
                if (inGame.exists()) inGame else safe(File(root, "_uploads"), tail)
            }
            r == "_skills" -> File(root, "_skills")
            r.startsWith("_skills/") -> safe(File(root, "_skills"), r.removePrefix("_skills/"))
            else -> {
                val f = safe(gameDir(game), r)
                // 用户只报文件名也要能读到：素材/文档/技能散在 _uploads、_skills 里
                if (f.exists()) f else (fuzzyFind(r) ?: f)
            }
        }
    }

    /**
     * 只报文件名也能读：在 _skills/ → _uploads/ → _shared/ 里按 basename 忽略大小写找。
     * 顺序有讲究：用户刚传的东西优先级最高，其次是共享资产。
     * 重名时取第一个（不会瞎猜路径，找不到就返回 null 交给上层报「文件不存在」）。
     */
    private fun fuzzyFind(name: String): File? {
        val base = name.trim().trimStart('/').substringAfterLast('/').lowercase()
        if (base.isEmpty() || base.contains("..")) return null
        val dirs = listOf(
            File(gameDir(null), "_uploads"),   // 当前项目的工作区：素材/文档优先命中
            File(root, "_skills"),
            File(root, "_uploads"),            // 旧版本遗留在工程根的素材
            File(root, "_shared")
        )
        for (d in dirs) {
            if (!d.isDirectory) continue
            val hit = d.walkTopDown()
                .filter { it.isFile && it.name.lowercase() == base }
                .take(1).firstOrNull()
            if (hit != null) return hit
        }
        return null
    }

    private fun tree(dir: File, base: File = dir): String {
        val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        if (files.isEmpty()) return "(目录为空)"
        return files.joinToString("\n") { "  ${it.relativeTo(base).path}  (${it.length()}B)" }
    }

    private fun exec(n: String, a: JSONObject): ToolResult = when (n) {

        "engine_status" -> {
            val size = runCatching { ui.runJsSync("innerWidth+'x'+innerHeight+' dpr='+devicePixelRatio") }
                .getOrDefault("?")
            ToolResult(
                "当前游戏: ${ui.currentGameId()}\n" +
                "屏幕: $size\n" +
                "工程目录: ${root.absolutePath}\n" +
                "游戏列表: " + (root.listFiles()?.filter { it.isDirectory && !it.name.startsWith("_") }?.joinToString { it.name } ?: "无")
            )
        }

        "game_list" -> ToolResult(
            root.listFiles()?.filter { it.isDirectory && !it.name.startsWith("_") }?.joinToString("\n") { "  - ${it.name}" }
                ?: "(还没有游戏)"
        )

        "game_create" -> {
            val id = a.getString("id").trim()
            require(id.matches(Regex("[A-Za-z0-9_\\-]{1,40}"))) { "id 只能是字母数字下划线短横线" }
            val d = File(root, id).apply { mkdirs() }
            val created = mutableListOf<String>()
            val html = File(d, "index.html")
            if (!html.exists()) {
                html.writeText(DefaultGame.HTML.replace("\$TITLE", a.optString("title", id)))
                created += "index.html"
            }
            val js = File(d, "game.js")
            if (!js.exists()) {
                js.writeText(DefaultGame.JS)
                created += "game.js"
            }
            ToolResult(
                if (created.isEmpty()) "游戏 $id 已存在，共 ${d.listFiles()?.size ?: 0} 个文件"
                else "已创建游戏 $id（${created.joinToString()}）"
            )
        }

        "game_write" -> {
            val rel = a.getString("path")
            val f = resolve(a.optString("game"), rel)
            f.parentFile?.mkdirs()
            f.writeText(a.getString("content"))
            val isShared = rel.trim().trimStart('/').startsWith("_shared/")
            if (!isShared) ui.reloadGame()
            ToolResult(
                "已写入 $rel（${f.length()} 字节）" +
                    if (isShared) "（共享资产，不需要重载）" else "，并已热重载"
            )
        }

        "game_read" -> {
            val g = a.optString("game")
            val p = a.optString("path")
            if (p.isBlank()) {
                val d = gameDir(g)
                val sh = File(root, "_shared")
                val head =
                    if (!d.exists()) "游戏目录不存在: ${d.name}"
                    else "${d.name} 文件清单：\n" + tree(d)
                val shared = if (sh.isDirectory && sh.listFiles()?.isNotEmpty() == true)
                    "\n\n_shared/ 共享资产（用 game_read path=_shared/xxx 读）：\n" + tree(sh)
                else ""
                val up = File(root, "_uploads")
                val ups = if (up.isDirectory && up.listFiles()?.isNotEmpty() == true)
                    "\n\n_uploads/ 用户上传的素材与文档（用 game_read path=_uploads/xxx 读）：\n" + tree(up)
                else ""
                val sk = File(root, "_skills")
                val sks = if (sk.isDirectory && sk.listFiles()?.isNotEmpty() == true)
                    "\n\n_skills/ 用户上传的技能（用 game_read path=_skills/xxx 读，也可以只报文件名）：\n" + tree(sk)
                else ""
                ToolResult(head + shared + ups + sks)
            } else {
                val f = resolve(g, p)
                ToolResult(
                    if (!f.exists()) {
                        // 还是没找到：把名字相近的候选列出来，让模型换一个名字再读
                        val want = p.substringAfterLast('/').lowercase()
                        val cand = listOf(
                            File(root, "_skills"), File(root, "_uploads"), File(root, "_shared")
                        ).filter { it.isDirectory }.joinToString("") { d ->
                            val ns = d.walkTopDown().filter { it.isFile }.take(60)
                                .map { it.name }
                                .filter { it.lowercase().contains(want) }
                                .take(8).toList()
                            if (ns.isEmpty()) "" else "\n  ${d.name}/ 里有相近的：${ns.joinToString("、")}"
                        }
                        "文件不存在: $p（共享资产要带 _shared/ 前缀，例如 _shared/adkit.js；" +
                            "用户上传的文档/技能也可以只报文件名）" + cand
                    } else "```\n${f.readText().take(60000)}\n```"
                )
            }
        }

        // Maker 云能力授权：AI 自己就能把授权链接递给用户，不用用户去设置页翻。
        // 这是内置工具，不受 MCP 准入开关限制 —— 否则「需要授权」这件事它连说都说不出来。
        "maker_auth" -> {
            val c = ctxRef
            if (c == null) {
                ToolResult("App 上下文不可用，重启一次 App 再试")
            } else when (a.optString("action", "status").lowercase()) {
                "start" -> {
                    if (MakerCli.hasPat(c)) {
                        ToolResult("Maker 已经授权过了（pat.json 在），可以直接生成素材。状态：" + MakerAuth.statusText(c))
                    } else {
                        val u = MakerAuth.start(c)
                        if (u == null) {
                            ToolResult(
                                "授权流程已启动，但 15 秒内还没拿到链接。\n当前状态：" + MakerAuth.statusText(c) +
                                    "\n稍后再用 maker_auth action=status 看一次。"
                            )
                        } else {
                            ToolResult(
                                "已开始 Maker 授权。请把下面这个链接**单独一行、原样**写进你的回复" +
                                    "（不要改写、不要加标点、不要加括号）：\n" + u + "\n\n" +
                                    "并告诉用户：点开链接 → 登录 TapTap → 点「创建 token」→ 回来告诉你一声就行。\n" +
                                    "（这个流程最长会等 10 分钟，用户在浏览器点完就自动完成；期间你可以先做别的。）"
                            )
                        }
                    }
                }
                else -> ToolResult(
                    "Maker 授权状态：" + MakerAuth.statusText(c) +
                        (MakerAuth.url?.let { "\n当前授权链接：" + it } ?: "")
                )
            }
        }

        "ad_guide" -> {
            val doc = File(root, "_shared/AD_KIT.md")
            val kit = File(root, "_shared/adkit.js")
            val sb = StringBuilder()
            sb.append("=== 广告接入官方契约（严格照做，禁止自创降级方案）===\n")
            sb.append(if (doc.exists()) doc.readText().take(26000) else AD_GUIDE_FALLBACK)
            sb.append("\n\n=== 八条硬纪律 ===\n").append(AD_DISCIPLINE)
            if (kit.exists()) {
                sb.append("\n\n=== adkit.js 模板开头（全文用 game_read path=_shared/adkit.js 读）===\n```js\n")
                sb.append(kit.readText().lineSequence().take(30).joinToString("\n"))
                sb.append("\n```\n把 adkit.js 整份复制进游戏工程（发布必须自包含）。")
            } else {
                sb.append("\n\n_shared/adkit.js 不存在：让用户重开一次 App 释放资产。")
            }
            ToolResult(sb.toString())
        }

        "game_patch" -> {
            val fnPath = a.getString("path")
            val f = resolve(a.optString("game"), fnPath)
            require(f.exists()) { "文件不存在: $fnPath" }
            val s = a.getInt("startLine") - 1
            val e = a.getInt("endLine")
            val lines = f.readLines().toMutableList()
            require(s in 0..lines.size) { "startLine 越界（文件共 ${lines.size} 行）" }
            val to = e.coerceIn(s, lines.size)
            repeat(to - s) { lines.removeAt(s) }
            lines.addAll(s, a.getString("content").split("\n"))
            f.writeText(lines.joinToString("\n"))
            ui.reloadGame()
            ToolResult("已替换 ${a.getInt("startLine")}-$e 行，文件现在 ${lines.size} 行，并已热重载")
        }

        "game_launch" -> {
            val id = a.getString("game")
            if (!File(File(root, id), "index.html").exists()) {
                ToolResult("没有 $id/index.html，先用 game_create 创建")
            } else {
                ui.openGame(id)
                ToolResult("已启动 $id")
            }
        }

        "game_reload" -> { ui.reloadGame(); ToolResult("已热重载") }

        "js_eval" -> ToolResult(ui.runJsSync(a.getString("code")).take(6000))

        "game_shot" -> {
            val img = ui.snapshotGameOffscreen(a.optInt("maxWidth", 720))
            if (img == null || img.size < 128) {
                ToolResult(
                    "离屏抓游戏画面没拿到图（${img?.size ?: 0} 字节）。这条路不切页、不动用户屏幕，" +
                        "失败通常是：游戏页还没打开过、该机型不允许离屏画 WebView、或者抓到的是一张纯色图。\n" +
                        "→ 先用 js_eval / console_logs / engine_status 验证逻辑；" +
                        "要眼见为实，可以请用户手动切到「预览」页后再调用 screenshot。"
                )
            } else {
                ToolResult(
                    "已离屏抓到游戏画面（${img.size / 1024} KB）—— 用户屏幕没有被切换、没有被动过。\n" +
                        "看图确认：有没有白屏、布局是否完整、有没有被系统栏压住、素材有没有加载出来。",
                    listOf(img)
                )
            }
        }
        "screenshot" -> {
            // 只截「用户当前停留的那一页」，**绝不切页**。
            // 但要老实告诉模型它截到了什么，否则它会拿聊天界面当游戏画面自欺欺人。
            val pageHint = when (ui.currentTab()) {
                1 -> ""
                0 -> "\n⚠这张图是「对话」页，**不是游戏画面**。" +
                    "要看游戏画面请用 game_shot（离屏抓，不动用户屏幕）；" +
                    "验证逻辑也可用 js_eval / console_logs / engine_status。\n"
                else -> "\n（这张图是「发布」页，不是游戏画面；要看游戏请用 game_shot）\n"
            }
            val w = a.optInt("maxWidth", 720)
            val img = ui.snapshotCss(w)
            if (img == null || img.size < 128) {
                ToolResult(
                    "截图失败：只拿到 ${img?.size ?: 0} 字节。等 1-2 秒再截一次；" +
                        "**不要**据此下结论说「游戏没渲染」。"
                )
            } else {
                val kb = img.size / 1024
                val m = ui.lastShotMeta()
                val map = if (m.size >= 8 && m[0] > 0f) {
                    val scale = m[0]
                    val d = if (m[3] > 0f) m[3] else 1f
                    val iw = (m[1] * scale).toInt()
                    val ih = (m[2] * scale).toInt()
                    val l = m[4] * scale
                    val t = m[5] * scale
                    val r = (m[4] + m[6]) * scale
                    val b = (m[5] + m[7]) * scale
                    val s3 = Math.round(scale * 1000f) / 1000.0
                    "整屏图 ${iw}x${ih}（设备窗口 ${m[1].toInt()}x${m[2].toInt()}，缩放 ${s3}）；" + pageHint +
                        "图中游戏画面在 x ${l.toInt()}~${r.toInt()}、y ${t.toInt()}~${b.toInt()}。" +
                        "要点击游戏里的东西，最省事的是直接把图内像素喂给它：tap(x=图内x, y=图内y, space=\"shot\")；" +
                        "要用 CSS 坐标则是 css=(图内坐标/${'$'}scale-画面左上角)/${'$'}d，d=${d}。"
                } else ""
                val blankWarn = if (ui.lastShotWasBlank()) {
                    val probe = runCatching { ui.runJsSync(SHOT_PROBE_JS, 4000) }.getOrNull().orEmpty()
                    "\n⚠ 这次截出来的图疑似**空白 / 纯色**。两种常见原因：①画面是 WebGL / canvas / " +
                        "视频这类硬件加速内容，像素没抓到；②页面确实还没渲染完。\n" +
                        "**不要**因此断定「引擎没加载 / 画面没渲染 / Three.js 不存在」——这多半是误判。" +
                        "先看下面的页面自检结果，或用 js_eval 核对真实状态，或等 1-2 秒再截一次。\n" +
                        "页面自检：$probe"
                } else if (img.size < 3 * 1024) {
                    " ⚠ 这张图只有 ${img.size} 字节，可能是纯色页（真有问题先看 console_logs）。"
                } else ""
                ToolResult(
                    "截图完成（整屏），${kb} KB（${img.size} 字节）。$map\n" +
                        "请检查：是否白屏、元素是否出屏或被遮挡、文字对比度与重叠、" +
                        "布局是否居中、底部有没有被系统栏/安全区压住。$blankWarn",
                    listOf(img)
                )
            }
        }

        "tap" -> {
            val rx = a.getDouble("x").toFloat()
            val ry = a.getDouble("y").toFloat()
            val space = a.optString("space", "css")
            val conv = if (space == "shot") ui.shotToCss(rx, ry) else null
            if (space == "shot" && conv == null) {
                ToolResult(
                    "还不能用截图坐标点击：先调一次 screenshot（记下换算信息），" +
                        "或者直接给网页 CSS 坐标（space 省略）。"
                )
            } else {
                val x = conv?.get(0) ?: rx
                val y = conv?.get(1) ?: ry
                ui.pointer(x, y, 0)
                Thread.sleep(50)
                ui.pointer(x, y, 1)
                Thread.sleep(120)
                if (conv != null) {
                    val cx = Math.round(x * 10) / 10.0
                    val cy = Math.round(y * 10) / 10.0
                    ToolResult("已点击：截图坐标 ($rx, $ry) → 网页 CSS 坐标 ($cx, $cy)")
                } else {
                    ToolResult("已点击 ($x, $y)")
                }
            }
        }

        "swipe" -> {
            val x1 = a.getDouble("x1").toFloat()
            val y1 = a.getDouble("y1").toFloat()
            val x2 = a.getDouble("x2").toFloat()
            val y2 = a.getDouble("y2").toFloat()
            val ms = a.optInt("ms", 300).coerceIn(50, 3000)
            val steps = 12
            ui.pointer(x1, y1, 0)
            for (i in 1 until steps) {
                ui.pointer(x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, 2)
                Thread.sleep((ms / steps).toLong())
            }
            ui.pointer(x2, y2, 1)
            ToolResult("已从 ($x1, $y1) 滑到 ($x2, $y2)")
        }

        "type_text" -> { ui.inputText(a.getString("text")); ToolResult("已输入文本") }

        "console_logs" -> {
            val lines = ui.consoleTail(a.optInt("lines", 40))
            if (a.optBoolean("clear")) ui.consoleClear()
            ToolResult(if (lines.isEmpty()) "(暂无 console 输出)" else lines.joinToString("\n"))
        }

        "game_libs", "lib_usage" -> ToolResult(Frameworks.catalogText())

        else -> ToolResult("未知工具: $n（可用: ${names().joinToString()}）")
    }
}

/** 新游戏的默认骨架 */
object DefaultGame {

    val HTML: String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<title>${'$'}TITLE</title>
<style>
  html,body{margin:0;height:100%;background:#0e1116;overflow:hidden;
    font-family:-apple-system,"Noto Sans SC",sans-serif;-webkit-user-select:none}
  canvas{display:block;width:100%;height:100%;touch-action:none}
</style>
</head>
<body>
<canvas id="cv"></canvas>
<script src="engine.js"></script>
<script src="game.js"></script>
</body>
</html>
""".trimIndent()

    val JS: String = """
/* 新游戏骨架：移动接住掉下来的方块 */
(function () {
  'use strict';
  var cv = document.getElementById('cv'), ctx = cv.getContext('2d');
  var W, H, DPR;
  function resize() {
    DPR = Math.min(window.devicePixelRatio || 1, 2);
    W = window.innerWidth; H = window.innerHeight;
    cv.width = W * DPR; cv.height = H * DPR;
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
  }
  window.addEventListener('resize', resize); resize();

  var CONFIG = { fallSpeed: 1.6, spawnMs: 700, playerR: 24, itemR: 16 };
  var state = { score: 0, player: { x: W / 2, y: H * 0.8, r: CONFIG.playerR }, items: [] };

  function moveTo(x, y) { state.player.x = x; state.player.y = y; }
  window.addEventListener('pointerdown', function (e) { moveTo(e.clientX, e.clientY); });
  window.addEventListener('pointermove', function (e) { moveTo(e.clientX, e.clientY); });

  setInterval(function () {
    state.items.push({ x: 30 + Math.random() * (W - 60), y: -20, r: CONFIG.itemR });
  }, CONFIG.spawnMs);

  (function loop() {
    ctx.fillStyle = '#0e1116'; ctx.fillRect(0, 0, W, H);
    for (var i = state.items.length - 1; i >= 0; i--) {
      var it = state.items[i]; it.y += CONFIG.fallSpeed;
      if (Math.hypot(it.x - state.player.x, it.y - state.player.y) < it.r + state.player.r) {
        state.items.splice(i, 1); state.score += 10; continue;
      }
      if (it.y > H + 30) { state.items.splice(i, 1); continue; }
      ctx.fillStyle = '#4ade80';
      ctx.beginPath(); ctx.arc(it.x, it.y, it.r, 0, 7); ctx.fill();
    }
    ctx.fillStyle = '#60a5fa';
    ctx.beginPath(); ctx.arc(state.player.x, state.player.y, state.player.r, 0, 7); ctx.fill();
    ctx.fillStyle = '#e6e9ef'; ctx.font = '16px sans-serif';
    ctx.fillText('得分 ' + state.score, 16, 34);
    window.__game = state;
    requestAnimationFrame(loop);
  })();
})();
""".trimIndent()
}