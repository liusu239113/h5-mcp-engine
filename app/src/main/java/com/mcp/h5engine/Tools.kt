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
            fn("lib_usage", "同 game_libs：查看内置框架清单与引用方式", "{}", emptyList())
        )
    }

    /** allow 为 null 或空集合都表示全开 */
    fun specs(allow: Set<String>? = null): List<JSONObject> =
        if (allow.isNullOrEmpty()) allSpecs
        else allSpecs.filter { allow.contains(it.getJSONObject("function").getString("name")) }

    fun names(): List<String> = allSpecs.map { it.getJSONObject("function").getString("name") }

    // ==================== 执行 ====================

    fun call(name: String, argsJson: String): ToolResult = try {
        exec(name, runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject()))
    } catch (t: Throwable) {
        ToolResult("工具执行失败 ${t.javaClass.simpleName}: ${t.message}")
    }

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
            val f = safe(gameDir(a.optString("game")), rel)
            f.parentFile?.mkdirs()
            f.writeText(a.getString("content"))
            ui.reloadGame()
            ToolResult("已写入 $rel（${f.length()} 字节），并已热重载")
        }

        "game_read" -> {
            val g = a.optString("game")
            val p = a.optString("path")
            if (p.isBlank()) {
                val d = gameDir(g)
                if (!d.exists()) ToolResult("游戏目录不存在: ${d.name}")
                else ToolResult("${d.name} 文件清单：\n" + tree(d))
            } else {
                val f = safe(gameDir(g), p)
                ToolResult(
                    if (!f.exists()) "文件不存在: $p"
                    else "```\n${f.readText().take(60000)}\n```"
                )
            }
        }

        "game_patch" -> {
            val fnPath = a.getString("path")
            val f = safe(gameDir(a.optString("game")), fnPath)
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