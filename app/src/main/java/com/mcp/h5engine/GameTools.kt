package com.mcp.h5engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 网页 console 日志环形缓冲 */
class LogBuffer(private val max: Int = 500) {

    private val q = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        q.addLast(line)
        while (q.size > max) q.removeFirst()
    }

    @Synchronized
    fun dump(limit: Int): String = q.takeLast(limit.coerceAtLeast(1)).joinToString("\n")

    @Synchronized
    fun clear() {
        q.clear()
    }
}

/** 与正在运行的 WebView 交互，由 MainActivity 实现 */
interface GameUi {
    fun currentGame(): String
    fun launch(gameId: String): String
    fun reload(): String
    fun evalJs(code: String, timeoutMs: Long): String
    fun screenshotBase64(maxWidth: Int): String?
    fun tap(x: Float, y: Float)
    fun text(s: String)
}

/**
 * MCP 工具集 —— AI 就是通过这堆工具在手机上做 H5 游戏的。
 *
 * 设计原则：
 *  - 所有路径限制在 gameRoot 内（防目录穿越）
 *  - 每个工具都返回人/模型可读的文本
 *  - 截图返回标准 MCP image content
 */
class GameTools(
    private val ctx: Context,
    private val gameRoot: File,
    private val ui: GameUi,
    private val logs: LogBuffer
) {

    // ---------------- 工具声明 ----------------

    fun listSpec(): JSONArray {
        val arr = JSONArray()

        fun prop(type: String, desc: String) =
            JSONObject().put("type", type).put("description", desc)

        fun reg(name: String, desc: String, props: JSONObject, required: List<String>) {
            val req = JSONArray()
            required.forEach { req.put(it) }
            arr.put(
                JSONObject()
                    .put("name", name)
                    .put("description", desc)
                    .put(
                        "inputSchema",
                        JSONObject().put("type", "object").put("properties", props).put("required", req)
                    )
            )
        }

        reg("engine_status", "查看引擎状态：工程根目录、当前运行的游戏、已安装游戏列表", JSONObject(), emptyList())

        reg("game_list", "列出所有已创建的游戏", JSONObject(), emptyList())

        reg(
            "game_create",
            "创建一个新的 H5 游戏，自动生成骨架 index.html + game.js",
            JSONObject()
                .put("name", prop("string", "游戏目录名（英文），例如 my-game"))
                .put("template", prop("string", "模板：canvas（默认）/ blank")),
            listOf("name")
        )

        reg(
            "game_write",
            "写入游戏文件（路径相对工程根目录）",
            JSONObject()
                .put("path", prop("string", "例如 my-game/game.js"))
                .put("content", prop("string", "文件内容")),
            listOf("path", "content")
        )

        reg(
            "game_read",
            "读取游戏文件内容",
            JSONObject().put("path", prop("string", "例如 my-game/game.js")),
            listOf("path")
        )

        reg(
            "game_list_files",
            "列出目录下的文件",
            JSONObject().put("path", prop("string", "目录路径，留空为工程根目录")),
            emptyList()
        )

        reg(
            "game_delete",
            "删除文件或目录（递归）",
            JSONObject().put("path", prop("string", "路径")),
            listOf("path")
        )

        reg(
            "game_launch",
            "在手机屏幕上启动某个游戏",
            JSONObject().put("game", prop("string", "游戏目录名")),
            listOf("game")
        )

        reg("game_reload", "热重载当前游戏，改完代码调这个立刻看效果", JSONObject(), emptyList())

        reg(
            "game_eval",
            "在当前运行的游戏里执行 JavaScript 并返回结果，用来验证逻辑/取状态",
            JSONObject()
                .put("code", prop("string", "JS 代码，最后表达式的值会被返回"))
                .put("timeoutMs", prop("integer", "超时毫秒，默认 8000")),
            listOf("code")
        )

        reg(
            "game_console",
            "读取游戏的 console 输出（log/warn/error）",
            JSONObject().put("limit", prop("integer", "最多返回多少条，默认 100")),
            emptyList()
        )

        reg(
            "game_screenshot",
            "截取当前游戏画面，以 PNG 图片返回，用来检查视觉效果",
            JSONObject().put("maxWidth", prop("integer", "最大宽度，默认 720")),
            emptyList()
        )

        reg(
            "game_tap",
            "模拟点击游戏画面（CSS 像素坐标，左上角为原点）",
            JSONObject()
                .put("x", prop("number", "x 坐标"))
                .put("y", prop("number", "y 坐标")),
            listOf("x", "y")
        )

        reg(
            "game_text",
            "向当前聚焦的输入框输入文本",
            JSONObject().put("text", prop("string", "要输入的文本")),
            listOf("text")
        )

        return arr
    }

    // ---------------- 工具派发 ----------------

    fun call(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val a = params.optJSONObject("arguments") ?: JSONObject()

        return try {
            when (name) {
                "engine_status" -> text(status())
                "game_list" -> text(listGames())
                "game_create" -> text(createGame(a))
                "game_write" -> text(writeFile(a))
                "game_read" -> text(readFile(a))
                "game_list_files" -> text(listFiles(a))
                "game_delete" -> text(deletePath(a))
                "game_launch" -> text(ui.launch(a.optString("game")))
                "game_reload" -> text(ui.reload())
                "game_eval" -> text(ui.evalJs(a.optString("code"), a.optLong("timeoutMs", 8000)))
                "game_console" -> text(logs.dump(a.optInt("limit", 100)).ifBlank { "(暂无 console 输出)" })
                "game_screenshot" -> screenshot(a)
                "game_tap" -> {
                    ui.tap(a.optDouble("x").toFloat(), a.optDouble("y").toFloat())
                    text("已点击 (${a.optDouble("x")}, ${a.optDouble("y")})")
                }
                "game_text" -> {
                    ui.text(a.optString("text"))
                    text("已输入文本")
                }
                else -> err("未知工具: $name")
            }
        } catch (t: Throwable) {
            err("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------- 结果包装 ----------------

    private fun text(s: String): JSONObject =
        JSONObject().put(
            "content",
            JSONArray().put(JSONObject().put("type", "text").put("text", s))
        )

    private fun err(s: String): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", s)))
        .put("isError", true)

    private fun screenshot(a: JSONObject): JSONObject {
        val b64 = ui.screenshotBase64(a.optInt("maxWidth", 720))
            ?: return err("截图失败：WebView 未就绪")
        return JSONObject().put(
            "content",
            JSONArray().put(
                JSONObject().put("type", "image").put("data", b64).put("mimeType", "image/png")
            )
        )
    }

    // ---------------- 各项实现 ----------------

    private fun resolve(path: String): File {
        val clean = path.trimStart('/').replace("..", "_")
        return File(gameRoot, clean)
    }

    private fun status(): String {
        val games = gameRoot.listFiles()
            ?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

        return buildString {
            appendLine("工程根目录: ${gameRoot.absolutePath}")
            appendLine("当前游戏: ${ui.currentGame()}")
            appendLine("已装游戏: " + if (games.isEmpty()) "(空)" else games.joinToString(", "))
            appendLine("MCP 服务: 运行中，端口 ${McpServer.DEFAULT_PORT}")
            append("提示: 改完文件记得 game_reload，再用 game_screenshot 看效果")
        }
    }

    private fun listGames(): String {
        val games = gameRoot.listFiles()
            ?.filter { it.isDirectory && it.name != "_shared" } ?: emptyList()
        if (games.isEmpty()) return "还没有任何游戏，用 game_create 创建一个"

        return games.sortedBy { it.name }.joinToString("\n") { d ->
            val entry = File(d, "index.html")
            "- ${d.name}${if (entry.exists()) "" else "  ⚠ 缺少 index.html"}   ${d.absolutePath}"
        }
    }

    private fun createGame(a: JSONObject): String {
        val name = a.optString("name").ifBlank { "game" + System.currentTimeMillis() % 10000 }
        val dir = File(gameRoot, name)
        if (dir.exists()) return "已存在: ${dir.absolutePath}"

        dir.mkdirs()
        File(dir, "index.html").writeText(defaultIndex(name))
        File(dir, "game.js").writeText(defaultGame())

        return "已创建 ${dir.absolutePath}\n" +
            "入口: https://appassets.androidplatform.net/games/$name/index.html\n" +
            "下一步: 用 game_write 改 game.js，然后 game_reload"
    }

    private fun writeFile(a: JSONObject): String {
        val f = resolve(a.getString("path"))
        f.parentFile?.mkdirs()
        f.writeText(a.optString("content"))
        return "已写入 ${f.absolutePath} (${f.length()} bytes)"
    }

    private fun readFile(a: JSONObject): String {
        val f = resolve(a.getString("path"))
        if (!f.exists()) return "文件不存在: ${f.absolutePath}"
        if (f.length() > 512 * 1024) return "文件过大 (${f.length()} bytes)，拒绝读取"
        return f.readText()
    }

    private fun listFiles(a: JSONObject): String {
        val dir = resolve(a.optString("path", ""))
        if (!dir.exists()) return "目录不存在: ${dir.absolutePath}"
        val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) return "(空目录)"
        return files.joinToString("\n") {
            if (it.isDirectory) "[dir]  ${it.name}/" else "${it.name}  (${it.length()} B)"
        }
    }

    private fun deletePath(a: JSONObject): String {
        val f = resolve(a.getString("path"))
        if (!f.exists()) return "不存在: ${f.absolutePath}"
        return if (f.deleteRecursively()) "已删除 ${f.absolutePath}" else "删除失败"
    }

    // ---------------- 模板 ----------------

    private fun defaultIndex(name: String) = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<title>$name</title>
<style>
html,body{margin:0;height:100%;background:#0e1116;color:#e6e9ef;
  font-family:-apple-system,"Noto Sans SC",sans-serif;overflow:hidden}
#cv{position:fixed;inset:0;width:100%;height:100%;display:block;touch-action:none}
</style>
</head>
<body>
<canvas id="cv"></canvas>
<script src="/games/_shared/engine.js"></script>
<script src="game.js"></script>
</body>
</html>
"""

    private fun defaultGame() = """// 由 MCP 工具生成的游戏骨架
const cv = document.getElementById('cv');
const ctx = cv.getContext('2d');
let W = 0, H = 0;

function resize() {
  const d = Math.min(devicePixelRatio || 1, 2);
  W = innerWidth; H = innerHeight;
  cv.width = W * d; cv.height = H * d;
  ctx.setTransform(d, 0, 0, d, 0, 0);
}
addEventListener('resize', resize);
resize();

let t = 0;
function update(dt) {
  t += dt;
  ctx.fillStyle = '#0e1116';
  ctx.fillRect(0, 0, W, H);
  ctx.fillStyle = '#4ade80';
  ctx.beginPath();
  ctx.arc(W / 2 + Math.cos(t) * 80, H / 2 + Math.sin(t * 1.3) * 80, 24, 0, Math.PI * 2);
  ctx.fill();
}

let last = performance.now();
(function frame(now) {
  const dt = Math.min((now - last) / 1000, 0.05);
  last = now;
  update(dt);
  requestAnimationFrame(frame);
})(performance.now());

console.log('game ready');
"""
}