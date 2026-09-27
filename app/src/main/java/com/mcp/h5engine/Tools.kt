package com.mcp.h5engine

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

    /** kind: 0=down 1=up 2=move */
    fun pointer(x: Float, y: Float, kind: Int)

    fun inputText(text: String)
    fun consoleTail(n: Int): List<String>
    fun consoleClear()
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

            fn("screenshot", "截取当前游戏画面（返回图片）。用它检查 UI 有没有白屏/错位/遮挡",
                """{"maxWidth":{"type":"integer","description":"图片宽度，默认 720"}}""",
                emptyList()),

            fn("tap", "点击画面。坐标是 CSS 像素，与 screenshot 图片坐标一一对应",
                """{"x":{"type":"number"},"y":{"type":"number"}}""",
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
        val extra = if (mcpAllowed) (mcp?.specs() ?: emptyList()) else emptyList()
        return if (extra.isEmpty()) base else base + extra
    }

    fun names(): List<String> = allSpecs.map { it.getJSONObject("function").getString("name") }

    // ==================== 执行 ====================

    fun call(name: String, argsJson: String): ToolResult {
        // MCP 工具（TapTap 等）转给对应服务器；但**默认不放行** —— 模型可能凭记忆猜工具名，
        // 猜中就等于绕过了「默认不给工具」的闸门。
        if (mcpAllowed) {
            mcp?.let { hub -> if (hub.handles(name)) return ToolResult(hub.call(name, argsJson)) }
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
            r == "_uploads" -> File(root, "_uploads")
            r.startsWith("_uploads/") -> safe(File(root, "_uploads"), r.removePrefix("_uploads/"))
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
            File(root, "_skills"),
            File(root, "_uploads"),
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

        "screenshot" -> {
            val w = a.optInt("maxWidth", 720)
            val img = ui.snapshotCss(w)
            if (img == null || img.isEmpty()) {
                ToolResult("截图失败（WebView 可能还没加载完）")
            } else {
                ToolResult(
                    "截图完成，${img.size / 1024} KB。图片坐标 = 屏幕 CSS 像素坐标，" +
                    "可直接用于 tap(x,y)。请检查：是否白屏、元素是否出屏或被遮挡、" +
                    "文字对比度与重叠、布局是否居中、有没有明显错位。",
                    listOf(img)
                )
            }
        }

        "tap" -> {
            val x = a.getDouble("x").toFloat()
            val y = a.getDouble("y").toFloat()
            ui.pointer(x, y, 0)
            Thread.sleep(50)
            ui.pointer(x, y, 1)
            Thread.sleep(120)
            ToolResult("已点击 ($x, $y)")
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