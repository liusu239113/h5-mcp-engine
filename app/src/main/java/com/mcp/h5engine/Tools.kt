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
/**
 * game_validate 用的「页面运行状态」探测脚本。
 *
 * 为什么光看 console 报错不够：**卡在加载页往往一条错都不报**。
 * 那个桌面 skill 的文档里也有同样的坑（它用 `scene_stalled` 区分「静态展示」和「卡死」）。
 * 这里从运行时事实回答三件事：
 *   · 页面加载到哪一步了（readyState）
 *   · 脚本有没有真的执行（有没有全局变量、body 有没有长出来）
 *   · 屏幕上现在显示的是什么文案（卡在「正在进场…」这种一眼就能看出来）
 */
// 用 val 不用 const val：`.trimIndent()` 是函数调用，不是编译期常量
private val VALIDATE_STATE_JS = """
(function(){
  try {
    var out = {};
    out.readyState = document.readyState;
    out.bodyChildren = document.body ? document.body.children.length : -1;
    out.bodyLen = document.body ? document.body.innerHTML.length : -1;
    // 屏幕上的可见文案（截断）：卡在 loading 页时这里会露馅
    var txt = document.body ? (document.body.innerText || '').replace(/\s+/g, ' ').trim() : '';
    out.visibleText = txt.slice(0, 160);
    // canvas 情况：有几个、有没有真的画出东西
    var cvs = [].slice.call(document.querySelectorAll('canvas'));
    out.canvasCount = cvs.length;
    out.canvases = cvs.slice(0, 4).map(function(c){
      return { w: c.width|0, h: c.height|0, visible: !!(c.offsetWidth && c.offsetHeight) };
    });
    // 常见的「引擎挂上没」信号
    out.globals = {};
    ['CONFIG','Career','Screens','App','game','SceneManager','Phaser','THREE','PIXI','engine']
      .forEach(function(k){ try { out.globals[k] = (typeof window[k]); } catch(e){} });
    out.scripts = document.scripts ? document.scripts.length : -1;
    return JSON.stringify(out);
  } catch(e) { return JSON.stringify({ probeError: String(e) }); }
})()
""".trimIndent()

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
        /**
         * 「精简工具定义」打开时要砍掉的工具。
         *
         * 刻意列得很短 —— 砍工具 = 模型看不到就调不了，是不折不扣的能力损失。
         * 这里只放**跟做游戏完全无关**的那几个：管理 App 自己的插件市场和本地 HTTP 服务。
         * 像 js_sandbox / shell_run / workflow_* 这种「看着无关、其实是干活工具」的一律不碰，
         * 宁可少省点 token，也不要出现「昨天还能用，今天这个功能不见了」。
         *
         * 放在 companion 里（不是实例属性）：AgentRunner 是拿 `EngineTools.SLIM_DROP`
         * 静态引用的，实例属性取不到。
         */
        val SLIM_DROP: Set<String> = setOf("toolpkg", "localserver")

        /**
         * 「发布到 TapTap」工具本轮是否放行。
         *
         * 和 mcpAllowed 同样的道理：默认关。用户这轮提到了发布 / 素材 / 审核相关的事，
         * 才把 taptap_publish 的声明发给模型 —— 免得它「顺手」去改线上资料。
         * （提审这一档更严：AI 永远只能做快照+预检，真正的提交必须用户在发布页点。）
         */
        @Volatile
        var publishAllowed: Boolean = false

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
        /**
         * Maker「构建过一次」钩子（MainActivity 注入）。
         *
         * 判型最忌讳看「目录里有什么文件」—— 占位 index.html 骗过一次、
         * .maker-mcp 骗过一次，而它们都是 AI 自己写进去的。
         * 唯一骗不了的信号是：**这个工程到底有没有真构建过 Maker**。
         * 所以直接在「构建」这个动作上下手：maker_build_current_directory
         * 成功返回一次，就把工程钉成 Maker。
         */
        @Volatile
        var onMakerBuiltProject: (() -> Unit)? = null
    }

    // ==================== 工具声明 ====================

    /**
     * 造一条工具声明。
     *
     * ⚠️ **这里必须容错**，不能直接 `JSONObject(props)`。
     *
     * 踩过的坑：`props` 是个手写的 JSON 串，写错一个字符就抛 JSONException。
     * 而 allSpecs 是 by lazy —— 构造列表时抛异常，会**整个工具列表取不出来**，
     * 于是每一条请求都失败，App 表现为「什么消息都发不了」。
     * 一次手滑（我在 description 里写了 `\"` —— Kotlin 三引号**不处理转义**，
     * 那个引号把 JSON 字符串提前闭合了）就把整个 App 搞瘫了。
     *
     * 现在：单条 schema 坏掉只丢**这一个**工具，其余照常发出去，
     * 并且在 logcat 里留一条 error 说明是哪个工具、错在哪。
     */
    private fun fn(name: String, desc: String, props: String, required: List<String>): JSONObject {
        val propsObj = runCatching { JSONObject(props) }.getOrElse { e ->
            android.util.Log.e("hexoraTools", "工具 $name 的参数 schema 不是合法 JSON，已降级为空参数: ${e.message}")
            JSONObject()
        }
        val params = JSONObject()
            .put("type", "object")
            .put("properties", propsObj)
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

            fn("service_logs",
                "【排查服务问题】读取本地服务日志尾部（MCP 服务 / Maker 桥）。" +
                    "服务起不来、工具数为 0、生图报错、授权诡异时，先看它，别靠猜。",
                """{"which":{"type":"string","description":"mcp 或 maker，默认 maker"},
                   "lines":{"type":"integer","description":"可选，返回最后多少行，默认 120"}}""",
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

            fn("code_search",
                "【改代码前先搜】全工程文本搜索（grep / ripgrep 风格）：按关键词或正则找代码，" +
                    "返回「文件:行号: 内容」。比一个个 game_read 翻文件省大量 token。" +
                    "不传 path 就搜整个工程（含 _shared / _uploads / _skills）。",
                """{"query":{"type":"string","description":"关键词或正则表达式"},
                   "path":{"type":"string","description":"可选，限定子目录（相对工程根），如 my_game 或 my_game/js"},
                   "glob":{"type":"string","description":"可选，按文件名后缀过滤，如 .js 或 .html,.css"},
                   "max":{"type":"integer","description":"可选，最多返回多少条匹配，默认 60"}}""",
                listOf("query")),

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

            fn("game_validate",
                "【跑一遍看有没有报错】让游戏真正跑起来、再查它有没有问题。\n" +
                    "做的事：强制热重载 → 等它跑一会儿 → 收集这段时间的 console 报错 → " +
                    "读页面运行状态（是否还在加载页、脚本有没有执行、关键全局变量在不在）。\n" +
                    "**改完代码用它自检，比只看截图靠谱** —— 截图只能看出「画面不对」，" +
                    "它能告诉你「哪一行抛了异常」。\n" +
                    "想额外断言就传 assertions，比如 [[\"typeof Career==='object'\",\"职业系统没定义\"]]。",
                """{"wait_ms":{"type":"integer","description":"重载后等多少毫秒再检查，默认 2500。游戏初始化慢就调大"},
                   "assertions":{"type":"array","description":"可选断言列表，每项 [表达式, 失败说明]"}}""",
                emptyList()),

            fn("game_shot_motion",
                "【判断「动得对不对」】连抓多帧拼成一张网格图 —— 用来看**运动**。\n" +
                    "**单帧判不了运动**：旋转朝哪转、是「左右摆」还是「360°公转」、动画播得对不对、" +
                    "物理轨迹对不对，都必须多帧对比。\n" +
                    "会先把帧图拼成一张 contact sheet 再给你看 —— 这样你**一次就能看到整段过程**，" +
                    "而不是拿到 N 张图自己脑补。\n" +
                    "想让某帧停在一个确定状态（比如把角度冻结成 0/90/180），" +
                    "用 setup 传一段 JS，它会带上帧号执行。",
                """{"frames":{"type":"integer","description":"抓几帧，默认 6，最多 16"},
                   "interval_ms":{"type":"integer","description":"每帧间隔毫秒，默认 120"},
                   "setup":{"type":"string","description":"可选。每帧前执行的 JS，可用变量 i（帧号）。用来把状态冻结到受控值"}}""",
                emptyList()),

            fn("game_shot", "**离屏抓一张「游戏画面」**（不切页、不动用户屏幕、不点击）。" +
                "这是你自己的调试眼：确认游戏画面有没有白屏/错位/被遮挡、素材有没有渲染出来。" +
                "拿不到图会说明原因，**不要**据此断言「游戏坏了」",
                "{\"maxWidth\":{\"type\":\"integer\",\"description\":\"图片最长边，默认 720\"}}",
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
                "【云素材必须先授权 / 支持换号】管理 TapTap Maker 授权（生图 / 音乐 / 音效 / 配音 / 3D / 视频都要它）。" +
                    "action=status 查状态；没授权用 action=start —— 它**立刻返回一个授权链接**，" +
                    "你把链接单独一行、原样贴进回复，让用户点开、登录、点「创建 token」。" +
                    "换号 / 换账号用 action=switch（先清旧凭证再出新链接）；" +
                    "退出登录用 action=logout；用户已经自己在官网建好 token 时用 action=token 并把 token 传进来。" +
                    "不要让用户自己去设置页找入口（App 会在对话里把链接递给他）。",
                """{"action":{"type":"string","description":"status=查状态；start/login=开始授权并拿链接；logout=退出授权；switch=换号（清旧+出新链接）；token=直接写入 token"},"token":{"type":"string","description":"可选。action=token 时要写入的 token 原文"}}""",
                listOf("action")),

            fn("taptap_publish",
                "【发布到 TapTap】管理 TapTap 商店页：查资料缺什么、传素材、改资料、提审。\n" +
                    "action 取值：\n" +
                    "  status     查发布准备情况（缺哪些必填项、能不能提审）—— **动手前先查这个**\n" +
                    "  login      取 TapTap 授权链接（把链接原样贴给用户，别让他自己去设置页翻）\n" +
                    "  apps       列出该账号下的游戏（拿 app_id）\n" +
                    "  modules    读某个资料模块的当前值（如 basic-info / assets-upload）\n" +
                    "  upload     传一张图**并写进指定字段**（path + field 都要传）\n" +
                    "  save       保存资料修改（fields 传 {字段:值}）\n" +
                    "⚠️ **上传成功 ≠ 资料已写好**：TapTap 的素材是**按字段**管理的" +
                    "（icon / screenshots / banner_4 / square_promo_image / trailer …），" +
                    "upload 只把图收进素材库，还必须写进对应字段才会出现在商店页。\n" +
                    "本工具的 upload 已经把这两步串起来了，但你**必须传 field**；" +
                    "不确定有哪些字段、什么规格，先 action=modules 读 assets-upload。\n" +
                    "  submit     提交审核（**高风险**：会走 快照→预检→提交，每一步都先返回给用户确认）\n" +
                    "纪律：save / upload / submit 都是写操作。**先把要改什么、从什么改成什么摊给用户看**，" +
                    "拿到明确同意再执行；submit 尤其如此 —— 提交后版本就进审核流了。",
                """{"action":{"type":"string","description":"status/login/apps/modules/upload/save/submit"},
                   "app_id":{"type":"string","description":"游戏 ID；不传就用发布页当前选中的"},
                   "developer_id":{"type":"string","description":"厂商 ID；一般不用传"},
                   "module":{"type":"string","description":"action=modules 时要读的模块 id"},
                   "path":{"type":"string","description":"action=upload 时的图片路径（工作区相对路径或文件名）"},
                   "field":{"type":"string","description":"action=upload 必填：写进哪个字段。icon / screenshots / banner_4 / square_promo_image 等，以 get-app-module 返回为准"},
                   "op":{"type":"string","description":"action=upload 时的写入方式：replace（默认，整组替换）/ append（截图追加）/ remove（删除）"},
                   "fields":{"type":"object","description":"action=save 时要写入的字段，形如 {字段名: 新值}"}}""",
                listOf("action")),

            fn("memory_save",
                "【长期记忆】把值得跨会话记住的事记下来（用户偏好、项目约定、踩过的坑、固定用法）。" +
                    "每轮会自动把相关记忆注入你的上下文，以后不用再问。只记以后还用得上的，别记一次性闲聊。",
                """{"text":{"type":"string","description":"要记住的内容，一句话说清"},
                   "tags":{"type":"array","items":{"type":"string"},"description":"可选，标签，便于检索"}}""",
                listOf("text")),

            fn("memory_search",
                "【长期记忆】检索以前记下来的事。不确定用户之前说过什么、约定过什么时，先搜一下再回答。",
                """{"query":{"type":"string","description":"关键词，留空则返回最近的记忆"},
                   "limit":{"type":"integer","description":"可选，最多返回几条，默认 8"}}""",
                emptyList()),

            fn("memory_forget",
                "【长期记忆】忘掉某条记忆（按 id 删）。id 可从 memory_search 结果里看到。",
                """{"id":{"type":"string","description":"要删除的记忆 id"}}""",
                listOf("id")),

            fn("workflow_list", "列出所有工作流（id / 名称 / 触发器 / 步骤数）。", "{}", emptyList()),

            fn("workflow_save",
                "【工作流】新建 / 更新一个工作流。nodes 是步骤数组，每步 {id,type,title,params,next}；" +
                    "type=tool（params.tool 指定要调的引擎工具，其余键是它的参数）/ llm / delay（params.ms）。" +
                    "next 指向下一步 id，最后一步不填。",
                """{"id":{"type":"string","description":"可选，更新时传原 id"},
                   "name":{"type":"string"},
                   "trigger":{"type":"string","description":"manual / schedule / event，默认 manual"},
                   "enabled":{"type":"boolean"},
                   "nodes":{"type":"array","items":{"type":"object"}}}""",
                listOf("name", "nodes")),

            fn("workflow_run", "执行一个工作流（按节点顺序跑）。id 从 workflow_list 拿。",
                """{"id":{"type":"string"}}""",
                listOf("id")),

            fn("js_sandbox",
                "【代码执行】在内嵌 JS 沙箱里跑一段脚本并拿到返回值（Rhino 引擎，无网络 / 无文件 IO）。" +
                    "适合纯计算、数据转换、字符串处理。用 return 返回结果。",
                """{"code":{"type":"string","description":"JS 代码，用 return 返回结果"},
                   "timeoutMs":{"type":"integer","description":"可选，超时毫秒，默认 3000"}}""",
                listOf("code")),

            fn("shell_run",
                "【执行系统命令】在当前设备上跑一条命令并拿回输出（经 sh -c 解释；不会自动拿到 root）。" +
                    "用于查设备状态 / 文件 / 进程。危险命令先告诉用户你想干什么。",
                """{"cmd":{"type":"string","description":"要执行的命令"},
                   "timeoutMs":{"type":"integer","description":"可选，超时毫秒，默认 15000"}}""",
                listOf("cmd")),

            fn("toolpkg",
                "【插件包】管理本地 JS 插件：action=list（列出已装）/ install（装：需 id+manifest+code）/ " +
                    "remove（卸：需 id）/ run（跑：需 id，可选 args）/ market（拉远端市场索引）。" +
                    "插件在沙箱里运行（无网络 / 无文件 IO）。",
                """{"action":{"type":"string","description":"list / install / remove / run / market"},
                   "id":{"type":"string","description":"插件 id"},
                   "manifest":{"type":"string","description":"install 时的 manifest.json 内容"},
                   "code":{"type":"string","description":"install 时的入口 JS 代码"},
                   "args":{"type":"string","description":"run 时传给插件的参数"},
                   "indexUrl":{"type":"string","description":"market 时的索引地址，可选"}}""",
                listOf("action")),

            fn("localserver",
                "【本地服务】启停内置 HTTP 服务（web-chat / a2a-server，供电脑或别的 Agent 访问）。" +
                    "action=start（可带 port，默认 8787）/ stop / status。启动后返回 token，" +
                    "调用 /api/* 需带 header X-Token。",
                """{"action":{"type":"string","description":"start / stop / status"},
                   "port":{"type":"integer","description":"start 时端口，默认 8787"}}""",
                listOf("action")),

            fn("ad_guide",
                "【接广告必调】一次给全：TapTap 激励视频官方契约 + adkit.js 模板位置 + 八条硬纪律 + 验收清单。" +
                    "用户只要提到广告 / 激励视频 / 发奖 / 变现，先调它，别凭印象写，更不许用「模拟广告」糊过去",
                "{}", emptyList()),
            fn("maker_project",
                "【Maker（UrhoX）工程必调】管理本地 Maker 工程，不用用户去设置页点任何按钮。" +
                    "action=status 先看状态（会告诉你自带 git 是否就绪、工程里到底有什么）；" +
                    "action=init 拉取/初始化工程（会装 AI dev-kit：CLAUDE.md / examples / templates / urhox-libs，" +
                    "并 clone 远端工程；远端为空时就得到一个可开发的骨架）；" +
                    "action=devkit 更新 dev-kit。" +
                    "用户说「拉取工程 / 初始化工程 / 把 Maker 项目弄到本地 / 装开发文档」时用它。" +
                    "注意：这是个较慢的操作（可能要几十秒到几分钟），调用前先告诉用户你在干什么。",
                """{"action":{"type":"string","description":"status=看状态；init=拉取/初始化工程（装dev-kit）；devkit=更新dev-kit"},"app_id":{"type":"string","description":"可选。Maker app id；不填则让 CLI 列出应用"}}""",
                listOf("action"))
        )
    }

    /**
     * 把 N 帧截图拼成一张网格图（contact sheet）。
     *
     * 为什么非拼不可：**模型看不了动图** —— GIF 读进来只是静态的一帧，
     * 拿到 N 张独立图片也只能一张张看，很难比较「这一帧比上一帧差在哪」。
     * 拼成一张网格，整段过程在一张图里，运动方向 / 轨迹一眼就能看出来。
     * （那套桌面 skill 的文档里也是同一个结论：contact sheet 给 AI 看，GIF 给人看。）
     *
     * 列数按帧数自适应，尽量接近正方形；每格缩到固定宽高，间距留一点，
     * 背景用深色 —— 跟游戏画面区分开，也方便看清边界。
     */
    private fun buildContactSheet(frames: List<ByteArray>, want: Int): ByteArray? = runCatching {
        if (frames.isEmpty()) return@runCatching null

        val cols = when {
            frames.size <= 4 -> 2
            frames.size <= 9 -> 3
            frames.size <= 16 -> 4
            else -> 5
        }
        val rows = (frames.size + cols - 1) / cols

        val cellW = 260
        val cellH = 200
        val pad = 4
        val sheetW = cols * cellW + (cols + 1) * pad
        val sheetH = rows * cellH + (rows + 1) * pad

        val sheet = android.graphics.Bitmap.createBitmap(
            sheetW, sheetH, android.graphics.Bitmap.Config.ARGB_8888
        )
        val cv = android.graphics.Canvas(sheet)
        cv.drawColor(0xFF14141C.toInt())   // 深底：和游戏画面区分开

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }

        for ((i, raw) in frames.withIndex()) {
            val bmp = android.graphics.BitmapFactory
                .decodeByteArray(raw, 0, raw.size) ?: continue
            // 等比缩放 + 居中放进格子
            val scale = minOf(cellW.toFloat() / bmp.width, cellH.toFloat() / bmp.height)
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            val col = i % cols
            val row = i / cols
            val left = pad + col * (cellW + pad) + (cellW - w) / 2
            val top = pad + row * (cellH + pad) + (cellH - h) / 2
            cv.drawBitmap(
                bmp, null,
                android.graphics.Rect(left, top, left + w, top + h),
                paint
            )
            bmp.recycle()
        }

        val out = java.io.ByteArrayOutputStream()
        sheet.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
        sheet.recycle()
        out.toByteArray()
    }.getOrNull()

    /**
     * 把 runJsSync 回传的 JSON 串排一下版。
     *
     * 页面探测脚本返回的是 `JSON.stringify(...)`，再经 evaluateJavascript 包了一层引号，
     * 直接丢给模型是一长条转义串；这里解开、缩进，它才读得下去。
     * 解不开就原样返回（别把信息弄丢）。
     */
    private fun prettyJson(raw: String): String = runCatching {
        val unquoted = org.json.JSONTokener(raw).nextValue()
        val s = when (unquoted) {
            is String -> unquoted
            is JSONObject -> unquoted.toString()
            else -> return@runCatching raw
        }
        org.json.JSONObject(s).toString(2)
    }.getOrDefault(raw)

    /**
     * 把 analyze-app-status 的结果压成人话给模型看。
     *
     * 为什么不直接把原始 JSON 丢过去：那个结构有好几层，还带一堆模型用不上的
     * meta / schema 字段；压成「能不能提审 + 还缺什么」它才知道下一步该干嘛。
     */
    private fun summarizeStatus(d: JSONObject): String {
        val sb = StringBuilder()
        val blockers = d.optJSONArray("blockers")
        val warnings = d.optJSONArray("warnings")
        val suggestions = d.optJSONArray("suggestions")

        sb.append("发布准备情况：").append(
            if (blockers == null || blockers.length() == 0) "**没有阻断项，可以提审**"
            else "还有 ${blockers.length()} 项阻断，现在还不能提审"
        ).append('\n')

        fun dump(title: String, arr: JSONArray?) {
            if (arr == null || arr.length() == 0) return
            sb.append('\n').append(title).append("（").append(arr.length()).append("）：\n")
            for (i in 0 until minOf(arr.length(), 10)) {
                val o = arr.opt(i)
                val txt = when (o) {
                    is JSONObject -> {
                        val m = o.optString("message").ifBlank {
                            o.optString("detail").ifBlank { o.toString() }
                        }
                        val mod = o.optString("module").ifBlank { o.optString("field_id") }
                        if (mod.isBlank()) m else "[$mod] $m"
                    }
                    else -> o?.toString().orEmpty()
                }
                if (txt.isNotBlank()) sb.append("  · ").append(txt.take(220)).append('\n')
            }
        }
        dump("阻断项（必须补）", blockers)
        dump("警告", warnings)
        dump("建议", suggestions)

        sb.append(
            "\n下一步：按清单逐项补。补字段用 action=save（先用 action=modules 读当前值再改），" +
                "补图用 action=upload；补完再 action=status 复查。" +
                "**不要直接提交审核** —— 提审必须由用户在发布页点确认。"
        )
        return sb.toString()
    }

    /**
     * allow 为 null 或空集合都表示全开；技能白名单只管引擎工具。
     *
     * MCP 工具**默认不附带**：只有本轮用户明确表达了相关意图（mcpAllowed=true）才注入声明，
     * 否则模型连这些工具的存在都看不到 —— 从源头杜绝「自动识别一圈 / 顺手发布」。
     */
    fun specs(allow: Set<String>? = null): List<JSONObject> {
        val base0 = if (allow.isNullOrEmpty()) allSpecs
        else allSpecs.filter { allow.contains(it.getJSONObject("function").getString("name")) }
        // 发布工具默认不给：用户这轮提了发布相关的事才注入声明（同 mcpAllowed 的思路）。
        // 它内部对写操作还有二次拦截，见 taptap_publish 的实现。
        val base = if (publishAllowed) base0
        else base0.filterNot { it.getJSONObject("function").getString("name") == "taptap_publish" }
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
        // 【本地优先】maker_project / maker_auth 这些是 App 自己实现的本地工具，
        // 但它们同样以 maker_ 开头。早先的写法会把它们一并丢给 MCP hub，
        // hub 清单里没有 → 回「未知的 MCP 工具：maker_project」，本地实现永远轮不到。
        // 所以先判断是不是本地工具名；是本地就绝不外卖。
        val isLocal = names().contains(name)
        mcp?.let { hub ->
            if (!isLocal && hub.handles(name)) {
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
            if (!isLocal && (name.startsWith("maker_") || name.startsWith("mcp_"))) {
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

    /**
     * 预制 UI 风格包的根目录（`<工程根>/_ui`，启动时从 assets/ui-kits 释放）。
     * AI 用 `game_read path=_ui/kit.json` 看全部主题，`_ui/neon/theme.css` 拿具体配色。
     */
    private val uiKitRoot: File get() = File(root, "_ui")

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
            // 预制的 UI 风格包（随 APK 分发，**不在工程目录里**）。
            // AI 要按题材挑一套主题时必须能读到它 —— 之前这条路是断的：
            // 包在 assets/ui-kits/ 躺着，而 resolve 只认工程目录，
            // 于是 AI 根本不知道有这 10 套主题，只能自己瞎编配色。
            // 返回不存在的 File 而不是 null：resolve 的签名是非空 File，
            // 上层统一按「文件在不在」判断（读的时候会报「文件不存在」，口径一致）。
            r == "_ui" || r.startsWith("_ui/") -> {
                val tail = if (r == "_ui") "" else r.removePrefix("_ui/")
                File(uiKitRoot, tail)
            }
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
            File(root, "_shared"),
            uiKitRoot                          // 预制 UI 风格包：报 theme.css / HOWTO.md 也能找到
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
            // 改之前先量一下行数：写完就能报「行数 120 → 135」这种变化量。
            // 界面那行「已工作」里显示的就是它 —— 用户要能一眼看出这次改动多大。
            val oldLines = if (f.isFile) runCatching { f.readLines().size }.getOrDefault(0) else 0
            val body = a.getString("content")
            f.writeText(body)
            val newLines = body.split('\n').size
            val growth = when {
                oldLines == 0 -> "新建 $newLines 行"
                newLines == oldLines -> "仍是 $newLines 行"
                else -> "行数 $oldLines → $newLines"
            }
            val isShared = rel.trim().trimStart('/').startsWith("_shared/")
            if (!isShared) ui.reloadGame()
            ToolResult(
                "已写入 $rel（$growth，${f.length()} 字节）" +
                    if (isShared) "（共享资产，不需要重载）" else "，并已热重载"
            )
        }

        "memory_save" -> {
            val c = ctxRef
            if (c == null) ToolResult("App 上下文不可用，重启一次 App 再试")
            else {
                val text = a.optString("text").trim()
                if (text.isEmpty()) ToolResult("text 不能为空")
                else {
                    val tags = a.optJSONArray("tags")?.let { ja ->
                        (0 until ja.length()).map { ja.optString(it) }.filter { it.isNotBlank() }
                    } ?: emptyList()
                    val it0 = MemoryStore.save(c, text, tags)
                    ToolResult(if (it0 == null) "没记下来（内容为空）" else "已记住（id=${it0.id}）：${it0.text.take(80)}")
                }
            }
        }

        "memory_search" -> {
            val c = ctxRef
            if (c == null) ToolResult("App 上下文不可用，重启一次 App 再试")
            else {
                val q = a.optString("query").trim()
                val lim = a.optInt("limit", 8).coerceIn(1, 30)
                val hits = MemoryStore.search(c, q, lim)
                ToolResult(
                    if (hits.isEmpty()) "没有相关记忆。" + if (q.isEmpty()) "（目前还没记过任何东西）" else ""
                    else "相关记忆 ${hits.size} 条：\n" + hits.joinToString("\n") {
                        "- [${it.id}] ${it.text}" + if (it.tags.isEmpty()) "" else "  <${it.tags.joinToString(",")}>"
                    }
                )
            }
        }

        "memory_forget" -> {
            val c = ctxRef
            if (c == null) ToolResult("App 上下文不可用，重启一次 App 再试")
            else {
                val id = a.optString("id").trim()
                if (id.isEmpty()) ToolResult("id 不能为空")
                else ToolResult(if (MemoryStore.delete(c, id)) "已忘掉 [$id]" else "没找到 id=[$id] 的记忆")
            }
        }

        "workflow_list" -> {
            val c = ctxRef
            if (c == null) ToolResult("App 上下文不可用，重启一次 App 再试")
            else {
                val l = WorkflowStore.list(c)
                ToolResult(
                    if (l.isEmpty()) "暂无工作流。用 workflow_save 建一个。"
                    else l.joinToString("\n") {
                        "${it.id}  ${it.name}  [${it.trigger}]  ${if (it.enabled) "启用" else "停用"}  ${it.nodes.size}步"
                    }
                )
            }
        }

        "workflow_save" -> {
            val c = ctxRef
            if (c == null) ToolResult("App 上下文不可用，重启一次 App 再试")
            else {
                val name = a.optString("name").trim()
                val na = a.optJSONArray("nodes")
                if (name.isEmpty() || na == null || na.length() == 0) ToolResult("name 和 nodes 都不能为空")
                else {
                    val nodes = mutableListOf<WorkflowStore.Node>()
                    for (i in 0 until na.length()) {
                        val n = na.optJSONObject(i) ?: continue
                        nodes += WorkflowStore.Node(
                            n.optString("id", "n$i"),
                            n.optString("type", "tool"),
                            n.optString("title", "步骤$i"),
                            n.optJSONObject("params") ?: JSONObject(),
                            n.optString("next").ifBlank { null }
                        )
                    }
                    val id = a.optString("id").trim().ifEmpty { WorkflowStore.newId() }
                    WorkflowStore.upsert(
                        c,
                        WorkflowStore.Flow(id, name, a.optString("trigger", "manual"), a.optBoolean("enabled", true), nodes)
                    )
                    ToolResult("已保存工作流 [$id]：$name（${nodes.size} 步）")
                }
            }
        }

        "workflow_run" -> {
            val c = ctxRef
            if (c == null) ToolResult("App 上下文不可用，重启一次 App 再试")
            else {
                val id = a.optString("id").trim()
                if (id.isEmpty()) ToolResult("id 不能为空")
                else ToolResult(
                    WorkflowStore.run(c, id) { node ->
                        when (node.type) {
                            "delay" -> {
                                val ms = node.params.optInt("ms", 500).coerceIn(0, 10000).toLong()
                                Thread.sleep(ms)
                                "等待 ${ms}ms"
                            }
                            "llm" -> "[llm 节点：请在对话中执行] " + node.params.optString("prompt").take(80)
                            else -> {
                                val tn = node.params.optString("tool").trim()
                                if (tn.isEmpty()) "（tool 节点缺少 params.tool）"
                                else {
                                    val p = JSONObject(node.params.toString())
                                    p.remove("tool")
                                    call(tn, p.toString()).text
                                }
                            }
                        }
                    }
                )
            }
        }

        "js_sandbox" -> {
            val code = a.optString("code")
            if (code.isBlank()) ToolResult("code 不能为空")
            else {
                val timeout = a.optInt("timeoutMs", 3000).coerceIn(100, 20000).toLong()
                runCatching { JsSandbox.eval(code, emptyMap(), timeout) }
                    .fold(
                        { ToolResult(it.take(4000)) },
                        { ToolResult("脚本出错：${it.message}") }
                    )
            }
        }

        "shell_run" -> {
            val cmd = a.optString("cmd").trim()
            if (cmd.isEmpty()) ToolResult("cmd 不能为空")
            else {
                val timeout = a.optInt("timeoutMs", 15000).coerceIn(500, 60000).toLong()
                val r = Shell.run(listOf("/system/bin/sh", "-c", cmd), null, emptyMap(), timeout)
                ToolResult(
                    (if (r.ok) "" else "[退出码 ${r.code}]\n") +
                        r.text().ifBlank { "(无输出)" }.take(8000)
                )
            }
        }

        "toolpkg" -> {
            val c = ctxRef
            if (c == null) ToolResult("App 上下文不可用，重启一次 App 再试")
            else when (a.optString("action", "list").lowercase()) {
                "list" -> {
                    val l = ToolPkg.list(c)
                    ToolResult(
                        if (l.isEmpty()) "还没装任何插件。用 action=market 看市场。"
                        else l.joinToString("\n") { "${it.id}  ${it.name} v${it.version} — ${it.desc}" }
                    )
                }
                "install" -> {
                    val id = a.optString("id").trim()
                    val mf = a.optString("manifest").trim()
                    val code = a.optString("code")
                    if (id.isEmpty() || mf.isEmpty() || code.isEmpty()) ToolResult("install 需 id+manifest+code")
                    else {
                        val p = ToolPkg.install(c, id, mf, code)
                        ToolResult("已安装插件 [${p.id}] ${p.name} v${p.version}（入口 ${p.entry}）")
                    }
                }
                "remove" -> {
                    val id = a.optString("id").trim()
                    ToolResult(
                        when {
                            id.isEmpty() -> "remove 需 id"
                            ToolPkg.remove(c, id) -> "已卸载 [$id]"
                            else -> "没找到插件 [$id]"
                        }
                    )
                }
                "run" -> {
                    val id = a.optString("id").trim()
                    ToolResult(
                        if (id.isEmpty()) "run 需 id"
                        else runCatching { ToolPkg.run(c, id, a.optString("args"), 3000) }
                            .getOrElse { "运行失败：${it.message}" }.take(4000)
                    )
                }
                "market" -> ToolResult(
                    runCatching { ToolPkg.market(a.optString("indexUrl")) }
                        .getOrElse { "市场拉取失败：${it.message}" }.take(8000)
                )
                else -> ToolResult("未知 action：${a.optString("action")}（支持 list/install/remove/run/market）")
            }
        }

        "localserver" -> {
            val c = ctxRef
            if (c == null) ToolResult("App 上下文不可用，重启一次 App 再试")
            else when (a.optString("action", "status").lowercase()) {
                "start" -> ToolResult(LocalServerHost.start(c, a.optInt("port", 8787).coerceIn(1024, 65535)))
                "stop" -> ToolResult(LocalServerHost.stop())
                "status" -> ToolResult(LocalServerHost.status())
                else -> ToolResult("未知 action：${a.optString("action")}（start/stop/status）")
            }
        }

        "service_logs" -> {
            val c = ctxRef
            if (c == null) {
                ToolResult("App 上下文不可用，重启一次 App 再试")
            } else {
                val which = a.optString("which", "maker").lowercase()
                val n = a.optInt("lines", 120).coerceIn(10, 1000)
                val f = File(McpRt.rtDir(c), if (which == "mcp") "mcp.log" else "maker.log")
                if (!f.isFile) {
                    ToolResult("没有日志文件 ${f.name}（这个服务可能还没启动过）")
                } else {
                    val lines = runCatching { f.readLines() }.getOrNull()
                    if (lines == null || lines.isEmpty()) {
                        ToolResult("${f.name} 是空的（服务还没产出输出）")
                    } else {
                        val tail = lines.takeLast(n).joinToString("\n")
                        ToolResult(
                            "=== ${f.name}（最后 ${minOf(n, lines.size)} 行 / 共 ${f.length()} 字节）===\n```\n" +
                                tail.take(8000) + "\n```"
                        )
                    }
                }
            }
        }

        "code_search" -> {
            val q = a.optString("query").trim()
            if (q.isEmpty()) {
                ToolResult("query 不能为空")
            } else {
                val sub = a.optString("path").trim()
                val subDir = if (sub.isEmpty()) null else File(root, sub)
                val base = subDir?.takeIf { it.exists() } ?: root
                val globs = a.optString("glob").trim().split(',', ' ')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                val cap = a.optInt("max", 60).coerceIn(1, 300)
                // 先当正则试；写错了（比如 `[`）就退化成纯文本搜，不让工具直接失败
                val re = runCatching { Regex(q, RegexOption.IGNORE_CASE) }
                    .getOrElse { Regex(Regex.escape(q), RegexOption.IGNORE_CASE) }
                val sb = StringBuilder()
                var hits = 0
                val files = base.walkTopDown().filter { it.isFile }
                    .filter { !it.path.contains("/.git/") && it.length() < 2_000_000 }
                    .filter { f -> globs.isEmpty() || globs.any { g -> f.name.endsWith(g.removePrefix("*")) } }
                    .take(600).toList()
                scan@ for (f in files) {
                    val lines = runCatching { f.readLines() }.getOrNull() ?: continue@scan
                    for ((i, ln) in lines.withIndex()) {
                        if (!re.containsMatchIn(ln)) continue
                        val rel = runCatching { f.relativeTo(root).path }.getOrDefault(f.name)
                        sb.append(rel).append(':').append(i + 1).append(": ")
                            .append(ln.trim().take(200)).append('\n')
                        hits++
                        if (hits >= cap) break@scan
                    }
                }
                ToolResult(
                    if (hits == 0) "没有匹配「$q」的内容（共搜了 ${files.size} 个文件）。"
                    else "匹配 $hits 条：\n```\n$sb```"
                )
            }
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

        // Maker 工程：拉取 / 初始化 / dev-kit。全走 App 本体的 MakerCli（AI 自己没有 exec 通道），
        // 自带 git 随运行时释放，所以 taptap-maker init 能真正跑起来。
        "maker_project" -> {
            val c = ctxRef
            if (c == null) {
                ToolResult("App 上下文不可用，重启一次 App 再试")
            } else {
                val act = a.optString("action", "status").lowercase()
                // 工程目录 = 「当前激活的那个游戏」，不能用 root。
                // root 是所有游戏的父目录（.../files/games），拿它当 --target-dir 会把
                // dev-kit / 仓库直接拉进父目录，跟别的游戏搅在一起（上一版就是这个 bug）。
                val cid = runCatching { ui.currentGameId() }.getOrNull().orEmpty()
                val cand = if (cid.isNotBlank()) File(root, cid) else null
                val proj = if (cand != null && cand.isDirectory) cand else root
                val dir = McpRt.rtDir(c)
                val gitBin = File(dir, "gitrt/git")
                when (act) {
                    "init", "devkit" -> {
                        runCatching { McpRt.ensureGitrt(c, dir) }
                        if (!gitBin.isFile) {
                            ToolResult("❌ 自带 git 还没释放（" + gitBin.absolutePath + "）。重启一次 App 会自动释放，然后再让我拉取。")
                        } else {
                            val cmd = if (act == "init") {
                                val id = a.optString("app_id", "").trim()
                                if (id.isEmpty()) listOf("init", "--target-dir", proj.absolutePath, "--skip-mcp-install")
                                else listOf("init", "--target-dir", proj.absolutePath, "--skip-mcp-install", "--app-id", id)
                            } else {
                                listOf("dev-kit", "update", "--target-dir", proj.absolutePath)
                            }
                            val r = MakerCli.run(c, cmd, null, 300_000, proj)
                            ToolResult(
                                (if (r.ok) "✅ " else "❌ ") + "taptap-maker " + cmd.joinToString(" ") +
                                    "\n工程：" + proj.absolutePath + "\n\n" + r.output.takeLast(3000)
                            )
                        }
                    }
                    else -> {
                        val sb = StringBuilder()
                        sb.append("工程目录：").append(proj.absolutePath).append("\n")
                        sb.append("自带 git：").append(if (gitBin.isFile && gitBin.canExecute()) "已就绪" else "未释放（需重启 App 重建软链）").append("\n")
                        sb.append(".project/project.json：").append(File(proj, ".project/project.json").isFile).append("\n")
                        sb.append(".maker-mcp/：").append(File(proj, ".maker-mcp").isDirectory).append("\n")
                        sb.append("urhox-libs（dev-kit）：").append(File(proj, "urhox-libs").isDirectory).append("\n")
                        sb.append("CLAUDE.md：").append(File(proj, "CLAUDE.md").isFile).append("\n")
                        sb.append("scripts/：").append(File(proj, "scripts").isDirectory).append("\n")
                        sb.append("index.html：").append(File(proj, "index.html").isFile).append("\n")
                        sb.append("是 git 仓库：").append(File(proj, ".git").isDirectory).append("\n")
                        ToolResult(sb.toString())
                    }
                }
            }
        }
        // Maker 云能力授权：AI 自己就能把授权链接递给用户，不用用户去设置页翻。
        // 这是内置工具，不受 MCP 准入开关限制 —— 否则「需要授权」这件事它连说都说不出来。
        "taptap_publish" -> {
            val ctx = ctxRef
            if (ctx == null) ToolResult("App 上下文不可用，重启一次 App 再试")
            else if (!TapCli.available(ctx)) {
                ToolResult(
                    "发布工具没随安装包进来（CI 构建时没下到 taptap-cli）。" +
                        "其它功能不受影响；要发布得装一个带它的版本。"
                )
            } else {
                val act = a.optString("action").trim().lowercase()
                // 没传就沿用发布页当前选中的那一个，省得 AI 每次都问
                val dev = a.optString("developer_id").ifBlank { PublishPanel.curDevId }
                val app = a.optString("app_id").ifBlank { PublishPanel.curAppId }
                fun needApp(): ToolResult? =
                    if (app.isBlank()) ToolResult(
                        "还没确定要发布哪个游戏。先 action=apps 列一下（需要先 login），" +
                            "或者让用户在发布页选一个。"
                    ) else null
                fun needIds(): ToolResult? =
                    if (app.isBlank() || dev.isBlank()) ToolResult(
                        "缺少 app_id / developer_id。先 action=login 再 action=apps 拿到它们。"
                    ) else null

                when (act) {
                    "status" -> {
                        needIds()?.let { return it }
                        val r = TapCli.analyzeStatus(ctx, dev, app)
                        if (!r.ok) ToolResult("查发布状态失败：${r.message}\n${r.raw.take(800)}")
                        // blockers/suggestions/warnings 在 data.result 下
                        else ToolResult(summarizeStatus(r.payload))
                    }

                    "login" -> {
                        // 官方给 AI 用的两段式：先 --no-wait 拿链接，再拿 device_code 轮询。
                        // 轮询会阻塞到用户点完（最长 10 分钟），所以直接在这一步里等他。
                        val start = TapCli.authLoginStart(ctx)
                        val url = start.payload.optString("verification_url").ifBlank {
                            Regex("\"verification_url\"\\s*:\\s*\"([^\"]+)\"")
                                .find(start.raw)?.groupValues?.get(1).orEmpty()
                        }
                        val code = start.payload.optString("device_code").ifBlank {
                            Regex("\"device_code\"\\s*:\\s*\"([^\"]+)\"")
                                .find(start.raw)?.groupValues?.get(1).orEmpty()
                        }
                        if (url.isBlank() || code.isBlank()) {
                            ToolResult("没拿到授权链接。CLI 输出：\n" + start.raw.take(700))
                        } else {
                            val done = TapCli.authLoginPoll(ctx, code)
                            ToolResult(
                                if (done.ok)
                                    "TapTap 授权已完成（链接是 $url，用户已点过确认）。" +
                                        "现在可以调 action=apps 拿游戏列表了。"
                                else
                                    "TapTap 授权没完成。把这个链接原样贴给用户，让他点开登录并确认：\n$url\n\n" +
                                        "他确认完你再调一次 action=status 看状态。\n" +
                                        "（CLI 返回：${done.raw.take(400)}）"
                            )
                        }
                    }

                    "apps" -> {
                        val d = dev.ifBlank {
                            // 没给厂商就先自己找
                            val dl = TapCli.developerList(ctx)
                            Regex("\"(?:developer_id|id)\"\\s*:\\s*\"?(\\d+)\"")
                                .find(dl.raw)?.groupValues?.get(1).orEmpty()
                        }
                        if (d.isBlank()) ToolResult("没定位到厂商，先 action=login 授权。")
                        else {
                            val r = TapCli.appList(ctx, d)
                            if (!r.ok) ToolResult("拉游戏列表失败：${r.message}\n${r.raw.take(600)}")
                            else ToolResult("厂商 $d 下的游戏：\n" + r.raw.take(2500))
                        }
                    }

                    "modules" -> {
                        needIds()?.let { return it }
                        val m = a.optString("module").trim()
                        if (m.isBlank()) ToolResult("要读哪个模块？如 basic-info / assets-upload / profile-promotion")
                        else {
                            val r = TapCli.raw(
                                ctx,
                                listOf("app", "get-app-module", "--dev-id", dev, "--app-id", app, "--module", m)
                            )
                            if (!r.ok) ToolResult("读模块失败：${r.message}\n${r.raw.take(600)}")
                            else ToolResult("模块 $m 当前值：\n" + r.raw.take(3000))
                        }
                    }

                    "upload" -> {
                        needIds()?.let { return it }
                        val p = a.optString("path").trim()
                        val field = a.optString("field").trim()
                        if (p.isBlank()) {
                            ToolResult("要传哪张图？path 传工作区里的文件名。")
                        } else if (field.isBlank()) {
                            // 官方铁律：上传成功 ≠ 字段已写入。
                            // 不说清写哪个字段的话，图只是躺在素材库里，商店页看不到。
                            ToolResult(
                                "要写进哪个字段？**上传只是第一步** —— 传完还要 save-changes 写进字段，" +
                                    "否则图只在素材库里、商店页看不到。\n" +
                                    "常用字段：icon（游戏图标）/ screenshots（截图，可多张）/" +
                                    "banner_4（横版封面）/ square_promo_image（方形宣传图）/" +
                                    "trailer（实机视频，走 video 参数）。\n" +
                                    "先调 action=modules 看 assets-upload 模块有哪些字段和规格。"
                            )
                        } else {
                            val f = fuzzyFind(p)
                            if (f == null) ToolResult("找不到文件：$p")
                            else {
                                // ① 传素材库拿 URL
                                val (up, url) = TapCli.uploadImageUrl(ctx, f, dev, app)
                                if (!up.ok || url.isBlank()) {
                                    ToolResult("第一步（传素材库）失败：${up.message}\n${up.raw.take(700)}")
                                } else {
                                    // ② 写进字段 —— 少了这步等于白传。
                                    // 用 saveField：它会自动带上官方强制的 expected（乐观锁），
                                    // 漏了会被 CLI 直接拒（实测踩过）。
                                    val save = TapCli.saveField(
                                        ctx, dev, app,
                                        module = a.optString("module", "assets-upload"),
                                        fieldId = field,
                                        op = a.optString("op", "replace"),
                                        value = url,
                                        idempotencyKey = "ai-" + app + "-" + field + "-" + System.currentTimeMillis()
                                    )
                                    if (save.ok) {
                                        ToolResult(
                                            "已完成两步：\n" +
                                                "  ① 上传 ${f.name} → 素材库（$url）\n" +
                                                "  ② 写入字段 $field\n" +
                                                save.raw.take(400)
                                        )
                                    } else {
                                        ToolResult(
                                            "⚠️ 图传上去了但**字段没写成**：\n" +
                                                "  素材库 URL：$url\n" +
                                                "  写 $field 被拒：${save.message}\n${save.raw.take(700)}\n" +
                                                "→ 用 action=modules 读一下这个字段的 image_spec（可能是尺寸/比例不符）。"
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "save" -> {
                        needIds()?.let { return it }
                        val fields = a.optJSONObject("fields")
                        if (fields == null || fields.length() == 0) {
                            ToolResult("要改什么？fields 传 {\"字段名\":\"新值\"}，字段名照 get-app-module 返回的来。")
                        } else {
                            // 先把「改成什么」摊给用户看 —— 写操作不能悄悄做
                            val preview = StringBuilder("准备写入这些字段：\n")
                            fields.keys().forEach { k ->
                                preview.append("  · ").append(k).append(" = ").append(fields.opt(k)).append('\n')
                            }
                            // ⚠️ 走 saveField（逐个字段），不要拼 `{"fields":{...}}` ——
                            // 官方要的是 `changes[]` 数组，而且每条**必须带 expected**（乐观锁）。
                            // 之前这里传的是旧格式，会直接被拒。
                            val module = a.optString("module", "basic-info")
                            val fails = StringBuilder()
                            var okCount = 0
                            for (k in fields.keys()) {
                                val r = TapCli.saveField(
                                    ctx, dev, app,
                                    module = module,
                                    fieldId = k,
                                    op = "replace",
                                    value = fields.opt(k) ?: "",
                                    idempotencyKey = "save-" + app + "-" + k + "-" + System.currentTimeMillis()
                                )
                                if (r.ok) okCount++ else fails.append("  · ").append(k)
                                    .append("：").append(r.message).append('\n')
                            }
                            if (fails.isEmpty()) {
                                ToolResult(preview.toString() + "\n已保存 $okCount 个字段。")
                            } else {
                                ToolResult(
                                    preview.toString() +
                                        "\n成功 $okCount 个，失败：\n" + fails +
                                        "\n（写字段要求带 expected 乐观锁，本工具已自动带；" +
                                        "还失败通常是值不合规 —— 用 action=modules 看该字段的规格。）"
                                )
                            }
                        }
                    }

                    "submit" -> {
                        needIds()?.let { return it }
                        // 提审是 high-risk-write：这里**只做快照 + 预检**，
                        // 真正的提交必须由用户在发布页点确认（见 PublishPanel.submitFlow）。
                        val snap = TapCli.prepareReviewSnapshot(ctx, dev, app)
                        if (!snap.ok) {
                            ToolResult("生成审核快照失败：${snap.message}\n${snap.raw.take(700)}")
                        } else {
                            val fp = Regex("\"review_fingerprint\"\\s*:\\s*\"([^\"]+)\"")
                                .find(snap.raw)?.groupValues?.get(1).orEmpty()
                            val data = JSONObject()
                                .put("release_schedule", JSONObject().put("kind", "immediate"))
                                .apply { if (fp.isNotBlank()) put("review_fingerprint", fp) }
                            val pre = TapCli.precheckReview(ctx, dev, app, data.toString())
                            ToolResult(
                                "已生成快照并预检（**还没有提交**）。\n" +
                                    "复核指纹：${fp.ifBlank { "（没读到，可能返回结构变了）" }}\n\n" +
                                    (if (pre.ok) "预检通过。" else "预检有问题：${pre.message}") + "\n" +
                                    pre.raw.take(900) +
                                    "\n\n→ 提交是不可撤销的（版本会进审核流），所以**必须由用户在发布页点确认**。" +
                                    "把上面的结果说给用户听，让他去发布页点「更新线上版本」。"
                            )
                        }
                    }

                    else -> ToolResult(
                        "不认识的 action：$act。可用：status / login / apps / modules / upload / save / submit"
                    )
                }
            }
        }

        "maker_auth" -> {
            val c = ctxRef
            if (c == null) {
                ToolResult("App 上下文不可用，重启一次 App 再试")
            } else when (a.optString("action", "status").lowercase()) {
                "start", "login" -> {
                    if (MakerCli.hasPat(c) && a.optString("action").lowercase() != "login") {
                        ToolResult("Maker 已经授权过了（pat.json 在），可以直接生成素材。状态：" + MakerAuth.statusText(c) +
                            "\n要给另一个号授权 → 用 maker_auth action=switch。")
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
                "logout" -> ToolResult(
                    if (MakerAuth.logout(c)) "已退出 Maker 授权（pat.json 已删除）。要重新授权就用 maker_auth action=start。"
                    else "退出时删 pat.json 失败，可能被占用，稍后再试一次。"
                )
                "switch" -> {
                    val u = MakerAuth.switch(c)
                    if (u == null) {
                        ToolResult(
                            "已清掉旧授权并开始换号流程，但 15 秒内还没拿到新链接。\n当前状态：" + MakerAuth.statusText(c) +
                                "\n稍后再用 maker_auth action=status 看一次。"
                        )
                    } else {
                        ToolResult(
                            "已清掉旧授权、开始换号。请把下面这个链接**单独一行、原样**写进你的回复：\n" + u + "\n\n" +
                                "并告诉用户：点开链接 → 登录**要换的那个** TapTap 号 → 点「创建 token」→ 回来告诉你一声。\n" +
                                "（也可以让用户自己在 maker.taptap.cn/pat-tokens 建好 token，用 maker_auth action=token 粘回来。）"
                        )
                    }
                }
                "token" -> ToolResult(MakerAuth.setToken(c, a.optString("token")))
                else -> ToolResult(
                    "Maker 授权状态：" + MakerAuth.statusText(c) +
                        (MakerAuth.url?.let { "\n当前授权链接：" + it } ?: "") +
                        "\n可用操作：status / start / logout / switch / token=..."
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
            val added = a.getString("content").split("\n")
            lines.addAll(s, added)
            f.writeText(lines.joinToString("\n"))
            ui.reloadGame()
            // 报出 -删 +增：这是「这次改动多大」最直观的表达，用户和模型都用得上
            ToolResult(
                "已替换 ${a.getInt("startLine")}-$e 行（-${to - s} +${added.size}），" +
                    "文件现在 ${lines.size} 行，并已热重载"
            )
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

        "game_validate" -> {
            // 跑一遍 + 查错。刻意**只重载一次、只等一次**：
            // 那套桌面 skill 的 validate 也是「跑 N 帧后收错误」，本质一样，
            // 区别只是它跑的是离线进程、我们跑的是用户手机上的真实页面 ——
            // 后者反而更准（就是用户看到的那个环境）。
            val waitMs = a.optInt("wait_ms", 2500).coerceIn(300, 30_000)
            val asserts = a.optJSONArray("assertions")

            // 清掉旧日志：只关心这次重载之后产生的报错
            ui.consoleClear()
            ui.reloadGame()
            runCatching { Thread.sleep(waitMs.toLong()) }

            val sb = StringBuilder()
            sb.append("跑了一遍（重载后等了 ${waitMs}ms），结果如下：\n\n")

            // ① 运行时报错 —— 这是最有价值的部分
            val errs = ui.consoleTail(400).filter {
                val l = it.lowercase()
                l.contains("error") || l.contains("uncaught") || l.contains("failed") ||
                    l.contains("exception") || l.contains("traceback")
            }
            if (errs.isEmpty()) {
                sb.append("✅ 这段时间没有 console 报错\n")
            } else {
                sb.append("❌ 有 ${errs.size} 条报错（最后 ${minOf(errs.size, 12)} 条）：\n")
                errs.takeLast(12).forEach { sb.append("  · ").append(it.take(220)).append('\n') }
            }

            // ② 页面运行状态 —— 光看「没报错」不够，卡在加载页也不报错
            val state = runCatching { ui.runJsSync(VALIDATE_STATE_JS, 8000) }.getOrNull()
            if (!state.isNullOrBlank() && state != "null") {
                sb.append("\n页面状态：\n").append(prettyJson(state).take(1200)).append('\n')
            }

            // ③ 调用方给的断言
            if (asserts != null && asserts.length() > 0) {
                sb.append("\n断言：\n")
                var failed = 0
                for (i in 0 until asserts.length()) {
                    val item = asserts.optJSONArray(i) ?: continue
                    val expr = item.optString(0).trim()
                    val why = item.optString(1, expr)
                    if (expr.isBlank()) continue
                    val r = runCatching {
                        ui.runJsSync("(function(){try{return !!($expr)}catch(e){return 'ERR:'+e.message}})()", 6000)
                    }.getOrNull()?.trim().orEmpty()
                    val ok = r == "true"
                    if (!ok) failed++
                    sb.append(if (ok) "  ✅ " else "  ❌ ").append(why)
                    if (!ok) sb.append("  （求值结果 ").append(r.take(80)).append("）")
                    sb.append('\n')
                }
                sb.append(if (failed == 0) "\n全部断言通过。\n" else "\n有 $failed 条断言没过。\n")
            }

            sb.append(
                "\n怎么用这份结果：报错 → 按栈定位到具体文件行去修；" +
                    "页面状态里 readyState 不是 complete 或还在 loading 文案 → 说明卡在初始化；" +
                    "**别只凭截图判断** —— 截图看不出「哪一行抛了异常」。"
            )
            ToolResult(sb.toString())
        }

        "game_shot_motion" -> {
            // 连抓多帧 → 拼成一张 contact sheet。
            //
            // 为什么必须拼图：模型**看不了动图**（GIF 读进来只是静态的一帧），
            // 拿到 N 张独立图片也只能一张张看，很难比较「这一帧和上一帧差在哪」。
            // 拼成一张网格，整段过程就在一张图里，运动方向/轨迹一眼可见。
            // （那套桌面 skill 的文档里也是这个结论：contact sheet 给 AI，GIF 给人。）
            val n = a.optInt("frames", 6).coerceIn(2, 16)
            val gap = a.optInt("interval_ms", 120).coerceIn(30, 3000)
            val setup = a.optString("setup").trim()

            val shots = ArrayList<ByteArray>(n)
            for (i in 0 until n) {
                // 受控值：让调用方能把状态**冻结**在第 i 个值上，
                // 这样「第几帧是什么样」是可复现的，不依赖真实渲染速度
                if (setup.isNotEmpty()) {
                    runCatching {
                        ui.runJsSync(
                            "(function(){var i=$i;" + setup + "})()", 5000
                        )
                    }
                }
                runCatching { Thread.sleep(gap.toLong()) }
                val img = ui.snapshotGameOffscreen(360) ?: continue
                if (img.size > 128) shots.add(img)
            }

            if (shots.isEmpty()) {
                ToolResult(
                    "一帧都没抓到。先用 game_shot 确认单帧能不能抓到 —— " +
                        "单帧都抓不到的话，多帧更抓不到（原因和 game_shot 一样）。"
                )
            } else {
                val sheet = buildContactSheet(shots, n)
                if (sheet == null) {
                    ToolResult("抓到 ${shots.size} 帧，但拼图失败。", shots)
                } else {
                    ToolResult(
                        "抓了 ${shots.size} 帧、按时间顺序拼成一张网格图（左→右、上→下）。\n" +
                            "看图判断**运动**：\n" +
                            "  · 方向对不对（该左转的在左转吗）\n" +
                            "  · 是「来回摆」还是「整圈公转」—— 看轨迹是不是闭合的\n" +
                            "  · 动画帧有没有跳、有没有卡住不动\n" +
                            "  · 物理轨迹自不自然（抛物线？匀速？越跑越偏？）\n" +
                            "⚠️ 别只看最后一帧就下结论 —— 单帧看不出这些。",
                        listOf(sheet)
                    )
                }
            }
        }

        "game_shot" -> {
            val img = ui.snapshotGameOffscreen(a.optInt("maxWidth", 720))
            if (img == null || img.size < 128) {
                ToolResult(
                    "没抓到画面（${img?.size ?: 0} 字节）。抓图走的是「**从页面内部取 canvas**」，" +
                        "不切页、不改可见性、不动用户屏幕，所以抓不到只会是这两种情况：\n" +
                        "  · 页面里根本没有 canvas（纯 DOM 排版的界面）—— 这种就是抓不到，别重试；\n" +
                        "  · 游戏刚打开/刚热重载，canvas 还没画第一帧 —— 稍等一下再抓一次就行。\n" +
                        "→ **不要请用户切到预览页**（用户明确不许动他正在看的屏幕）。" +
                        "改用 js_eval / console_logs / engine_status 做逻辑验证：" +
                        "把关键状态（当前屏幕名、关键变量、元素是否存在）读出来自检，一样能定位问题。"
                )
            } else {
                ToolResult(
                    "已抓到画面（${img.size / 1024} KB）—— 从页面内部取的 canvas，" +
                        "用户屏幕没有被切换、没有被动过。\n" +
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