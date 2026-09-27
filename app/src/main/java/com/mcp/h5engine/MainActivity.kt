package com.mcp.h5engine

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 宿主：手机上的 AI 游戏工作台。
 *
 *   [对话]  和 AI 聊天，可带图 / 带当前画面截图
 *   [预览]  看游戏画面（带轻量工具条）
 *   [发布]  导出工程 / 看文件 / 看 console / 分享
 *
 * 界面风格：手绘简洁风，浅色为默认，可在设置里切深色。
 * 所有可点区域都有按下反馈；导出结果会明确告诉用户文件在哪、并能一键分享。
 */
class MainActivity : AppCompatActivity(), GameUi {

    // ---------- 视图 ----------
    private lateinit var web: WebView
    private lateinit var bridge: EngineBridge
    private lateinit var chatPage: LinearLayout
    private lateinit var previewPage: LinearLayout
    private lateinit var pubPage: LinearLayout
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var inputEt: EditText
    private lateinit var sendBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var modelBtn: TextView
    private lateinit var attachInfo: TextView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var navChat: LinearLayout
    private lateinit var navPreview: LinearLayout
    private lateinit var navPub: LinearLayout
    private lateinit var rootView: LinearLayout
    private lateinit var stage: FrameLayout
    private lateinit var pubScroll: LinearLayout
    private lateinit var lastExportTv: TextView

    private val main = Handler(Looper.getMainLooper())
    private val logs = LogBuffer()
    /** 当前会话的消息（切会话时整体替换） */
    private var history: MutableList<ChatMsg> = mutableListOf()
    private val sessions = mutableListOf<ChatSession>()
    private var activeSession = 0
    private val attached = java.util.ArrayList<Attach>()      // 待发送区（选了但还没加入对话）
    private val queued = java.util.ArrayList<Attach>()        // 已加入本轮对话的附件
    private val shotBytes = HashMap<String, ByteArray>()      // 图片 rel → 压缩后的 JPEG（多模态用）
    private var pickMode = "media"                            // media / doc / skill
    private lateinit var pendingBox: LinearLayout             // 待发送区容器
    private var fullDlg: Dialog? = null                       // 全屏预览 Dialog
    private lateinit var previewWrapHolder: FrameLayout       // 预览 WebView 的容器（全屏时搬家）
    private lateinit var previewSlot: LinearLayout            // 预览容器原位（退出全屏搬回来）
    private var sessStore: SessionStore? = null

    /** 正在试听的音频（再点一次就停；避免多个播放器叠着响） */
    @Volatile
    private var audioPlayer: android.media.MediaPlayer? = null

    /** 会话库按项目取：项目变了就换库，新项目 = 全新上下文 */
    private fun ss(): SessionStore {
        val cur = sessStore
        if (cur != null && cur.proj == currentGame) return cur
        val s = SessionStore(this, currentGame).also { it.migrateLegacy() }
        sessStore = s
        return s
    }
    private lateinit var sessionBtn: TextView
    private val pendingShots = mutableListOf<ByteArray>()
    private val cfgStore by lazy { AiConfigStore(this) }

    private lateinit var pal: Palette
    private lateinit var gameRoot: File

    @Volatile
    private var currentGame = "demo"

    /** 顶栏左上角「项目」入口 */
    private lateinit var projBtn: TextView

    private var projectDlg: AlertDialog? = null
    private var rootInited = false

    // ---------- 运行状态可视化：折叠思考面板 + 计时 ----------
    private var runHead: TextView? = null

    /** 底部常驻状态行：输入框上方，切后台回来也能看到跑了多久 */
    private var runBar: TextView? = null
    private var guardOn = false
    private var lastNotiSec = -1L
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var runBody: TextView? = null
    private var runTicker: Runnable? = null
    private var runStartAt = 0L
    private var runSteps = 0
    private var bodyExpanded = false
    private var latestActivity = ""
    private var runSeq = 0
    private var runWrote = false

    /** 事件批量合并用的缓冲：忙的时候一秒几十条，逐条建 View 会明显卡 */
    private val pendingEvents = mutableListOf<String>()
    private var flushScheduled = false
    private var scrollPending = false

    /** 是否跟随到底部：用户往上翻就停下（免得边看边被拽走），滑回底部附近自动恢复 */
    private var stickBottom = true
    private var scrollBtn: ImageView? = null
    private var chatWrap: FrameLayout? = null

    /** 预览页：上次加载的项目与目录指纹，用来判断要不要自动重载 */
    private var lastLoadedGame = ""
    private var lastLoadedStamp = 0L

    @Volatile
    private var running = false

    @Volatile
    private var runner: AgentRunner? = null

    private var activeTab = 0

    /** 最近一次导出的 zip，用于「分享 / 复制路径」 */
    @Volatile
    private var lastExport: File? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val small = bytes?.let { shrinkToJpeg(it, 1024, 80) }
            main.post {
                if (small == null) {
                    toast("读取图片失败")
                } else {
                    pendingShots += small
                    updateAttachInfo()
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gameRoot = pickProjectRoot().apply { mkdirs() }
        migrateProjectsIfNeeded()
        seedBundledGames()
        seedSharedRuntime()

        // 恢复上次打开的项目：覆盖安装会强杀进程，冷启动不恢复就永远回到 demo
        val savedGame = cfgStore.lastGame
        if (savedGame.isNotBlank() && savedGame != currentGame &&
            File(gameRoot, savedGame).isDirectory
        ) {
            currentGame = savedGame
        }
        cfgStore.lastRoot = gameRoot.absolutePath

        pal = paletteOf(this, themeModeOf(cfgStore.themeMode))

        // Maker 的授权 / 凭据检查要跑 CLI、读 pat.json，需要 Context。
        // AI 通过内置工具 maker_auth 触发（不受 MCP 准入开关限制）。
        EngineTools.ctxRef = applicationContext

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }

        buildTopBar()
        buildStage()
        buildNav()

        rootView.addView(topHolder!!, LinearLayout.LayoutParams(-1, -2))
        rootView.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        rootView.addView(navHolder!!, LinearLayout.LayoutParams(-1, -2))

        setContentView(rootView)
        applySystemBars()

        setupWebView()
        buildPublishPage()
        showTab(0)

        refreshHeader()
        openGame(currentGame)
        addSystemLine("引擎已就绪 · 当前游戏：$currentGame")
        addSystemLine("直接说「做个贪吃蛇」，或点底部「当前画面」把画面发给 AI 让它改。")
        startMcp()
    }

    // ==================== 内置 MCP（TapTap 小游戏官方服务） ====================

    @Volatile private var mcpBusy = false

    /**
     * 把随 APK 分发的 MCP 运行时释放到私有目录并拉起本地服务，
     * 再把服务暴露的工具全部注册给 AI。全程后台线程，不阻塞冷启动。
     */
    private fun startMcp() {
        if (mcpBusy) return
        mcpBusy = true
        Thread {
            fun say(s: String) = main.post { addSystemLine(s) }
            // 让独立进程里的守护负责 node 的生死（主界面被冻结也不影响它）
            McpGuardService.start(this)

            // 独立进程把 node 拉起来需要几秒，这里带重试，避免「启动瞬间没就绪」被误判失败
            var hub: McpHub? = null
            for (attempt in 1..6) {
                hub = McpBoot.ensure(this) { }
                if (hub != null && hub.size > 0) break
                Thread.sleep(if (attempt == 1) 3000L else 5000L)
            }
            if (hub != null && hub.size > 0) {
                say("MCP 已就绪：${hub.lastInfo}，AI 可直接调用这些工具")
            } else {
                say("MCP：暂未连上，守护会在后台自动重试（也可到 设置 → MCP 服务器 点「刷新状态」）")
            }
            mcpBusy = false
        }.start()
    }

    private var topHolder: LinearLayout? = null
    private var navHolder: LinearLayout? = null

    // ==================== 顶栏 ====================

    /** 拿到「所有文件访问」后，项目就落在 /sdcard/Hexora —— 路径在文件管理器里看得见 */
    private fun pickProjectRoot(): File {
        // 1) 有「所有文件访问」权限：项目落在 /sdcard/Hexora，文件管理器里看得见
        runCatching {
            if (Environment.isExternalStorageManager()) {
                return File(Environment.getExternalStorageDirectory(), "Hexora")
            }
        }
        // 2) 权限判定抖动（覆盖安装后首次启动偶尔是 false）：只要上次的根目录还在就沿用，
        //    否则会静默切到私有目录，用户看到的是「项目全没了，只剩 demo」
        runCatching {
            val last = cfgStore.lastRoot
            if (last.isNotBlank()) {
                val f = File(last)
                if (f.isDirectory && f.canWrite() && f.listFiles()?.isNotEmpty() == true) return f
            }
        }
        // 3) 兜底：没有权限时唯一可写的位置
        return File(filesDir, "games")
    }

    /** 从旧的内部目录一次性迁移项目（只补不覆盖） */
    private fun migrateProjectsIfNeeded() {
        if (!gameRoot.absolutePath.contains("Hexora")) return
        val old = File(filesDir, "games")
        if (!old.isDirectory) return
        for (p in old.listFiles().orEmpty()) {
            if (!p.isDirectory || p.name.startsWith("_")) continue
            val dst = File(gameRoot, p.name)
            if (dst.exists()) continue
            runCatching { copyDir(p, dst) }
        }
    }

    private fun copyDir(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            for (c in src.listFiles().orEmpty()) copyDir(c, File(dst, c.name))
        } else {
            dst.parentFile?.mkdirs()
            src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { persistSessions() }
        // 窗口不可见时 WebView 会节流甚至暂停页面里的 JS 定时器，
        // 这里显式续期一次，尽量让游戏循环继续跑（配合前台服务保活）
        if (::web.isInitialized) {
            runCatching { web.onResume() }
            runCatching { web.resumeTimers() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) {
            runCatching { web.onResume() }
            runCatching { web.resumeTimers() }
        }
        // 授权「所有文件访问」返回后，把项目根目录切到 /sdcard/Hexora
        if (rootInited && Environment.isExternalStorageManager() && !gameRoot.absolutePath.contains("Hexora")) {
            gameRoot = pickProjectRoot().apply { mkdirs() }
            migrateProjectsIfNeeded()
            seedBundledGames()
            seedSharedRuntime()
            runCatching { bridge.setSandbox(File(gameRoot, currentGame)) }
            lastLoadedGame = ""
            refreshHeader()
            addSystemLine("项目根目录已切到 ${gameRoot.absolutePath}")
            toast("项目目录已切换")
        }
        rootInited = true
        // 回到前台顺手续一次：后台期间被系统冻结/回收过就立刻把 MCP 拉回来
        Thread {
            runCatching {
                if (!McpRt.health()) {
                    McpBoot.ensure(this) { }
                    McpGuardService.start(this)
                }
            }
        }.start()
    }

    private fun buildTopBar() {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.navBg)
            setPadding(dp(16), dp(14), dp(12), dp(10))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleTv = TextView(this).apply {
            text = "Hexora"
            setTextColor(pal.text)
            textSize = 17.5f
            letterSpacing = 0.06f
            typeface = MEDIUM
        }

        val settings = TextView(this).apply {
            text = "设置"
            textSize = 12.5f
            letterSpacing = 0.06f
            setTextColor(pal.sub)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 10), 0x14000000)
            setOnClickListener { showSettings() }
        }

        projBtn = TextView(this).apply {
            textSize = 13f
            letterSpacing = 0.04f
            setTextColor(pal.text)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 10), 0x14000000)
            setOnClickListener { showProjects() }
        }
        row.addView(projBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        row.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(settings)
        top.addView(row)

        subTv = TextView(this).apply {
            setTextColor(pal.sub)
            textSize = 11.5f
        }
        top.addView(subTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })

        // 顶部工具栏：会话切换 / 新对话 / 目录 / 代码 —— 对齐 TapTap Maker 的形态
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, 0)
        }
        sessionBtn = TextView(this).apply {
            textSize = 12.5f
            setTextColor(pal.text)
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 10), 0x14000000)
            setOnClickListener { showSessions() }
        }
        tools.addView(sessionBtn, LinearLayout.LayoutParams(0, -2, 1f))
        val newBtn = chipOf(this, pal, "＋新对话", false).apply { setOnClickListener { newChat() } }
        tools.addView(newBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        top.addView(tools)
        updateSessionBtn()
        // 发丝分隔线：顶栏与内容之间一道 1dp 线，比投影更安静
        top.addView(View(this).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) })

        topHolder = top
    }

    // ==================== 三页容器 ====================

    private fun buildStage() {
        stage = FrameLayout(this)
        chatPage = buildChatPage()
        previewPage = buildPreviewPage()

        pubPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
            visibility = View.GONE
        }

        stage.addView(chatPage, FrameLayout.LayoutParams(-1, -1))
        stage.addView(previewPage, FrameLayout.LayoutParams(-1, -1))
        stage.addView(pubPage, FrameLayout.LayoutParams(-1, -1))
    }

    // ==================== 底栏 ====================

    private fun navItemView(label: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
            background = pressable(roundCard(this@MainActivity, pal.navBg, pal.navBg, 0, 0), 0x14000000)
        }
        box.addView(TextView(this).apply {
            text = label
            textSize = 12.5f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(pal.navIdle)
        })
        // 2dp 细下划线做选中指示，比「加粗变色」更克制
        box.addView(View(this).apply {
            visibility = View.INVISIBLE
            setBackgroundColor(pal.navActive)
        }, LinearLayout.LayoutParams(dp(20), dp(2)).apply {
            topMargin = dp(8)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        return box
    }

    private fun buildNav() {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pal.navBg)
        }
        navChat = navItemView("对话")
        navPreview = navItemView("预览")
        navPub = navItemView("发布")
        val lp = LinearLayout.LayoutParams(0, -2, 1f)
        holder.addView(navChat, lp)
        holder.addView(navPreview, LinearLayout.LayoutParams(0, -2, 1f))
        holder.addView(navPub, LinearLayout.LayoutParams(0, -2, 1f))

        navChat.setOnClickListener { showTab(0) }
        navPreview.setOnClickListener { showTab(1) }
        navPub.setOnClickListener { showTab(2) }

        navHolder = holder
    }

    private fun tintNav(box: LinearLayout, active: Boolean) {
        val color = if (active) pal.navActive else pal.navIdle
        for (i in 0 until box.childCount) {
            val c = box.getChildAt(i)
            if (c is TextView) {
                c.setTextColor(color)
                c.typeface = if (active) MEDIUM else Typeface.DEFAULT
            } else {
                c.visibility = if (active) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    /** 多选：素材（图/音/视频）、文档、技能共用一套选择器，按 pickMode 决定 mime 白名单 */
    private val pickMany =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            onPickedMany(uris.toList())
        }

    private fun showTab(tab: Int) {
        activeTab = tab
        chatPage.visibleIf(tab == 0)
        previewPage.visibleIf(tab == 1)
        pubPage.visibleIf(tab == 2)
        tintNav(navChat, tab == 0)
        tintNav(navPreview, tab == 1)
        tintNav(navPub, tab == 2)
        if (tab == 1) ensurePreviewFresh()
        if (tab == 2) refreshExportRow()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        runCatching {
            window.statusBarColor = pal.navBg
            window.navigationBarColor = pal.navBg
            var flags = window.decorView.systemUiVisibility
            flags = if (pal.dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    // ==================== 对话页 ====================

    /**
     * 短肯定句 = 用户同意（用来放行 MCP 写 / 发布类工具）。
     *
     * 「可以」「好」「确认」这种回复本身不含任何领域关键词，
     * 光靠关键词表永远放行不了写接口 —— 用户明明答应了，AI 还是动不了，又是一轮来回。
     */
    private fun shortOK(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t.length > 12) return false
        return listOf("可以", "好的", "好吧", "行", "确认", "同意", "没问题", "ok", "yes", "嗯")
            .any { t.contains(it) }
    }

    private fun buildChatPage(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }

        chatList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(chatList)
            // 手指滑动时实时判断「还在不在底部」
            setOnScrollChangeListener { _, _, _, _, _ -> updateScrollBtn() }
        }

        // 对话区外面套一层 FrameLayout：用来把「回到底部」悬浮按钮叠在右下角
        chatWrap = FrameLayout(this).apply {
            addView(chatScroll, FrameLayout.LayoutParams(-1, -1))
            val fab = ImageView(this@MainActivity).apply {
                setImageDrawable(LineIcon("down", pal.bg, dp(2).toFloat()))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = pressable(roundCard(this@MainActivity, pal.text, pal.text, 21, 0), 0x33000000)
                contentDescription = "回到底部"
                visibility = View.GONE
                setOnClickListener {
                    stickBottom = true
                    scrollChatToBottom(true)
                }
            }
            scrollBtn = fab
            addView(fab, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = dp(14)
                bottomMargin = dp(14)
            })
        }

        // 工作区入口：只留一个（原来「素材/文档/技能/代码」四张卡片和面板里四个页签重复了）
        // 单击 = 打开工作区（默认上次看的那一类），进去后面板里有四个页签可切
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(8))
        }
        toolRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(12), dp(7))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 12), 0x14000000)
            isClickable = true
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(LineIcon("code", pal.sub, dp(2).toFloat()))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "工作区"
                    textSize = 13f
                    setTextColor(pal.text)
                    typeface = MEDIUM
                })
                addView(TextView(this@MainActivity).apply {
                    text = "素材 / 文档 / 技能 / 代码"
                    textSize = 10.5f
                    setTextColor(pal.faint)
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(2) })
            addView(TextView(this@MainActivity).apply {
                text = "❯"
                textSize = 13f
                setTextColor(pal.faint)
            })
            setOnClickListener { openWorkspace(wsTab) }
        }, LinearLayout.LayoutParams(-1, -2))
        page.addView(toolRow)

        // 待发送区：一行一个附件（小图标 + 文件名 + ×）；点条目 = 加入对话
        pendingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(6))
        }
        page.addView(pendingBox)

        page.addView(chatWrap, LinearLayout.LayoutParams(-1, 0, 1f))

        page.addView(View(this).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(pal.navBg)
        }

        runBar = TextView(this).apply {
            textSize = 11.5f
            setTextColor(pal.sub)
            visibility = View.GONE
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        box.addView(runBar)

        attachInfo = TextView(this).apply {
            setTextColor(pal.accent)
            textSize = 11.5f
            visibility = View.GONE
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        box.addView(attachInfo)

        inputEt = EditText(this).apply {
            hint = "说出你想做的游戏，或问 AI 现在的画面哪里不对…"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 5
            setTextColor(pal.text)
            setHintTextColor(pal.faint)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundCard(this@MainActivity, pal.card, pal.border, 14)
        }
        box.addView(inputEt, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val attachBtn = chipOf(this, pal, "＋ 附件", false).apply {
            setOnClickListener {
                AlertDialog.Builder(themed())
                    .setTitle("添加附件")
                    .setItems(
                        arrayOf("素材（图 / 音频 / 视频，可多选）", "文档（txt / md / json / zip / docx…）", "当前游戏画面")
                    ) { _, i ->
                        when (i) {
                            0 -> pickMedia("media")
                            1 -> pickMedia("doc")
                            else -> attachScreenshot()
                        }
                    }
                    .show()
            }
        }
        val shotBtn = chipOf(this, pal, "当前画面", false).apply {
            setOnClickListener { attachScreenshot() }
        }
        modelBtn = TextView(this).apply {
            textSize = 11.5f
            setTextColor(pal.sub)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { showSettings() }
        }
        stopBtn = ghostBtnOf(this, pal, "停止").apply {
            visibility = View.GONE
            setOnClickListener {
                // 立刻掐断在飞的网络请求，不用等 240 秒读超时
                runCatching { runner?.cancel() }
                runHead?.text = "正在停止…"
                toast("已停止")
            }
        }
        sendBtn = primaryBtnOf(this, pal, "发送").apply { setOnClickListener { send() } }

        row.addView(attachBtn)
        row.addView(shotBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        row.addView(modelBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(stopBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        row.addView(sendBtn)
        box.addView(row)

        page.addView(box, LinearLayout.LayoutParams(-1, -2))
        initSessions()
        return page
    }

    // ==================== 气泡 ====================

    /** 段落里识别出来的链接（http/https），供「复制链接 / 打开链接」用 */
    private val LINK_REGEX = Regex("https?://[^\\s\"'<>\\u3002\\uff0c\\uff09\\uff08\\u3010\\u3011]+")

    /** 对话里链接的颜色：蓝 + 可点，和浏览器里一个观感 */
    private val LINK_BLUE = 0xFF2E6BE6.toInt()


    private fun addBubble(text: String, fromUser: Boolean) {
        // 一条消息 = 一个气泡；内部按空行切段，每段自带「复制」，含链接的段落多一个「复制链接」
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(8))
            background = if (fromUser) roundCard(this@MainActivity, pal.userBubble, pal.userBubble, 16, 0)
            else roundCard(this@MainActivity, pal.aiBubble, pal.border, 16)
        }
        val paras = text.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(text) }
        for ((i, seg) in paras.withIndex()) {
            if (i > 0) card.addView(View(this), LinearLayout.LayoutParams(-1, dp(7)))
            card.addView(TextView(this).apply {
                // 链接染成蓝色、点一下直接跳外部浏览器；没有链接的段落才保持可长按选中
                textSize = if (fromUser) 14f else 13.5f
                setTextColor(if (fromUser) pal.userText else pal.aiText)
                linkifySeg(this, seg)
            })
            val links = LINK_REGEX.findAll(seg).map { it.value }.toList()
            val ops = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            ops.addView(iconOp("copy", "复制本段") { copyToClip(seg, "本段") })
            if (links.size == 1) {
                ops.addView(iconOp("open", "用浏览器打开链接") { openExternal(links[0]) })
                ops.addView(iconOp("link", "复制链接地址") { copyToClip(links[0], "链接") })
            } else if (links.isNotEmpty()) {
                ops.addView(iconOp("link", "复制链接（${links.size} 个，点开可选）") { pickLink(links) })
            }
            card.addView(ops, LinearLayout.LayoutParams(-1, -2))
        }
        // 分段多的时候一段段点太烦，末尾再给一个整条复制
        if (paras.size > 1) {
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(iconOp("doc", "复制整条对话（全文）") { copyToClip(text, "全文") })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        chatList.addView(card, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(5)
            bottomMargin = dp(5)
            if (fromUser) leftMargin = dp(46) else rightMargin = dp(30)
        })
        // 自己发的消息永远贴底；AI 的消息只在用户本来就在底部时跟随（免得看一半被拽走）
        if (fromUser) stickBottom = true
        scrollChatToBottom(fromUser)
    }

    /**
     * 段落末尾的小操作：**手绘风图标**（用户要求不要文字按钮）。
     * 触摸目标 ≥ 36dp；长按给一句人话说明，避免纯图标看不懂。
     */
    private fun iconOp(kind: String, desc: String, onTap: () -> Unit): ImageView = ImageView(this).apply {
        setImageDrawable(LineIcon(kind, pal.sub, dp(2).toFloat()))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        minimumWidth = dp(36)
        minimumHeight = dp(36)
        contentDescription = desc
        background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 10), 0x14000000)
        setOnClickListener { onTap() }
        setOnLongClickListener { toast(desc); true }
    }

    private fun copyToClip(v: String, what: String = "") {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hexora", v))
            toast(if (what.isBlank()) "已复制" else "已复制$what")
        }.onFailure { toast("复制失败：${it.message}") }
    }

    /** 一段里有多个链接时，让用户挑一个复制 */
    private fun pickLink(links: List<String>) {
        AlertDialog.Builder(themed())
            .setTitle("选择要复制的链接")
            .setItems(links.map { if (it.length > 64) it.take(64) + "…" else it }.toTypedArray()) { _, i ->
                copyToClip(links[i])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== Maker 授权（AI 直接把链接递给用户，不藏设置页） ====================

    private var authBusy = false
    private var authCardTv: TextView? = null
    private var authListener: ((String, String?) -> Unit)? = null

    /**
     * Maker 报未授权时的自动处理：取授权链接 → 贴进对话 → 顺手打开浏览器。
     *
     * 为什么自动：用户明确要求「我要生图就自动把链接给我」，不许藏在设置页里翻。
     */
    private fun autoMakerAuth() {
        if (authBusy) return
        if (MakerCli.hasPat(this)) {
            addSystemLine("Maker 已授权，继续生成就行。")
            return
        }
        authBusy = true
        ensureAuthListener()
        addSystemLine("需要先授权 TapTap Maker，正在取授权链接…")
        Thread {
            val u = MakerAuth.start(this)
            main.post {
                authBusy = false
                if (u == null) {
                    addSystemLine("没拿到授权链接（" + MakerAuth.statusText(this) + "）。再让我生成一次我就重试。")
                } else {
                    addAuthCard(u)
                    openExternal(u)
                }
            }
        }.start()
    }

    /** 授权结果回来后更新对话里的卡片 */
    private fun ensureAuthListener() {
        if (authListener != null) return
        val l: (String, String?) -> Unit = { st, _ ->
            main.post {
                when (st) {
                    "done" -> {
                        authCardTv?.text = "✅ 授权成功，可以生成素材了"
                        authCardTv = null
                        authBusy = false
                        addSystemLine("✅ TapTap Maker 授权成功。现在直接说「生一张图」就行。")
                        toast("Maker 授权成功")
                    }
                    "failed" -> {
                        authCardTv?.text = "❌ 没成功：" + MakerAuth.lastError.replace("\n", " ").take(120)
                        authBusy = false
                    }
                }
            }
        }
        authListener = l
        MakerAuth.addListener(l)
    }

    /**
     * 授权卡：链接蓝字可点 +「打开授权页 / 复制链接」两个按钮 + 状态行。
     * 全程留在对话里，用户不用去设置页。
     */
    private fun addAuthCard(url: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = roundCard(this@MainActivity, pal.cardAlt, pal.border, 14)
        }
        card.addView(TextView(this).apply {
            text = "需要授权 TapTap Maker：点下面链接 → 登录 → 点「创建 token」"
            textSize = 13f
            setTextColor(pal.aiText)
        })
        card.addView(TextView(this).apply {
            val sp = android.text.SpannableString(url)
            sp.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(w: View) { openExternal(url) }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = LINK_BLUE
                    ds.isUnderlineText = true
                }
            }, 0, url.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sp
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(iconOp("open", "用浏览器打开授权页") { openExternal(url) })
            addView(iconOp("link", "复制授权链接") { copyToClip(url, "授权链接") })
        })
        val st = TextView(this).apply {
            text = "点完「创建 token」这里会自动变成已授权（流程最长等 10 分钟）"
            textSize = 11.5f
            setTextColor(pal.sub)
        }
        card.addView(st)
        chatList.addView(card, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.START
            topMargin = dp(5)
            bottomMargin = dp(5)
            rightMargin = dp(30)
        })
        authCardTv = st
        scrollChatToBottom()
    }

    /** 段落里的链接：染蓝 + 可点（跳外部浏览器）；没链接才保持可长按选中 */
    private fun linkifySeg(tv: TextView, seg: String) {
        val hits = LINK_REGEX.findAll(seg).toList()
        if (hits.isEmpty()) {
            tv.text = seg
            tv.setTextIsSelectable(true)
            return
        }
        val sp = android.text.SpannableString(seg)
        for (m in hits) {
            val u = m.value
            sp.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(w: View) { openExternal(u) }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = LINK_BLUE
                    ds.isUnderlineText = false
                }
            }, m.range.first, m.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sp
        // LinkMovementMethod 与「可选中」冲突：有链接就优先保证点得开
        tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        tv.highlightColor = 0x00000000
        tv.setTextIsSelectable(false)
    }

    /** 跳外部浏览器打开 */
    private fun openExternal(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("打不开链接：${it.message}") }
    }

    // ==================== 素材卡（Maker 生成物直接出现在对话里） ====================

    /** 对话里的素材卡：图片给缩略图、音频给播放条；点一下预览 / 试听 */
    private fun addAssetCard(path: String) {
        val f = File(path)
        if (!f.isFile) return
        val ext = f.extension.lowercase()
        val isImg = ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundCard(this@MainActivity, pal.cardAlt, pal.border, 14)
        }
        if (isImg) {
            card.addView(android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setImageBitmap(decodeThumb(f, 900))
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            card.addView(TextView(this).apply {
                text = "🎵"
                textSize = 30f
                gravity = Gravity.CENTER
            })
        }
        card.addView(TextView(this).apply {
            text = (if (isImg) "🖼 " else "🎵 ") + f.name + " · " + (f.length() / 1024) + " KB"
            textSize = 11.5f
            setTextColor(pal.sub)
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = if (isImg) "点一下看大图" else "点一下试听"
            textSize = 11f
            setTextColor(pal.faint)
            setPadding(0, dp(2), 0, 0)
        })
        card.setOnClickListener { if (isImg) showImageFull(f) else playAudio(f) }
        chatList.addView(card, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.START
            topMargin = dp(6)
            bottomMargin = dp(4)
            rightMargin = dp(30)
        })
        scrollChatToBottom()
    }

    /** 缩略图：按目标边长采样，别把几十 MB 的原图整张读进内存 */
    private fun decodeThumb(f: File, target: Int): android.graphics.Bitmap? = runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1
        while (o.outWidth / (s * 2) >= target || o.outHeight / (s * 2) >= target) s *= 2
        android.graphics.BitmapFactory.decodeFile(
            f.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = s }
        )
    }.getOrNull()

    /** 点开大图：全屏黑底，点一下关掉 */
    private fun showImageFull(f: File) {
        val bm = decodeThumb(f, 2400)
        if (bm == null) {
            toast("图片读取失败")
            return
        }
        val iv = android.widget.ImageView(this).apply {
            setImageBitmap(bm)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
        }
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setContentView(iv, android.view.ViewGroup.LayoutParams(-1, -1))
        iv.setOnClickListener { d.dismiss() }
        d.show()
    }

    /** 试听：点一下放，再点一下停 */
    private fun playAudio(f: File) {
        runCatching {
            val cur = audioPlayer
            if (cur != null && cur.isPlaying) {
                cur.stop()
                cur.release()
                audioPlayer = null
                toast("已停止")
                return
            }
            cur?.release()
            val mp = android.media.MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            mp.start()
            audioPlayer = mp
            mp.setOnCompletionListener {
                runCatching { it.release() }
                if (audioPlayer === it) audioPlayer = null
            }
            toast("正在播放：${f.name}")
        }.onFailure { toast("播放失败：${it.message}") }
    }

    private fun addSystemLine(text: String) {
        val tv = TextView(this).apply {
            setText(text)
            textSize = 11.5f
            setTextColor(pal.faint)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        chatList.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        scrollChatToBottom()
    }

    /** 错误卡片：可读的字号 + 明确的原因 + 一键再试，不再是看不清的灰字 */
    private fun addErrorCard(msg: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = roundCard(this@MainActivity, pal.errBg, pal.errBg, 14, 0)
        }
        card.addView(TextView(this).apply {
            setText("请求失败")
            textSize = 13f
            setTextColor(pal.errText)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            setText(msg)
            textSize = 13f
            setTextColor(pal.errText)
            setPadding(0, dp(5), 0, 0)
            setTextIsSelectable(true)
        })
        card.addView(ghostBtnOf(this, pal, "再试一次").apply {
            setOnClickListener { send() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        chatList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })
        scrollChatToBottom()
    }

    /** 同一帧内多次滚动请求合并：事件密集时不再反复 fullScroll（卡顿主因之一） */
    /**
     * 滚到底。
     *
     * 原来只 post 一次 fullScroll —— 但新加进去的 View 这时**还没测量**，
     * fullScroll 算出来的是「旧内容的底部」，所以看起来老是往上弹、不在底部。
     * 现在：先等 chatList 布局，滚一次，再补一次（字体/图片异步测量完再对一次）。
     *
     * @param force true = 不管用户有没有往上翻，都强制贴底（发消息、切会话用）
     */
    private fun scrollChatToBottom(force: Boolean = false) {
        if (!::chatList.isInitialized || !::chatScroll.isInitialized) return
        if (!force && !stickBottom) return
        if (scrollPending) return
        scrollPending = true
        chatList.post {
            scrollPending = false
            chatScroll.fullScroll(View.FOCUS_DOWN)
            chatList.post {
                chatScroll.fullScroll(View.FOCUS_DOWN)
                updateScrollBtn()
            }
        }
    }

    /** 离底 80dp 内算「贴底」；据此决定新消息是否自动跟随、以及悬浮按钮是否出现 */
    private fun updateScrollBtn() {
        if (!::chatList.isInitialized || !::chatScroll.isInitialized) return
        val gap = chatList.height - chatScroll.height - chatScroll.scrollY
        stickBottom = gap <= dp(80)
        scrollBtn?.visibility = if (stickBottom) View.GONE else View.VISIBLE
    }

    private fun updateAttachInfo() {
        // 待发送区：一行一个附件（小图标 + 文件名 + ×）。点条目 = 加入对话，× = 移除
        if (::pendingBox.isInitialized) {
            pendingBox.removeAllViews()
            for (a in attached) {
                val row = attachRowOf(this, pal, a.kind, a.name, tail = "×", onTail = {
                    attached.remove(a)
                    shotBytes.remove(a.rel)
                    updateAttachInfo()
                })
                row.setOnClickListener { queueAttach(a) }
                pendingBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
        if (attached.isEmpty() && queued.isEmpty() && pendingShots.isEmpty()) {
            attachInfo.visibility = View.GONE
            return
        }
        attachInfo.visibility = View.VISIBLE
        attachInfo.text = buildString {
            if (attached.isNotEmpty()) append("待发送 ${attached.size} 项：直接发送会一起带上，× 移除")
            if (queued.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append("已加入对话 ${queued.size} 项")
            }
            if (pendingShots.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append("图片 ${pendingShots.size} 张")
            }
        }
        attachInfo.setOnClickListener {
            attached.clear()
            queued.clear()
            shotBytes.clear()
            pendingShots.clear()
            updateAttachInfo()
        }
    }

    // ==================== 发送 ====================

    @Volatile
    private var lastUserText = ""

    private fun send() {
        if (running) {
            // 运行中也能直接发：先掐断上一轮，再把新消息发出去
            runCatching { runner?.cancel() }
            toast("已中断上一轮，接着发新的")
        }
        val text = inputEt.text.toString().trim().ifEmpty { lastUserText }
        if (text.isEmpty() && pendingShots.isEmpty() && queued.isEmpty() && attached.isEmpty()) return

        val cfg = cfgStore.active()
        val local = cfg.baseUrl.contains("127.0.0.1") || cfg.baseUrl.contains("localhost")
        if (cfg.apiKey.isBlank() && !local) {
            showSettings()
            toast("先填 ${cfg.provider.label} 的 API Key")
            return
        }

        // 待发送区里的附件：不必再点一次「加入对话」。
        // 用户选完图/文件，直接打字发出去，直觉上就是「这些一起发出去」；
        // 老逻辑只弹一句「点一下条目就能带上」，附件留在原地 —— 用户会以为图丢了
        // （实测反馈：选了图发出去，对话里没有图，下面还挂着待发送）。
        // 现在：发送时自动全部并入本轮（图片进多模态、其余带路径 + 正文）。
        if (attached.isNotEmpty()) {
            for (a in attached.toList()) queueAttach(a, silent = true)
        }

        val images = pendingShots.toList()
        val docs = queued.toList()
        pendingShots.clear()
        queued.clear()
        updateAttachInfo()
        inputEt.setText("")
        lastUserText = text

        // 附件块：图片走多模态（上面的 images），其余把「路径 + 文件名 + 正文」一起给模型，
        // 这样既能 game_read 读原文件，也能只报文件名命中（Tools.fuzzyFind）
        val docBlock = docs.joinToString("") { d ->
            val body = when {
                d.text.isEmpty() ->
                    "\n（二进制内容，App 解析不出正文；可用 game_read path=${d.rel} 读原文件）"
                d.text.length <= 8000 -> "\n```\n${d.text}\n```"
                else ->
                    "\n（正文共 ${d.text.length} 字，太长不整段附上；用 game_read path=${d.rel} 读，" +
                        "或者只报文件名 ${d.name} 也能读到）"
            }
            "\n\n【已上传 ${d.kind}：${d.name}，路径 ${d.rel}】" +
                "\n（可直接 game_read(path=\"${d.rel}\")；只报文件名也能读到）" + body
        }

        addBubble(
            (text.ifEmpty { if (images.isEmpty()) "（看附件）" else "（看这张图）" }) +
                docs.joinToString("") { "\n[附件] ${it.name}" },
            true
        )
        if (images.isNotEmpty()) addSystemLine("（附带 ${images.size} 张图片）")
        if (docs.isNotEmpty()) addSystemLine("（附带 ${docs.size} 个文档）")
        maybeAutoTitle(text)
        persistSessions()

        // 一句「帮我接广告」就自动切到广告技能，不用用户自己去翻设置
        val adWanted = listOf("广告", "激励视频", "发奖", "变现", "adunitid", "adkit", "rewarded")
            .any { text.toLowerCase().contains(it) }

        // ==================== MCP 工具准入（默认关，按意图临时放行） ====================
        // 平时 AI 连这些工具的声明都看不到 —— 从源头杜绝「自动识别一圈」「顺手把游戏发布上线」。
        // 只有你这一轮明确表达了相关意图，才把工具给它；一旦涉及写接口，纪律要求它先问你。
        val mcpWanted = listOf(
            // 广告 / 变现
            "广告", "激励视频", "发奖", "变现", "adunitid", "adkit", "rewarded", "广告位",
            // 排行榜
            "排行榜", "榜单", "leaderboard", "排行",
            // TapTap 平台
            "taptap", "tap", "开发者", "应用信息", "appid", "app_id", "商店", "审核", "上架", "发布", "上传",
            // 素材生产（Maker 云能力）
            "生图", "画图", "出图", "生成图", "生成图片", "图片素材", "立绘", "图标", "贴图", "背景图", "美术",
            "音乐", "bgm", "配乐", "音效", "配音", "语音", "音频素材", "音色",
            "3d", "三维", "模型素材", "视频素材", "生成视频",
            // 制造开发流程
            "素材", "构建", "打包", "预览", "二维码", "测试白名单", "maker"
        ).any { text.lowercase().contains(it) } ||
            // 关键词表天生会漏：用户说「生成一张图」时，上面的「生图 / 生成图」一个都不命中，
            // 结果 AI 连生图工具的声明都看不到，只能跟你说「我没有生图能力」。
            // 所以再补一条「动词 + 可选量词 + 名词」的模式匹配兜底。
            Regex(
                "(生成|画|做|出|来|弄|搞)[^。！？.!?\\n]{0,8}" +
                    "(图|立绘|插画|图标|贴图|背景|美术|音乐|配乐|音效|配音|语音|视频|3d|三维|素材)"
            ).containsMatchIn(text.lowercase()) ||
            // AI 问你「要不要我现在上传 / 发布？」你回一句「可以」——这也得算同意。
            // 这种回复本身不含任何领域关键词，光靠词表永远放行不了写接口，又是一轮来回。
            shortOK(text)
        EngineTools.mcpAllowed = mcpWanted
        if (mcpWanted) {
            addSystemLine("本轮已放行 TapTap / Maker 的写 / 发布类接口（上传、发布、改信息等）；它动手前仍会先跟你确认")
        } else if (EngineTools.mcp != null) {
            // 只读查询与素材生成是常驻的，不需要放行；这里只是日志：写类本轮还锁着
            android.util.Log.i("Hexora", "本轮未放行 MCP 写 / 发布类工具（无明确同意）")
        }

        val skill =
            if (adWanted) SkillPresets.byId("ads") else SkillPresets.byId(cfgStore.skillId)
        if (adWanted && cfgStore.skillId != "ads") {
            addSystemLine("识别到广告需求：本轮自动使用「广告接入（TapTap 激励视频）」技能，按官方契约执行")
        }
        val tools = EngineTools(this, gameRoot)
        val r = AgentRunner(
            cfg = cfg,
            skill = skill,
            tools = tools,
            visionFallback = cfgStore.visionFallback,
            shotDir = File(gameRoot, "_shots")
        ) { ev -> onAgentEvent(ev) }

        runSeq++
        val mySeq = runSeq
        runner = r
        running = true
        runSteps = 0
        runWrote = false
        stopBtn.visibleIf(true)
        startRunCard()

        Thread {
            try {
                r.run(
                    history,
                    (text + docBlock).ifBlank { "请看我发的图片/文档，并按里面的内容改进游戏。" },
                    images
                )
            } catch (t: Throwable) {
                main.post {
                    if (mySeq == runSeq) addErrorCard(AiClient.friendly("${t.javaClass.simpleName}: ${t.message}"))
                }
            } finally {
                main.post { if (mySeq == runSeq) finishRun() }
            }
        }.start()
    }

    private fun onAgentEvent(ev: String) {
        main.post {
            when {
                ev.startsWith("AI: ") -> {
                    flushNow()
                    addBubble(ev.removePrefix("AI: "), false)
                }
                ev.startsWith("[失败]") -> {
                    flushNow()
                    addErrorCard(ev.removePrefix("[失败] 请求失败：").trim())
                }
                ev.startsWith("STEP:") -> {
                    runSteps = ev.removePrefix("STEP:").trim().toIntOrNull() ?: runSteps
                    latestActivity = ""
                }
                ev.startsWith("THINK:") -> {
                    val t = ev.removePrefix("THINK:").trim()
                    if (t.isNotEmpty()) appendThinking("思考：\n" + t.take(1500))
                }
                ev.startsWith("TOOL:") -> {
                    val l = ev.removePrefix("TOOL:").trim()
                    latestActivity = l.take(30)
                    appendThinking(l)
                }
                ev.startsWith("ASSET:") -> {
                    // 素材生成完成：直接在对话里插一张卡，点一下就能预览 / 试听
                    addAssetCard(ev.removePrefix("ASSET:").trim())
                }
                ev.startsWith("AUTHREQ:") -> {
                    // Maker 报未授权：App 自己把授权链接取回来，直接贴进对话（不让用户去设置页翻）
                    flushNow()
                    autoMakerAuth()
                }
                ev == "RELOAD" -> {
                    runWrote = true
                    ensurePreviewFresh()
                }
                ev.startsWith("INFO: ") -> {
                    pendingEvents += ev.removePrefix("INFO: ")
                    scheduleFlush()
                }
                else -> {
                    pendingEvents += ev
                    scheduleFlush()
                }
            }
        }
    }

    // ==================== 运行状态（折叠思考面板 + 计时） ====================

    /** 折叠面板：收起时也能看到「跑了多久 + 第几轮 + 正在干什么」，不会以为它在发呆 */
    private fun startRunCard() {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(10), dp(9))
        }
        val title = TextView(this).apply {
            text = "思考过程"
            textSize = 12.5f
            letterSpacing = 0.06f
            typeface = MEDIUM
            setTextColor(pal.text)
        }
        val stat = TextView(this).apply {
            textSize = 11.5f
            setTextColor(pal.sub)
            setPadding(dp(8), 0, 0, 0)
        }
        val toggle = TextView(this).apply {
            textSize = 11.5f
            setTextColor(pal.accent)
            setPadding(dp(8), 0, dp(2), 0)
        }
        head.addView(title)
        head.addView(stat, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(toggle)

        val body = TextView(this).apply {
            textSize = 11.5f
            setTextColor(pal.sub)
            setPadding(dp(12), 0, dp(12), dp(10))
            visibility = View.VISIBLE
            setTextIsSelectable(true)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundCard(this@MainActivity, pal.card, pal.border, 12)
        }
        card.addView(head)
        card.addView(body)
        chatList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(6)
        })

        runHead = stat
        runBody = body
        bodyExpanded = true
        latestActivity = ""
        runStartAt = SystemClock.elapsedRealtime()
        stat.text = "启动中…"
        toggle.text = "收起"
        runBar?.visibleIf(true)
        runBar?.text = "启动中…"
        askNotiPermission()
        startGuard()
        toggle.setOnClickListener {
            bodyExpanded = !bodyExpanded
            body.visibleIf(bodyExpanded)
            toggle.text = if (bodyExpanded) "收起" else "展开"
            if (bodyExpanded) scrollChatToBottom()
        }
        startTicker()
        scrollChatToBottom()
    }

    private fun startTicker() {
        runTicker?.let { main.removeCallbacks(it) }
        val t = object : Runnable {
            override fun run() {
                if (!running) return
                val ms = SystemClock.elapsedRealtime() - runStartAt
                val line = "${fmtDur(ms)} · 第 ${runSteps.coerceAtLeast(1)} 轮 · " +
                    latestActivity.ifEmpty { "思考中" }
                runHead?.text = line
                runBar?.text = "运行中 · $line"
                val sec = ms / 1000
                if (sec != lastNotiSec) {
                    lastNotiSec = sec
                    updateGuard("正在运行 · $line")
                }
                main.postDelayed(this, 500)
            }
        }
        runTicker = t
        main.postDelayed(t, 200)
    }

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s} 秒"
            s < 3600 -> "${s / 60} 分 ${s % 60} 秒"
            else -> "${s / 3600} 时 ${(s % 3600) / 60} 分"
        }
    }

    private fun finishRun() {
        running = false
        runTicker?.let { main.removeCallbacks(it) }
        stopBtn.visibleIf(false)
        val ms = SystemClock.elapsedRealtime() - runStartAt
        runHead?.text = "共用时 ${fmtDur(ms)} · ${runSteps} 轮 · 已结束"
        runBar?.text = "上次运行 · 共用时 ${fmtDur(ms)} · ${runSteps} 轮 · 已结束"
        latestActivity = ""
        lastNotiSec = -1L
        stopGuard()
        if (runWrote) ensurePreviewFresh()
    }

    // ==================== 会话管理（像 Maker 那样开多个对话） ====================

    private fun newId(): String = "s" + System.currentTimeMillis()

    /** 载入会话（没有就建一个），并渲染出历史气泡 */
    private fun initSessions() {
        reloadSessionsForProject()
    }

    /** 装载当前项目的会话列表（切项目后调用：界面上的上下文整体换掉，互不串味） */
    private fun reloadSessionsForProject() {
        if (!::chatList.isInitialized) return
        val loaded = ss().load().ifEmpty { mutableListOf(ChatSession(newId(), "新对话")) }
        sessions.clear()
        sessions.addAll(loaded)
        activeSession = 0
        history = sessions[0].msgs
        chatList.removeAllViews()
        renderHistory()
        updateSessionBtn()
    }

    private fun updateSessionBtn() {
        if (!::sessionBtn.isInitialized) return
        val t = sessions.getOrNull(activeSession)?.title ?: "新对话"
        sessionBtn.text = "会话 · $t ▾"
    }

    private fun persistSessions() {
        sessions.getOrNull(activeSession)?.let {
            it.msgs = history
            it.updated = System.currentTimeMillis()
        }
        ss().save(sessions)
    }

    private fun newChat() {
        persistSessions()
        val s = ChatSession(newId(), "新对话")
        sessions.add(0, s)
        activeSession = 0
        history = s.msgs
        chatList.removeAllViews()
        addSystemLine("已开新对话：上下文互不干扰；旧对话可从「会话」里切回来")
        updateSessionBtn()
        persistSessions()
    }

    private fun switchTo(i: Int) {
        if (i == activeSession) return
        persistSessions()
        activeSession = i
        history = sessions[i].msgs
        renderHistory()
        updateSessionBtn()
        toast("已切到「${sessions[i].title}」")
    }

    private fun deleteSession(i: Int) {
        if (sessions.size <= 1) {
            toast("至少留一个会话")
            return
        }
        sessions.removeAt(i)
        if (activeSession >= sessions.size) activeSession = sessions.size - 1
        history = sessions[activeSession].msgs
        renderHistory()
        updateSessionBtn()
        persistSessions()
    }

    private fun renderHistory() {
        if (!::chatScroll.isInitialized) return
        // 关键：**不要在已挂载的列表上「先清空、再逐条加」** —— 那样会空出一帧，
        // 表现出来就是「对话莫名消失一下」。改成先在离屏的新列表里拼好，
        // 最后一次换上去（同一帧内完成，不会闪）。
        val old = chatList
        val fresh = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        chatList = fresh
        for (m in history) {
            when (m.role) {
                "user" -> addBubble(m.text ?: "（图片）", true)
                "assistant" -> if (!m.text.isNullOrBlank()) addBubble(m.text, false)
                else -> addSystemLine(m.text ?: "")
            }
        }
        chatScroll.removeAllViews()
        chatScroll.addView(fresh)
        old.removeAllViews()
        stickBottom = true
        scrollChatToBottom(true)
    }

    /** 第一条用户消息顺便当标题，省得一堆「新对话」 */
    private fun maybeAutoTitle(t: String) {
        val s = sessions.getOrNull(activeSession) ?: return
        if (s.title == "新对话" && t.isNotBlank()) {
            s.title = t.replace("\n", " ").take(14)
            updateSessionBtn()
        }
    }

    private fun showSessions() {
        persistSessions()
        val labels = sessions.mapIndexed { i, s ->
            (if (i == activeSession) "当前 · " else "") + s.title + "（${s.msgs.size} 条）"
        }.toTypedArray()
        AlertDialog.Builder(themed())
            .setTitle("会话（${sessions.size}）")
            .setItems(labels) { _, i -> switchTo(i) }
            .setPositiveButton("＋新对话") { _, _ -> newChat() }
            .setNeutralButton("重命名") { _, _ ->
                val et = EditText(this).apply {
                    setText(sessions[activeSession].title)
                    setSelection(text.length)
                }
                AlertDialog.Builder(themed())
                    .setTitle("重命名会话")
                    .setView(et)
                    .setPositiveButton("好") { _, _ ->
                        sessions[activeSession].title = et.text.toString().take(24)
                        updateSessionBtn()
                        persistSessions()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("删除当前") { _, _ -> deleteSession(activeSession) }
            .show()
    }

    // ==================== 工作区：素材 / 文档 / 技能 ====================

    /** 入口卡 → 选择器：素材 = 图/音/视频，文档 = 文本类，技能 = 用户自传的 skill */
    private fun pickMedia(mode: String) {
        pickMode = mode
        val mimes = when (mode) {
            "doc" -> arrayOf(
                "text/*", "application/json", "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/zip", "application/octet-stream"
            )
            "skill" -> arrayOf("text/*", "application/json", "application/javascript")
            else -> arrayOf("image/*", "audio/*", "video/*")
        }
        runCatching { pickMany.launch(mimes) }
            .onFailure { toast("这台机器没有可用的文件选择器") }
    }

    private fun onPickedMany(uris: List<Uri>) {
        val mode = pickMode
        Thread {
            val ok = mutableListOf<Attach>()
            var bad = 0
            for (u in uris) {
                val a = runCatching { readAttach(u, mode) }.getOrNull()
                if (a == null) bad++ else ok += a
            }
            main.post {
                if (ok.isNotEmpty()) {
                    attached.addAll(ok)
                    toast("已加入待发送区 ${ok.size} 个文件")
                }
                if (bad > 0) toast("有 $bad 个文件读不了（可能没给读取权限）")
                // 上传完刷新工作区（常驻列表立刻能看到新文件）
                updateAttachInfo()
                if (wsDlg != null) renderWs()
            }
        }.start()
    }

    /**
     * 把选中的文件落进工程，并尽量提出正文：
     *   素材 → 工程根/_uploads/media/
     *   文档 → 工程根/_uploads/doc/
     *   技能 → 工程根/_skills/
     * 图片额外压一份小图存进 shotBytes，走多模态当图片发给模型。
     */
    private fun readAttach(uri: Uri, mode: String): Attach {
        val name = (queryName(uri) ?: "file_${System.currentTimeMillis()}").replace("/", "_")
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        if (bytes.isEmpty()) throw IllegalStateException("空文件")
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
        val kind = when {
            mode == "doc" -> "doc"
            mode == "skill" -> "skill"
            mime.startsWith("audio") -> "audio"
            mime.startsWith("video") -> "video"
            else -> "image"
        }
        val dir = when (kind) {
            "doc" -> File(gameRoot, "_uploads/doc")
            "skill" -> File(gameRoot, "_skills")
            else -> File(gameRoot, "_uploads/media")
        }.apply { mkdirs() }
        val dst = File(dir, name)
        dst.writeBytes(bytes)
        val rel = dst.relativeTo(gameRoot).path
        val text = if (kind == "image" || kind == "audio" || kind == "video") ""
        else extractText(name, bytes).take(60000)
        if (kind == "image") {
            runCatching { shrinkToJpeg(bytes, 1024, 80) }.getOrNull()?.let { shotBytes[rel] = it }
        }
        return Attach(name, rel, kind, text)
    }

    /** 加入对话：图片并入多模态，其余在本轮消息里带上路径 + 正文。
     *  silent=true 时用于「发送时自动并入」，不刷一条系统提示（避免噪音）。 */
    private fun queueAttach(a: Attach, silent: Boolean = false) {
        if (!attached.remove(a)) return
        if (a.kind == "image") {
            // 优先用内存里已压缩好的那份；没有就现读文件再压
            // （readAttach 存的是 shotBytes[rel]，rel 是相对 gameRoot 的路径，别当绝对路径用）
            val b = shotBytes[a.rel] ?: runCatching {
                val f = File(a.rel).let { if (it.isAbsolute) it else File(gameRoot, a.rel) }
                if (f.isFile) shrinkToJpeg(f.readBytes(), 1024, 80) else null
            }.getOrNull()
            if (b != null && b.isNotEmpty()) pendingShots += b
        }
        queued += a
        if (!silent) addSystemLine("已加入对话：${a.kind}「${a.name}」")
        updateAttachInfo()
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

    private fun extractText(name: String, bytes: ByteArray): String {
        val n = name.lowercase()
        val textExt = listOf(
            ".txt", ".md", ".markdown", ".json", ".js", ".ts", ".html", ".htm", ".css",
            ".csv", ".log", ".xml", ".yml", ".yaml", ".lua", ".py", ".kt", ".java",
            ".ini", ".toml", ".sh", ".sql", ".c", ".cpp", ".h"
        )
        return when {
            textExt.any { n.endsWith(it) } -> String(bytes, Charsets.UTF_8)
            n.endsWith(".docx") -> runCatching {
                java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
                    var e = zin.nextEntry
                    var xml = ""
                    while (e != null) {
                        if (e.name == "word/document.xml") {
                            xml = zin.readBytes().toString(Charsets.UTF_8)
                            break
                        }
                        e = zin.nextEntry
                    }
                    xml.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                }
            }.getOrDefault("")
            n.endsWith(".zip") -> runCatching {
                java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
                    val names = mutableListOf<String>()
                    var e = zin.nextEntry
                    while (e != null) {
                        names += e.name
                        if (names.size >= 300) break
                        e = zin.nextEntry
                    }
                    "（zip 内条目）\n" + names.joinToString("\n")
                }
            }.getOrDefault("")
            n.endsWith(".pdf") ->
                "（PDF 不能直接解析正文：有要参考的内容请复制成 txt/md 再上传；" +
                    "原文件已存到 _uploads/，路径见上）"
            else -> ""
        }
    }

    // ==================== 看 / 改代码（对话页那排图标） ====================

    /** 代码图标：直接开 index.html（没有就 game.js，再没有就第一个文件） */
    private fun editMainCode() {
        val dir = File(gameRoot, currentGame)
        val f = listOf("index.html", "game.js").map { File(dir, it) }
            .firstOrNull { it.exists() }
            ?: dir.walkTopDown().firstOrNull { it.isFile }
        if (f == null) {
            toast("这个项目还没有文件")
            return
        }
        openFileEditor(f, dir)
    }

    private fun showCode() {
        Thread {
            val dir = File(gameRoot, currentGame)
            val files = dir.walkTopDown().filter { it.isFile }
                .filterNot { it.path.contains("/_shots/") }
                .sortedBy { it.path }.take(200).toList()
            main.post {
                if (files.isEmpty()) {
                    toast("这个项目还没有文件")
                    return@post
                }
                // 与素材列表同一套行样式：图标 + 文件名 + ＋（添加到对话），点行 = 打开编辑
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                }
                for (f in files) {
                    val rel = f.relativeTo(dir).path
                    val row = fileRow(
                        name = rel,
                        kind = kindOfFile(rel),
                        onOpen = { openFileEditor(f, dir) },
                        onAdd = {
                            attached += Attach(rel, rel, "code")
                            toast("已加入待发送区：$rel")
                            updateAttachInfo()
                        }
                    )
                    col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                }
                AlertDialog.Builder(themed())
                    .setTitle("$currentGame · 文件（${files.size}）")
                    .setView(ScrollView(this).apply { addView(col) })
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }.start()
    }

    /** 文件行：小图标 + 文件名 + 可选「＋」。素材列表和代码列表共用同一套样式 */
    private fun fileRow(
        name: String,
        kind: String,
        onOpen: () -> Unit,
        onAdd: (() -> Unit)? = null
    ): LinearLayout {
        val row = attachRowOf(this, pal, kind, name, tail = if (onAdd != null) "＋" else null, onTail = onAdd)
        row.setOnClickListener { onOpen() }
        return row
    }

    /** 按后缀猜图标：代码页和素材列表都靠它选图标 */
    private fun kindOfFile(name: String): String = when {
        name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
            name.endsWith(".jpeg", true) || name.endsWith(".gif", true) ||
            name.endsWith(".webp", true) || name.endsWith(".bmp", true) -> "image"
        name.endsWith(".mp3", true) || name.endsWith(".wav", true) ||
            name.endsWith(".ogg", true) || name.endsWith(".m4a", true) -> "audio"
        name.endsWith(".mp4", true) || name.endsWith(".webm", true) ||
            name.endsWith(".mov", true) -> "video"
        name.endsWith(".json", true) || name.endsWith(".js", true) ||
            name.endsWith(".ts", true) || name.endsWith(".html", true) ||
            name.endsWith(".css", true) || name.endsWith(".kt", true) -> "code"
        else -> "doc"
    }

    private fun openFileEditor(f: File, base: File) {
        val body = runCatching { f.readText() }.getOrDefault("")
        val et = EditText(this).apply {
            setText(body)
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(pal.text)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        AlertDialog.Builder(themed())
            .setTitle(f.relativeTo(base).path)
            .setView(ScrollView(this).apply { addView(et) })
            .setPositiveButton("保存") { _, _ ->
                runCatching {
                    f.writeText(et.text.toString())
                    reloadGame()
                    toast("已保存并热重载")
                }.onFailure { toast("保存失败：${it.message}") }
            }
            .setNeutralButton("复制全文") { _, _ -> copyToClipboard(et.text.toString()) }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ==================== 拉取厂商真实模型列表 ====================

    /** 预设永远追不上厂商改名，这里直接问厂商「你现在有哪些模型」 */
    private fun fetchModels(p: Provider, keyOverride: String? = null, onPicked: ((String) -> Unit)? = null) {
        // 优先用「输入框里当下填的 Key」，其次才回退到已保存的。
        // 老代码只读已保存值 → 用户刚粘贴完、还没点「保存」就点「拉取」，
        // 会被判成「没填 Key」，于是出现「明明填了却提示先去设置里填」的鬼打墙。
        val key = keyOverride?.trim()?.takeIf { it.isNotBlank() } ?: cfgStore.keyOf(p.id)
        if (key.isBlank() && !p.baseUrl.contains("127.0.0.1")) {
            toast("请先在下面输入框填好 ${p.label} 的 Key（没保存也行，拉取会直接用它）")
            return
        }
        toast("正在拉取 ${p.label} 的模型列表…")
        Thread {
            val ids = runCatching {
                val url = java.net.URL(cfgStore.effectiveBaseUrl(p).trimEnd('/') + "/models")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Accept", "application/json")
                }
                val txt = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                conn.disconnect()
                val jo = org.json.JSONObject(txt)
                val arr = jo.optJSONArray("data") ?: jo.optJSONArray("models")
                val list = mutableListOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id").ifBlank { o.optString("name") }
                        if (id.isNotBlank()) list += id
                    }
                }
                list.sorted()
            }.getOrElse { emptyList() }
            main.post {
                if (ids.isEmpty()) {
                    toast("没拉到（协议不支持或 Key/地址不对），先给预设清单")
                    val presets = p.models.map { it.name }.toTypedArray()
                    AlertDialog.Builder(themed())
                        .setTitle("${p.label} 预设模型")
                        .setItems(presets) { _, i ->
                            cfgStore.setModel(p.id, presets[i])
                            onPicked?.invoke(presets[i])
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                    return@post
                }
                AlertDialog.Builder(themed())
                    .setTitle("${p.label} 可用模型（${ids.size}）")
                    .setItems(ids.toTypedArray()) { _, i ->
                        cfgStore.setModel(p.id, ids[i])
                        onPicked?.invoke(ids[i])
                        refreshHeader()
                        toast("已选 ${ids[i]}，设置页点「保存」即可生效")
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }.start()
    }

    // ==================== 后台保活 ====================

    /**
     * 任务一启动就挂前台服务 + WakeLock。
     * 不这么做的话，切后台几秒钟系统就把进程冻结，AI 直接「不动了」。
     */
    private fun startGuard() {
        if (guardOn) return
        guardOn = true
        RunGuardService.start(this, "启动中…")
        runCatching {
            val pm = getSystemService(android.content.Context.POWER_SERVICE)
                as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK, "hexora:run"
            ).apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun updateGuard(text: String) {
        if (!guardOn) return
        RunGuardService.update(this, text)
    }

    private fun stopGuard() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        if (!guardOn) return
        guardOn = false
        RunGuardService.stop(this)
    }

    /** 国产 ROM 只靠前台服务还不够，得把电池优化关掉 */
    private fun askKeepAlive() {
        val pm = getSystemService(android.content.Context.POWER_SERVICE)
            as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已在保活白名单里，切后台不会被冻结")
            return
        }
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            toast("这台机器不允许直接申请，请到系统设置 → 应用 → Hexora → 省电策略里选「无限制」")
        }
    }

    /** Android 13+ 通知权限：不给也能跑，只是通知栏看不到进度 */
    private fun askNotiPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val ok = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!ok) runCatching {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 71)
        }
    }

    private fun appendThinking(line: String) {
        val tv = runBody ?: return
        val cur = tv.text.toString()
        val next = if (cur.isEmpty()) line else "$cur\n$line"
        tv.text = if (next.length > 6000) next.takeLast(6000) else next
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        main.postDelayed({
            flushScheduled = false
            if (pendingEvents.isEmpty()) return@postDelayed
            val txt = pendingEvents.joinToString("\n")
            pendingEvents.clear()
            val tv = TextView(this).apply {
                setText(txt)
                textSize = 11.5f
                setTextColor(pal.faint)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            chatList.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
            scrollChatToBottom()
        }, 140)
    }

    private fun flushNow() {
        if (pendingEvents.isEmpty()) return
        val txt = pendingEvents.joinToString("\n")
        pendingEvents.clear()
        addSystemLine(txt)
    }

    // ==================== 项目（一个项目一个目录） ====================

    private fun listProjects(): List<File> =
        (gameRoot.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith("_") }
            .sortedBy { it.name.lowercase() }

    private fun showProjects() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        col.addView(TextView(this).apply {
            text = "根目录 ${gameRoot.absolutePath}"
            textSize = 11f
            setTextColor(pal.faint)
            setPadding(dp(4), 0, dp(4), dp(10))
            setTextIsSelectable(true)
        })

        if (!Environment.isExternalStorageManager()) {
            col.addView(ghostBtnOf(this, pal, "授权所有文件访问 → 项目存到 /sdcard/Hexora").apply {
                setOnClickListener {
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
        }

        val projects = listProjects()
        if (projects.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "还没有项目，点下面的「新建项目」"
                textSize = 13f
                setTextColor(pal.sub)
                setPadding(dp(4), dp(2), dp(4), dp(8))
            })
        }
        for (p in projects) {
            val row = listRowOf(
                this, pal,
                p.name + if (p.name == currentGame) " · 当前" else "",
                p.absolutePath
            )
            row.setOnClickListener {
                switchProject(p.name)
                projectDlg?.dismiss()
            }
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        val dlg = AlertDialog.Builder(themed())
            .setTitle("项目")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("新建项目", null)
            .setNeutralButton("导出当前", null)
            .setNegativeButton("关闭", null)
            .create()
        projectDlg = dlg
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dlg.dismiss()
                newProjectDialog()
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dlg.dismiss()
                exportZip()
            }
        }
        dlg.show()
    }

    private fun switchProject(name: String) {
        openGame(name)
        refreshHeader()
        lastLoadedGame = ""
        addSystemLine("已打开项目「$name」 · ${File(gameRoot, name).absolutePath}")
        if (activeTab == 1) ensurePreviewFresh()
    }

    private fun newProjectDialog() {
        val et = EditText(this).apply {
            hint = "项目名，例如 snake-01"
            textSize = 14f
            setTextColor(pal.text)
            setHintTextColor(pal.faint)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundCard(this@MainActivity, pal.cardAlt, pal.border, 12)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(16), dp(10), dp(16), dp(4))
            addView(et, LinearLayout.LayoutParams(-1, -2))
        }
        val d = AlertDialog.Builder(themed())
            .setTitle("新建项目")
            .setMessage("一个项目一个目录，互不干扰。创建后先给一个可运行的空白页，之后 AI 的所有改动都只写进这个目录。")
            .setView(wrap)
            .setPositiveButton("创建", null)
            .setNegativeButton("取消", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = et.text.toString().trim()
                    .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
                    .joinToString("")
                    .trim('-')
                if (name.isEmpty()) {
                    toast("先起个名字")
                    return@setOnClickListener
                }
                val dir = File(gameRoot, name)
                if (dir.exists()) {
                    toast("已存在同名项目")
                    return@setOnClickListener
                }
                dir.mkdirs()
                runCatching { File(dir, "index.html").writeText(starterHtml(name)) }
                d.dismiss()
                switchProject(name)
                showTab(1)
            }
        }
        d.show()
    }

    /** 新项目的空白页：打开就能跑，不是白屏 */
    private fun starterHtml(name: String): String = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<title>$name</title>
<style>
  html,body{margin:0;height:100%;background:#0F1011;color:#EDEDED;
    font:14px/1.7 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
    display:flex;align-items:center;justify-content:center}
  .c{text-align:center;padding:28px;max-width:80%}
  h1{font-size:17px;font-weight:500;letter-spacing:.1em;margin:0 0 10px}
  p{color:#9A9A9A;font-size:13px;margin:6px 0}
</style>
</head>
<body>
  <div class="c">
    <h1>$name</h1>
    <p>项目已创建，这是一个独立的目录。</p>
    <p>回到「对话」页说要做什么，AI 会直接写进这个目录。</p>
  </div>
</body>
</html>
""".trimIndent()

    // ==================== 预览自动刷新 ====================

    private fun dirStamp(dir: File): Long {
        var st = dir.lastModified()
        for (f in dir.listFiles().orEmpty()) {
            st = st * 31 + f.lastModified() + f.name.hashCode()
        }
        return st
    }

    /** 切到预览页时调用：项目换了或文件变了才重载，不再每次进来都白一下 */
    private fun ensurePreviewFresh() {
        val dir = File(gameRoot, currentGame)
        val idx = File(dir, "index.html")
        val stamp = dirStamp(dir)
        if (currentGame == lastLoadedGame && stamp == lastLoadedStamp) return
        lastLoadedGame = currentGame
        lastLoadedStamp = stamp
        if (!idx.exists()) addSystemLine("「$currentGame」还没有 index.html，先让 AI 构建一次")
        reloadGame()
    }

    private fun attachScreenshot() {
        Thread {
            val img = snapshotCss(720, 72)
            main.post {
                if (img == null) {
                    toast("截图失败，等画面加载完再试")
                } else {
                    pendingShots += img
                    updateAttachInfo()
                    toast("已附上当前画面")
                }
            }
        }.start()
    }

    /** 把任意格式图片压成适合喂给模型的 JPEG */
    private fun shrinkToJpeg(raw: ByteArray, maxW: Int, quality: Int): ByteArray? = runCatching {
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val w = bmp.width
        val h = bmp.height
        val out = if (w > maxW) {
            val nh = (h.toFloat() * maxW / w).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, maxW, nh, true)
        } else bmp
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        if (out !== bmp) out.recycle()
        bmp.recycle()
        bos.toByteArray()
    }.getOrNull()

    // ==================== 预览页 ====================

    private lateinit var previewLabel: TextView

    private fun buildPreviewPage(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(8))
            setBackgroundColor(pal.navBg)
        }
        previewLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(pal.text)
            typeface = Typeface.DEFAULT_BOLD
        }
        val reload = chipOf(this, pal, "重载", false).apply {
            setOnClickListener {
                reloadGame()
                toast("已重载画面")
            }
        }
        val shot = chipOf(this, pal, "发给 AI", false).apply {
            setOnClickListener { attachScreenshot() }
        }
        val switch = chipOf(this, pal, "项目", false).apply {
            setOnClickListener { showProjects() }
        }

        bar.addView(previewLabel, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(reload)
        bar.addView(shot, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        bar.addView(switch, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        // 手绘全屏图标：把预览容器整个搬进全屏 Dialog（同一个 WebView 实例，游戏状态不丢）
        bar.addView(iconButton(this, pal, "fullscreen", sizeDp = 34, marginStartDp = 6) { showFullPreview() })

        @SuppressLint("SetJavaScriptEnabled")
        val w = WebView(this).apply { setBackgroundColor(pal.bg) }
        web = w
        val wrap = FrameLayout(this).apply { addView(w, FrameLayout.LayoutParams(-1, -1)) }

        page.addView(bar, LinearLayout.LayoutParams(-1, -2))
        page.addView(View(this).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)))
        page.addView(wrap, LinearLayout.LayoutParams(-1, 0, 1f))
        previewWrapHolder = wrap
        previewSlot = page
        return page
    }

    /**
     * 全屏看预览。
     * 关键：WebView 只能有一个父容器，所以这里是「同一个实例搬家」，
     * 而不是新建第二个 WebView —— 后者既丢游戏运行状态，又因为没有 asset 拦截而白屏。
     * manifest 已声明 configChanges=orientation|screenSize，横屏不会重建 Activity。
     */
    private fun showFullPreview() {
        if (fullDlg != null) return
        if (!::previewWrapHolder.isInitialized) return
        val host = previewWrapHolder.parent as? ViewGroup ?: return
        host.removeView(previewWrapHolder)

        val box = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        box.addView(previewWrapHolder, FrameLayout.LayoutParams(-1, -1))
        // 真全屏：没有标题栏，预览容器直接铺满整屏；屏内只留一个悬浮「缩小」按钮
        box.addView(chipOf(this, pal, "缩小", false).apply {
            alpha = 0.82f
            setOnClickListener { fullDlg?.dismiss() }
        }, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(16)
            rightMargin = dp(14)
        })

        val dlg = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dlg.setContentView(box)
        dlg.setOnDismissListener { restorePreview() }
        fullDlg = dlg
        dlg.show()
        dlg.window?.setLayout(-1, -1)
        // 真全屏：内容铺到状态栏/导航栏底下（LAYOUT_*），再把系统栏藏掉；不旋转屏幕
        @Suppress("DEPRECATION")
        dlg.window?.decorView?.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    /** 退出全屏：把预览容器搬回预览页原位 */
    private fun restorePreview() {
        fullDlg = null
        if (!::previewWrapHolder.isInitialized) return
        (previewWrapHolder.parent as? ViewGroup)?.removeView(previewWrapHolder)
        if (::previewSlot.isInitialized &&
            previewSlot.indexOfChild(previewWrapHolder) < 0
        ) {
            previewSlot.addView(previewWrapHolder, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    // ==================== 工作区（素材 / 文档 / 技能 / 代码） ====================
    //
    // 这是「常驻资源面板」，不是一次性选择器：
    // 列的是磁盘上真实存在的文件，重启 / 覆盖安装后还在，可预览、可多选、可反复加入对话。

    private var wsDlg: Dialog? = null
    private var wsTab = "media"        // media / doc / skill / code
    private var wsKind = "image"       // 素材页内：image / audio / video
    private var wsGrid = false         // 列表 / 网格
    private var wsSort = 0             // 0 名称 / 1 大小 / 2 时间
    private var wsSearch = ""
    private val wsSel = HashSet<String>()   // 勾选的相对路径
    private val wsOpen = HashSet<String>()  // 已展开的目录
    private var wsBody: LinearLayout? = null
    private var wsTabsRow: LinearLayout? = null
    private var wsBottom: LinearLayout? = null
    private var wsTitle: TextView? = null

    private fun openWorkspace(tab: String) {
        wsTab = tab
        if (wsDlg != null) {
            renderWs()
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(8))
            setBackgroundColor(pal.navBg)
        }
        wsTitle = TextView(this).apply {
            textSize = 14f
            setTextColor(pal.text)
            typeface = MEDIUM
        }
        bar.addView(wsTitle, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ghostBtnOf(this, pal, "关闭").apply { setOnClickListener { wsDlg?.dismiss() } })
        box.addView(bar, LinearLayout.LayoutParams(-1, -2))

        wsTabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(4))
        }
        box.addView(wsTabsRow, LinearLayout.LayoutParams(-1, -2))

        wsBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(10))
        }
        box.addView(ScrollView(this).apply { addView(wsBody) }, LinearLayout.LayoutParams(-1, 0, 1f))

        wsBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            setBackgroundColor(pal.navBg)
        }
        box.addView(wsBottom, LinearLayout.LayoutParams(-1, -2))

        val dlg = Dialog(this)
        dlg.setContentView(box)
        dlg.setOnDismissListener {
            wsDlg = null
            wsSel.clear()
        }
        wsDlg = dlg
        dlg.show()
        dlg.window?.setLayout(-1, -1)
        renderWs()
    }

    private fun renderWs() {
        val body = wsBody ?: return
        val tabs = wsTabsRow ?: return
        wsTitle?.text = "工作区 · $currentGame"

        tabs.removeAllViews()
        for ((t, label) in listOf(
            "media" to "素材", "doc" to "文档", "skill" to "技能", "code" to "代码"
        )) {
            tabs.addView(
                chipOf(this, pal, label, t == wsTab).apply {
                    setOnClickListener {
                        wsTab = t
                        wsSel.clear()
                        renderWs()
                    }
                },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }
            )
        }

        body.removeAllViews()
        when (wsTab) {
            "media" -> renderMediaTab(body)
            "doc" -> renderDocTab(body, "doc")
            "skill" -> renderDocTab(body, "skill")
            else -> renderCodeTab(body)
        }
        renderWsBottom()
    }

    // ---------- 素材页 ----------

    private fun renderMediaTab(body: LinearLayout) {
        val kinds = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for ((k, label) in listOf("image" to "图片", "audio" to "音频", "video" to "视频")) {
            kinds.addView(
                chipOf(this, pal, label, k == wsKind).apply {
                    setOnClickListener {
                        wsKind = k
                        wsSel.clear()
                        renderWs()
                    }
                },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }
            )
        }
        body.addView(kinds, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tools.addView(actionChip("upload", "上传") { pickMedia("media") })
        tools.addView(
            actionChip("search", sortLabel()) {
                wsSort = (wsSort + 1) % 3
                renderWs()
            },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }
        )
        tools.addView(
            actionChip(if (wsGrid) "list" else "grid", if (wsGrid) "列表" else "网格") {
                wsGrid = !wsGrid
                renderWs()
            },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }
        )
        body.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val files = listMedia(wsKind)
        if (files.isEmpty()) {
            body.addView(emptyHint("还没有素材。点「上传」加图片 / 音频 / 视频（落在 _uploads/media/，重启后还在）"))
            return
        }
        if (wsGrid) {
            body.addView(mediaGrid(files))
        } else {
            for (f in files) {
                body.addView(mediaRow(f), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
    }

    private fun sortLabel() = when (wsSort) {
        1 -> "按大小"
        2 -> "按时间"
        else -> "按名称"
    }

    private fun emptyHint(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 12.5f
        setTextColor(pal.faint)
        setPadding(dp(4), dp(18), dp(4), dp(18))
    }

    /** 工具条按钮：手绘图标 + 文字 */
    private fun actionChip(kind: String, label: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(12), dp(7))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 10), 0x14000000)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val iv = ImageView(this).apply {
            setImageDrawable(LineIcon(kind, pal.sub, dp(2).toFloat()))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(26), dp(26)))
        row.addView(TextView(this).apply {
            text = label
            textSize = 12.5f
            setTextColor(pal.text)
        })
        return row
    }

    /** 素材行：勾选框 + 缩略图 + 文件名/大小 + ⋮；点行 = 预览 */
    private fun mediaRow(f: File): LinearLayout {
        val kind = kindOfFile(f.name)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = pressable(roundCard(this@MainActivity, pal.card, pal.border, 12), 0x14000000)
            isClickable = true
            setOnClickListener { previewFile(f, kind) }
        }
        row.addView(selBox(f.relativeTo(gameRoot).path))
        row.addView(
            thumbView(f, 44),
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) }
        )
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = f.name
            textSize = 13f
            setTextColor(pal.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        col.addView(TextView(this).apply {
            text = fmtSize(f.length())
            textSize = 11f
            setTextColor(pal.faint)
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        row.addView(moreBtn(f, kind))
        return row
    }

    private fun mediaGrid(files: List<File>): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var line: LinearLayout? = null
        files.forEachIndexed { i, f ->
            if (i % 3 == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                wrap.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
            val kind = kindOfFile(f.name)
            val rel = f.relativeTo(gameRoot).path
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { previewFile(f, kind) }
                setOnLongClickListener {
                    if (wsSel.contains(rel)) wsSel.remove(rel) else wsSel.add(rel)
                    renderWs()
                    true
                }
            }
            cell.addView(thumbView(f, 92), LinearLayout.LayoutParams(-1, dp(92)))
            cell.addView(TextView(this).apply {
                text = f.name
                textSize = 11f
                setTextColor(if (wsSel.contains(rel)) pal.accent else pal.sub)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(dp(2), dp(4), dp(2), 0)
            })
            line?.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        }
        return wrap
    }

    private fun selBox(rel: String): CheckBox = CheckBox(this).apply {
        isChecked = wsSel.contains(rel)
        buttonTintList = android.content.res.ColorStateList.valueOf(pal.accent)
        setOnCheckedChangeListener { _, b ->
            if (b) wsSel.add(rel) else wsSel.remove(rel)
            renderWsBottom()
        }
    }

    private fun moreBtn(f: File, kind: String): ImageView = ImageView(this).apply {
        setImageDrawable(LineIcon("more", pal.faint, dp(2).toFloat()))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener {
            val rel = f.relativeTo(gameRoot).path
            AlertDialog.Builder(themed())
                .setTitle(f.name)
                .setItems(arrayOf("预览 / 播放", "加入对话", "复制路径", "删除")) { _, i ->
                    when (i) {
                        0 -> previewFile(f, kind)
                        1 -> {
                            attached += Attach(f.name, rel, kind)
                            toast("已加入待发送区：${f.name}")
                            updateAttachInfo()
                        }
                        2 -> copyToClipboard(rel)
                        else -> confirmDelete(f)
                    }
                }
                .show()
        }
    }

    private fun thumbView(f: File, sizeDp: Int): ImageView {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundCard(this@MainActivity, pal.cardAlt, pal.border, 8)
        }
        val k = kindOfFile(f.name)
        if (k == "image") {
            val bmp = runCatching { decodeSmall(f, dp(sizeDp)) }.getOrNull()
            if (bmp != null) {
                iv.setImageBitmap(bmp)
            } else {
                iv.setImageDrawable(LineIcon("image", pal.faint, dp(2).toFloat()))
                iv.setPadding(dp(10), dp(10), dp(10), dp(10))
            }
        } else {
            iv.setPadding(dp(11), dp(11), dp(11), dp(11))
            iv.setImageDrawable(LineIcon(k, pal.faint, dp(2).toFloat()))
        }
        return iv
    }

    private fun decodeSmall(f: File, target: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    }

    // ---------- 文档 / 技能页 ----------

    private fun renderDocTab(body: LinearLayout, kind: String) {
        val dir = kindDir(kind)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(18), dp(14), dp(14))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 12), 0x14000000)
            isClickable = true
            setOnClickListener { pickMedia(kind) }
        }
        val ic = ImageView(this).apply {
            setImageDrawable(LineIcon("upload", pal.sub, dp(2).toFloat()))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        card.addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(TextView(this).apply {
            text = if (kind == "skill") "点击上传技能（供 AI 读取）" else "点击上传文档"
            textSize = 13.5f
            setTextColor(pal.text)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = if (kind == "skill")
                "支持 .txt .md .json .js .lua 等 · 上传到 _skills/"
            else
                "支持 .txt .json .md .xml .lua .docx .pdf .zip · 上传到 _uploads/doc/"
            textSize = 11.5f
            setTextColor(pal.faint)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tools.addView(actionChip("search", sortLabel()) {
            wsSort = (wsSort + 1) % 3
            renderWs()
        })
        tools.addView(
            actionChip(if (wsGrid) "list" else "grid", if (wsGrid) "列表" else "网格") {
                wsGrid = !wsGrid
                renderWs()
            },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }
        )
        body.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val files = sortFiles(dir.listFiles()?.filter { it.isFile } ?: emptyList())
        body.addView(TextView(this).apply {
            text = (if (kind == "skill") "技能" else "项目文档") + "（${files.size}）  ${dir.name}/"
            textSize = 12f
            setTextColor(pal.faint)
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        if (files.isEmpty()) {
            body.addView(emptyHint(if (kind == "skill") "还没有技能文件" else "还没有文档"))
            return
        }
        for (f in files) {
            body.addView(docRow(f, kind), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun docRow(f: File, kind: String): LinearLayout {
        val rel = f.relativeTo(gameRoot).path
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = pressable(roundCard(this@MainActivity, pal.card, pal.border, 12), 0x14000000)
            isClickable = true
        }
        row.addView(selBox(rel))
        val iv = ImageView(this).apply {
            setImageDrawable(LineIcon(kindOfFile(f.name), pal.sub, dp(2).toFloat()))
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(26), dp(26)).apply { leftMargin = dp(6) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = f.name
            textSize = 13f
            setTextColor(pal.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        col.addView(TextView(this).apply {
            text = fmtSize(f.length())
            textSize = 11f
            setTextColor(pal.faint)
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        row.addView(moreBtn(f, kind))
        row.setOnClickListener { openFileEditor(f, f.parentFile ?: gameRoot) }
        return row
    }

    // ---------- 代码页 ----------

    private fun renderCodeTab(body: LinearLayout) {
        val dir = File(gameRoot, currentGame)
        val search = EditText(this).apply {
            hint = "搜索文件名"
            textSize = 13f
            setText(wsSearch)
            setTextColor(pal.text)
            setHintTextColor(pal.faint)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundCard(this@MainActivity, pal.card, pal.border, 12)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val now = s?.toString().orEmpty()
                    if (now == wsSearch) return
                    wsSearch = now
                    renderWs()
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val files = dir.walkTopDown().filter { it.isFile }
            .filterNot { it.path.contains("/_shots/") }
            .filter { wsSearch.isBlank() || it.relativeTo(dir).path.contains(wsSearch, true) }
            .sortedBy { it.relativeTo(dir).path }
            .take(300).toList()

        body.addView(TextView(this).apply {
            text = "${dir.name}（${files.size} 个文件）"
            textSize = 12f
            setTextColor(pal.faint)
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        if (files.isEmpty()) {
            body.addView(emptyHint("没有匹配的文件"))
            return
        }

        files.groupBy { it.relativeTo(dir).path.substringBeforeLast('/', "") }
            .toSortedMap()
            .forEach { (folder, list) ->
                val key = folder.ifBlank { "." }
                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(10), dp(6), dp(8))
                    isClickable = true
                    setOnClickListener {
                        if (wsOpen.contains(key)) wsOpen.remove(key) else wsOpen.add(key)
                        renderWs()
                    }
                }
                val fi = ImageView(this).apply {
                    setImageDrawable(LineIcon("folder_sm", pal.accent, dp(2).toFloat()))
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
                head.addView(fi, LinearLayout.LayoutParams(dp(24), dp(24)))
                head.addView(TextView(this).apply {
                    text = (if (folder.isBlank()) "根目录" else folder) + "（${list.size}）"
                    textSize = 13.5f
                    setTextColor(pal.text)
                    typeface = MEDIUM
                    setPadding(dp(6), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                head.addView(TextView(this).apply {
                    text = if (wsOpen.contains(key)) "收起" else "展开"
                    textSize = 11.5f
                    setTextColor(pal.faint)
                })
                body.addView(head)

                if (wsOpen.contains(key)) {
                    for (f in list) {
                        val rel = f.relativeTo(dir).path
                        val row = fileRow(
                            name = rel,
                            kind = kindOfFile(rel),
                            onOpen = { openFileEditor(f, dir) },
                            onAdd = {
                                attached += Attach(rel, rel, "code")
                                toast("已加入待发送区：$rel")
                                updateAttachInfo()
                            }
                        )
                        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                            leftMargin = dp(14)
                            bottomMargin = dp(6)
                        })
                    }
                }
            }
    }

    // ---------- 底部「加入对话」 ----------

    private fun renderWsBottom() {
        val b = wsBottom ?: return
        b.removeAllViews()
        val total = wsSel.size
        b.addView(TextView(this).apply {
            text = if (total == 0) "勾选文件后可加入对话" else "已选 $total 项"
            textSize = 12f
            setTextColor(pal.faint)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (total > 0) {
            b.addView(primaryBtnOf(this, pal, "加入对话 ($total)").apply {
                setOnClickListener { queueSelected() }
            })
        }
    }

    /** 勾选的文件 → 加入对话（图片同时进多模态） */
    private fun queueSelected() {
        val rels = wsSel.toList()
        if (rels.isEmpty()) return
        Thread {
            val out = mutableListOf<Attach>()
            for (rel in rels) {
                val kind = when {
                    rel.startsWith("_skills") -> "skill"
                    rel.startsWith("_uploads/doc") -> "doc"
                    rel.startsWith("_uploads/media") -> kindOfFile(rel)
                    else -> "code"
                }
                val a = attachFromFile(File(gameRoot, rel), rel, kind) ?: continue
                out += a
            }
            main.post {
                for (a in out) {
                    if (a.kind == "image") shotBytes[a.rel]?.let { pendingShots += it }
                    queued += a
                }
                wsSel.clear()
                toast("已加入对话 ${out.size} 项")
                updateAttachInfo()
                renderWs()
            }
        }.start()
    }

    private fun attachFromFile(f: File, rel: String, kind: String): Attach? = runCatching {
        if (!f.isFile) return@runCatching null
        val bytes = f.readBytes()
        val text = if (kind == "image" || kind == "audio" || kind == "video") ""
        else extractText(f.name, bytes).take(60000)
        if (kind == "image") shrinkToJpeg(bytes, 1024, 80)?.let { shotBytes[rel] = it }
        Attach(f.name, rel, kind, text)
    }.getOrNull()

    // ---------- 工具 ----------

    private fun kindDir(kind: String): File = when (kind) {
        "doc" -> File(gameRoot, "_uploads/doc")
        "skill" -> File(gameRoot, "_skills")
        else -> File(gameRoot, "_uploads/media")
    }

    private fun listMedia(kind: String): List<File> {
        val all = kindDir("media").listFiles()?.filter { it.isFile } ?: emptyList()
        return sortFiles(all.filter { kindOfFile(it.name) == kind })
    }

    private fun sortFiles(l: List<File>): List<File> = when (wsSort) {
        1 -> l.sortedByDescending { it.length() }
        2 -> l.sortedByDescending { it.lastModified() }
        else -> l.sortedBy { it.name.lowercase() }
    }

    private fun fmtSize(n: Long): String = when {
        n < 1024 -> "${n}B"
        n < 1024 * 1024 -> "${n / 1024}KB"
        else -> String.format(java.util.Locale.US, "%.1fMB", n / 1024.0 / 1024.0)
    }

    /** 图片预览用内置全屏；音频 / 视频交给系统播放器 */
    private fun previewFile(f: File, kind: String) {
        if (kind == "image") {
            val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
            if (bmp == null) {
                toast("这张图打不开")
                return
            }
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val wrap = FrameLayout(this).apply {
                setBackgroundColor(0xFF000000.toInt())
                addView(iv, FrameLayout.LayoutParams(-1, -1))
                setOnClickListener { d.dismiss() }
            }
            d.setContentView(wrap)
            d.show()
            d.window?.setLayout(-1, -1)
        } else {
            val uri = runCatching {
                FileProvider.getUriForFile(this, "$packageName.files", f)
            }.getOrNull()
            if (uri == null) {
                toast("打不开这个文件")
                return
            }
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(f.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(i) }.onFailure { toast("没有能打开它的 App") }
        }
    }

    private fun mimeOf(n: String): String = when (kindOfFile(n)) {
        "image" -> "image/*"
        "audio" -> "audio/*"
        "video" -> "video/*"
        else -> "*/*"
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(themed())
            .setTitle("删除文件")
            .setMessage(f.name)
            .setPositiveButton("删除") { _, _ ->
                runCatching { f.delete() }.onSuccess {
                    toast("已删除")
                    renderWs()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 发布页 ====================

    private fun buildPublishPage() {
        pubScroll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(18))
        }

        pubScroll.addView(TextView(this).apply {
            text = "导出与发布"
            textSize = 12f
            setTextColor(pal.faint)
            setPadding(dp(4), 0, 0, dp(8))
        })

        lastExportTv = TextView(this).apply {
            textSize = 12f
            setTextColor(pal.sub)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundCard(this@MainActivity, pal.accentSoft, pal.accentSoft, 14, 0)
            setOnClickListener { showExportActions() }
        }
        pubScroll.addView(lastExportTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val rows = listOf(
            Triple("导出工程包", "打包成 zip，并自动另存到「下载/H5Games」", { exportZip() }),
            Triple("查看当前游戏文件", "列出文件与体积", { showTree() }),
            Triple("查看 console 输出", "游戏里的 log / warn / error", { showConsole() }),
            Triple("切换 / 新建项目", "一个项目一个目录，互不干扰", { showProjects() }),
            Triple("后台保活设置", "切后台 / 锁屏 AI 继续跑（需要关掉电池优化）", { askKeepAlive() }),
            Triple("复制工程路径", gameRoot.absolutePath, { copyToClipboard(gameRoot.absolutePath) }),
            Triple("清空对话历史", "AI 会忘掉之前的上下文", { clearHistory() })
        )

        for ((title, sub, action) in rows) {
            val row = listRowOf(this, pal, title, sub)
            row.setOnClickListener { action() }
            pubScroll.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }

        pubPage.addView(ScrollView(this).apply { addView(pubScroll) }, LinearLayout.LayoutParams(-1, -1))
        refreshExportRow()
    }

    private fun refreshExportRow() {
        val f = lastExport
        lastExportTv.text = if (f != null && f.exists()) {
            "最近导出：${f.name}\n${f.parentFile?.absolutePath}\n（点这里可以分享或复制路径）"
        } else {
            "还没有导出过。点下面的「导出工程包」，导出后会告诉你文件在哪。"
        }
    }

    private fun exportZip() {
        toast("正在打包…")
        Thread {
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                val out = File(dir, "hexora_${currentGame}_${System.currentTimeMillis()}.zip")
                ZipOutputStream(out.outputStream()).use { z ->
                    for (g in listOf(File(gameRoot, currentGame))) {
                        if (!g.isDirectory) continue
                        if (g.name.startsWith("_")) continue
                        for (f in g.walkTopDown().filter { it.isFile }) {
                            z.putNextEntry(ZipEntry("${g.name}/${f.relativeTo(g).path}"))
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                    }
                }
                lastExport = out
                val pubPath = saveToDownloads(out)
                main.post {
                    refreshExportRow()
                    showExportDone(out, pubPath)
                }
            }.onFailure { e ->
                main.post { addErrorCard("导出失败：${e.message}") }
            }
        }.start()
    }

    /** 另存一份到公共下载目录，这样用文件管理器/微信都能直接找到 */
    private fun saveToDownloads(src: File): String? = runCatching {
        val rel = "H5Games"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + rel)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            contentResolver.openOutputStream(uri)?.use { o ->
                src.inputStream().use { i -> i.copyTo(o) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            "/sdcard/Download/$rel/${src.name}"
        } else {
            @Suppress("DEPRECATION")
            val d = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                rel
            ).apply { mkdirs() }
            val f = File(d, src.name)
            src.inputStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            f.absolutePath
        }
    }.getOrNull()

    private fun showExportDone(zip: File, pubPath: String?) {
        val msg = buildString {
            append("工程包已生成：\n")
            append(zip.absolutePath)
            append("\n")
            if (pubPath != null) {
                append("\n已另存到公共下载目录（用文件管理器可直接看到）：\n")
                append(pubPath)
            } else {
                append("\n（另存到下载目录失败，可以直接用下面的「分享」导出到别的 App）")
            }
        }
        val ctx = themed()
        AlertDialog.Builder(ctx)
            .setTitle("导出完成")
            .setMessage(msg)
            .setPositiveButton("分享") { _, _ -> shareZip(zip) }
            .setNeutralButton("复制路径") { _, _ -> copyToClipboard(zip.absolutePath) }
            .setNegativeButton("好", null)
            .show()
    }

    private fun showExportActions() {
        val zip = lastExport ?: return
        if (!zip.exists()) {
            toast("文件已被清理，请重新导出")
            return
        }
        AlertDialog.Builder(themed())
            .setTitle("最近导出的工程包")
            .setMessage(zip.absolutePath)
            .setPositiveButton("分享") { _, _ -> shareZip(zip) }
            .setNeutralButton("复制路径") { _, _ -> copyToClipboard(zip.absolutePath) }
            .setNegativeButton("好", null)
            .show()
    }

    private fun shareZip(zip: File) {
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "把工程包发到…"))
        }.onFailure { toast("分享失败：${it.message}") }
    }

    private fun clearHistory() {
        history.clear()
        chatList.removeAllViews()
        addSystemLine("对话历史已清空")
        toast("已清空")
    }

    // ==================== 设置 ====================

    /** 深色模式下让对话框也用深色主题 */
    private fun themed(): Context =
        ContextThemeWrapper(this, if (pal.dark) R.style.AppThemeDark else R.style.AppTheme)

    private fun showSettings() {
        val ctx = this
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
        }

        fun section(t: String) = col.addView(TextView(ctx).apply {
            text = t
            textSize = 11f
            letterSpacing = 0.1f
            typeface = MEDIUM
            setTextColor(pal.faint)
            setPadding(dp(2), dp(20), 0, dp(8))
        })

        fun input(hint: String, value: String, numeric: Boolean = false): EditText =
            EditText(ctx).apply {
                this.hint = hint
                textSize = 13.5f
                setText(value)
                setTextColor(pal.text)
                setHintTextColor(pal.faint)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundCard(ctx, pal.cardAlt, pal.border, 12)
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
            }

        fun spinnerOf(labels: List<String>, selected: Int) = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, labels
            )
            setSelection(selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0)))
        }

        // ---------- 外观 ----------
        section("外观")
        val themeChips = listOf("浅色", "深色", "跟随系统")
        val themeIds = listOf("light", "dark", "auto")
        var themeSel = themeIds.indexOf(cfgStore.themeMode).coerceAtLeast(0)
        val themeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        var themeViews: List<TextView> = emptyList()
        themeViews = themeChips.mapIndexed { i, t ->
            chipOf(ctx, pal, t, i == themeSel).also { c ->
                c.setOnClickListener {
                    themeSel = i
                    themeViews.forEachIndexed { j, v ->
                        val active = j == themeSel
                        v.setTextColor(if (active) pal.onAccent else pal.text)
                        v.background = pressable(
                            roundCard(ctx, if (active) pal.accent else pal.cardAlt,
                                if (active) pal.accent else pal.border, 20), 0x55FFFFFF
                        )
                    }
                }
                themeRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
        }
        col.addView(themeRow)

        // ---------- TapTap Maker（素材生产线） ----------
        // 为什么放这里：AI 跑在 WebView 沙箱里，没有任何 exec 通道，
        // 授权（pat/login）与绑定工程（init）只能由 App 本体代跑。
        section("TapTap Maker（生图 / 音乐 / 音效 / 配音）")
        col.addView(TextView(ctx).apply {
            text = "素材生成走 TapTap 云端，需要先授权一次。这里由 App 帮你跑命令，你不用敲终端。"
            textSize = 12f
            setTextColor(pal.sub)
            setPadding(dp(2), 0, 0, dp(8))
        })

        val makerOut = TextView(ctx).apply {
            text = "点下面任意按钮开始；「自检」能看出现在到底缺哪一步。"
            textSize = 11.5f
            setTextColor(pal.sub)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundCard(ctx, pal.cardAlt, pal.border, 12)
        }

        fun makerRun(cmd: List<String>, stdin: String?, tip: String) {
            makerOut.text = "执行中：$tip …"
            Thread {
                val r = MakerCli.run(ctx, cmd, stdin)
                main.post {
                    makerOut.text = (if (r.ok) "✅ " else "❌ ") + tip + "\n" + r.output.takeLast(900)
                }
            }.start()
        }

        val patEt = input("粘贴 PAT（也可用右边的扫码登录）", "")
        col.addView(patEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        val makerRow1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        makerRow1.addView(ghostBtnOf(ctx, pal, "保存 PAT").apply {
            setOnClickListener {
                val v = patEt.text.toString().trim()
                if (v.isEmpty()) {
                    toast("先把 PAT 粘进上面的输入框")
                } else {
                    makerRun(listOf("pat", "set", "--pat-stdin"), v + "\n", "写入 PAT")
                }
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
makerRow1.addView(ghostBtnOf(ctx, pal, "扫码登录").apply {
            setOnClickListener {
                // 走 MakerAuth（流式 + 10 分钟等待）：链接一出来就显示，并顺手打开浏览器。
                // 老写法是等进程退出才回读输出，链接永远显示不出来。
                makerOut.text = "正在获取授权链接…"
                Thread {
                    val u = MakerAuth.start(this@MainActivity)
                    main.post {
                        if (u == null) {
                            makerOut.text = "❌ 没拿到授权链接：" + MakerAuth.statusText(this@MainActivity)
                        } else {
                            makerOut.text = "✅ 授权链接（已尝试打开浏览器；也可长按复制）\n$u\n\n" +
                                "登录后点「创建 token」，完成后这里会自动提示。"
                            makerOut.setTextIsSelectable(true)
                            openExternal(u)
                        }
                    }
                }.start()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        makerRow1.addView(ghostBtnOf(ctx, pal, "自检").apply {
            setOnClickListener { makerRun(listOf("doctor", "--json"), null, "Maker 自检") }
        }, LinearLayout.LayoutParams(-2, -2))
        col.addView(makerRow1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val makerRow2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        makerRow2.addView(ghostBtnOf(ctx, pal, "绑定当前项目").apply {
            setOnClickListener {
                // 必须走桥的 /ensure-project：它用 HTTPS 直接调 Maker 接口（列项目 / 建项目），
                // 然后自己写 .maker-mcp/config.json —— 全程不需要 git、不需要 python。
                // 以前这里跑的是 `init --create`，而 init 第一行就是 ensureGitAvailable()：
                // 安卓沙箱里没有 git，所以这个按钮**从来没成功过**，
                // 症状就是每次生图都报 ".maker-mcp/config.json is missing"、用户只能自己去建项目。
                val proj = File(gameRoot, currentGame).absolutePath
                makerOut.text = "正在确保 Maker 已启动，并为「$currentGame」绑定素材项目…"
                Thread {
                    val sb = StringBuilder()
                    runCatching {
                        if (McpRt.makerReady(proj)) {
                            sb.append("✅ Maker 已在运行\n")
                        } else {
                            val err = McpRt.startMaker(this@MainActivity, {}, proj)
                            sb.append(if (err == null) "✅ Maker 已启动\n" else "❌ Maker 启动失败：$err\n")
                        }
                    }.onFailure { sb.append("❌ 启动异常：${it.message}\n") }
                    val enc = runCatching { java.net.URLEncoder.encode(proj, "UTF-8") }.getOrDefault(proj)
                    val body = runCatching {
                        val c = java.net.URL("http://127.0.0.1:${McpRt.MAKER_PORT}/ensure-project?dir=$enc")
                            .openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 8000
                        c.readTimeout = 90_000
                        c.inputStream.bufferedReader().use { it.readText() }
                    }.getOrElse { "❌ 绑定接口调用失败：${it.message}" }
                    main.post {
                        makerOut.text = sb.toString() + body
                        makerOut.setTextIsSelectable(true)
                    }
                }.start()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        makerRow2.addView(ghostBtnOf(ctx, pal, "查看我的应用").apply {
            setOnClickListener { makerRun(listOf("apps", "--json"), null, "列出 Maker 应用") }
        }, LinearLayout.LayoutParams(-2, -2))
        col.addView(makerRow2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        col.addView(makerOut, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // ---------- 厂商 ----------
        section("厂商（国内外主流已预设，Key 各家独立保存）")
        val provLabels = AiProviders.ALL.map { "${it.group} · ${it.label}" }
        val provSp = spinnerOf(provLabels, AiProviders.ALL.indexOfFirst { it.id == cfgStore.providerId })
        col.addView(spSpacer(provSp))

        var curProvider = AiProviders.ALL[provSp.selectedItemPosition]

        val urlEt = input("Base URL（可改成中转站）", cfgStore.effectiveBaseUrl(curProvider))
        val keyEt = input("API Key（只存本机，不会上传）", cfgStore.keyOf(curProvider.id))
        col.addView(urlEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        col.addView(keyEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val testBtn = ghostBtnOf(ctx, pal, "测试连通")
        col.addView(testBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        val testResult = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(pal.sub)
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        col.addView(testResult)

        // ---------- 模型 ----------
        section("模型")
        val modelEt = input("模型名（可直接手填最新模型）", cfgStore.modelOf(curProvider.id))
        col.addView(modelEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // 这个下拉本身就是「拉取可用模型」：点一下就去问厂商现在有哪些模型，
        // 不再依赖写死的预设（预设永远追不上厂商改名/上新）。
        val pickText = TextView(ctx).apply {
            text = "从厂商拉取可用模型"
            textSize = 13f
            setTextColor(pal.text)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val pickRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(8), dp(9))
            background = pressable(roundCard(ctx, pal.cardAlt, pal.border, 12), 0x14000000)
            addView(pickText)
            addView(TextView(ctx).apply {
                text = "拉取"
                textSize = 12.5f
                setTextColor(pal.accent)
                setPadding(dp(10), dp(2), dp(2), dp(2))
            })
        }
        pickRow.setOnClickListener {
            // 把输入框里的 Key 直接传进去：用户刚粘贴、还没点保存时也能拉取
            fetchModels(curProvider, keyEt.text?.toString().orEmpty()) { picked ->
                modelEt.setText(picked)
                pickText.text = "已选 · $picked"
                pickText.setTextColor(pal.accent)
            }
        }
        col.addView(pickRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })


        // ---------- 视觉策略 ----------
        section("视觉能力（截图能否直接给模型看）")
        val visChips = listOf("自动", "强制开", "强制关")
        val visIds = listOf("auto", "on", "off")
        var visSel = visIds.indexOf(cfgStore.visionMode).coerceAtLeast(0)
        val visRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        var visViews: List<TextView> = emptyList()
        visViews = visChips.mapIndexed { i, t ->
            chipOf(ctx, pal, t, i == visSel).also { c ->
                c.setOnClickListener {
                    visSel = i
                    visViews.forEachIndexed { j, v ->
                        val active = j == visSel
                        v.setTextColor(if (active) pal.onAccent else pal.text)
                        v.background = pressable(
                            roundCard(ctx, if (active) pal.accent else pal.cardAlt,
                                if (active) pal.accent else pal.border, 20), 0x55FFFFFF
                        )
                    }
                }
                visRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
        }
        col.addView(visRow)
        col.addView(TextView(ctx).apply {
            text = "自动 = 模型自带看图就开（截图直接喂给 AI，能自己查 UI）；强制开/关用于新模型。"
            textSize = 11.5f
            setTextColor(pal.faint)
            setPadding(dp(2), dp(6), dp(2), 0)
        })

        // ---------- 技能 ----------
        section("技能包（决定 AI 的工作方式）")
        val skillSp = spinnerOf(
            SkillPresets.ALL.map { it.label + if (it.needVision) "（建议开视觉）" else "" },
            SkillPresets.ALL.indexOfFirst { it.id == cfgStore.skillId }
        )
        col.addView(spSpacer(skillSp))

        // ---------- 参数 ----------
        section("参数")
        val tempEt = input("temperature（越低越稳定）", cfgStore.temperature.toString(), true)
        val stepsEt = input("单次最多工具轮数", cfgStore.maxSteps.toString(), true)
        col.addView(tempEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        col.addView(stepsEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val fbCb = CheckBox(ctx).apply {
            text = "模型看不了图时，把截图存盘并把路径告诉它"
            textSize = 12.5f
            setTextColor(pal.text)
            isChecked = cfgStore.visionFallback
        }
        col.addView(fbCb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // ---------- 请求体积（适配免费档 / 小配额模型） ----------
        section("请求体积（免费档 / 小配额模型必调）")
        col.addView(TextView(ctx).apply {
            text = "很多模型有「每分钟 token 上限」（如 Groq 免费档 8000 TPM）。" +
                "工具清单 + 历史一多，请求就会被判超限（413/429/余额不足）。" +
                "下面几项越小越省 token。"
            textSize = 11.5f
            setTextColor(pal.faint)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val maxOutEt = input(
            "输出上限 max_tokens（0 = 交给服务商决定）",
            cfgStore.maxOutTokens.toString(), true
        )
        col.addView(maxOutEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val histEt = input("历史条数上限（只带最近 N 条消息）", cfgStore.historyLimit.toString(), true)
        col.addView(histEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val sendToolsCb = CheckBox(ctx).apply {
            text = "把工具清单发给模型（关掉最省 token，但 AI 不能调用工具/生图）"
            textSize = 12.5f
            setTextColor(pal.text)
            isChecked = cfgStore.sendTools
        }
        col.addView(sendToolsCb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val autoSlimCb = CheckBox(ctx).apply {
            text = "遇 413/超限时自动精简并重试（推荐开）"
            textSize = 12.5f
            setTextColor(pal.text)
            isChecked = cfgStore.autoSlim
        }
        col.addView(autoSlimCb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        col.addView(ghostBtnOf(ctx, pal, "按当前厂商一键省 token（输出 512 / 历史 12 / 关工具）").apply {
            setOnClickListener {
                maxOutEt.setText("512")
                histEt.setText("12")
                sendToolsCb.isChecked = false
                autoSlimCb.isChecked = true
                toast("已填入省 token 参数，点「保存」生效")
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })


        provSp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long
            ) {
                curProvider = AiProviders.ALL[pos]
                urlEt.setText(cfgStore.effectiveBaseUrl(curProvider))
                keyEt.hint = curProvider.keyHint
                keyEt.setText(cfgStore.keyOf(curProvider.id))
                modelEt.setText(cfgStore.modelOf(curProvider.id))
                testResult.text = ""
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ---------- MCP 服务器 ----------
        section("MCP 服务器（TapTap 小游戏等，运行时已内置）")
        val mcpStatus = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(pal.sub)
            setPadding(dp(2), 0, dp(2), dp(6))
        }
        col.addView(mcpStatus)

        val mcpList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(mcpList)

        fun mcpRender() {
            val rt = when {
                !McpRt.ready(ctx) -> "运行时未释放"
                McpRt.running() -> "本地服务运行中（端口 ${McpRt.PORT}）"
                else -> "运行时已就绪，服务未启动"
            }
            val hub = EngineTools.mcp
            val info = hub?.lastInfo ?: "未连接"
            mcpStatus.text = "$rt · $info · 已注册 ${hub?.size ?: 0} 个工具"
        }

        fun mcpRebuild() {
            mcpList.removeAllViews()
            val list = McpStore.load(ctx)
            if (list.isEmpty()) {
                mcpList.addView(TextView(ctx).apply {
                    text = "（还没有服务器，点下面「添加服务器」）"
                    textSize = 12f
                    setTextColor(pal.faint)
                    setPadding(dp(2), 0, 0, dp(4))
                })
            }
            list.forEach { s ->
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(android.widget.CheckBox(ctx).apply {
                    isChecked = s.enabled
                    setOnCheckedChangeListener { _, v ->
                        s.enabled = v
                        McpStore.save(ctx, list)
                        toast("MCP「${s.name}」已${if (v) "启用" else "停用"}，重启服务后生效")
                    }
                })
                row.addView(TextView(ctx).apply {
                    text = "${s.name}\n${s.url}"
                    textSize = 12.5f
                    setTextColor(pal.text)
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                row.addView(ghostBtnOf(ctx, pal, "删除").apply {
                    setOnClickListener {
                        McpStore.save(ctx, list.filter { it !== s })
                        mcpRebuild()
                        mcpRender()
                    }
                })
                mcpList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
        }

        fun mcpStart(restart: Boolean) {
            mcpStatus.text = "正在启动 MCP 服务…"
            Thread {
                val hub = McpBoot.ensure(ctx) { }
                if (hub != null) McpGuardService.start(ctx)
                main.post {
                    mcpRender()
                    toast(if (hub != null) "MCP 服务已就绪（${hub.lastInfo}）" else "MCP 启动失败，可在 设置 → MCP 服务器 的日志里看原因")
                }
            }.start()
        }

        mcpRebuild()
        mcpRender()

        val mcpRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        mcpRow.addView(ghostBtnOf(ctx, pal, "启动 / 重连").apply { setOnClickListener { mcpStart(true) } })
        mcpRow.addView(ghostBtnOf(ctx, pal, "刷新状态").apply { setOnClickListener { mcpRender() } })
        mcpRow.addView(ghostBtnOf(ctx, pal, "添加服务器").apply {
            setOnClickListener {
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(6), dp(18), dp(6))
                }
                val nameEt = input("名称", "我的 MCP")
                val urlEt2 = input("地址（Streamable HTTP，如 http://127.0.0.1:3000/）", "http://")
                box.addView(nameEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                box.addView(urlEt2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                AlertDialog.Builder(themed())
                    .setTitle("添加 MCP 服务器")
                    .setView(box)
                    .setPositiveButton("添加") { _, _ ->
                        val list = McpStore.load(ctx)
                        list.add(
                            McpServer(
                                nameEt.text.toString().trim().ifBlank { "MCP" },
                                urlEt2.text.toString().trim(),
                                true
                            )
                        )
                        McpStore.save(ctx, list)
                        mcpRebuild()
                        mcpRender()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        col.addView(mcpRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val dlg = AlertDialog.Builder(themed())
            .setTitle("设置")
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("保存", null)
            .setNeutralButton("清空这家 Key", null)
            .setNegativeButton("取消", null)
            .create()

        dlg.setOnShowListener {
            val test = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            // 用「保存」按钮旁边的位置挂两个动作，这里改成自定义布局：保存写盘
            test.setOnClickListener {
                cfgStore.providerId = curProvider.id
                cfgStore.setKey(curProvider.id, keyEt.text.toString())
                cfgStore.setBaseUrl(curProvider.id, urlEt.text.toString())
                val m = modelEt.text.toString().trim()
                if (m.isNotEmpty()) cfgStore.setModel(curProvider.id, m)
                cfgStore.skillId = SkillPresets.ALL[skillSp.selectedItemPosition].id
                cfgStore.temperature = tempEt.text.toString().toDoubleOrNull() ?: 0.4
                cfgStore.maxSteps = stepsEt.text.toString().toIntOrNull() ?: 400
                cfgStore.visionFallback = fbCb.isChecked
                cfgStore.visionMode = visIds[visSel]
                cfgStore.maxOutTokens = maxOutEt.text.toString().toIntOrNull() ?: 0
                cfgStore.historyLimit = histEt.text.toString().toIntOrNull() ?: 40
                cfgStore.sendTools = sendToolsCb.isChecked
                cfgStore.autoSlim = autoSlimCb.isChecked
                val oldTheme = cfgStore.themeMode
                cfgStore.themeMode = themeIds[themeSel]
                // 保存设置绝对不能顺手清空对话——用户只是来调个参数
                dlg.dismiss()
                if (oldTheme != themeIds[themeSel]) {
                    toast("主题已切换，正在刷新界面…")
                    recreate()
                } else {
                    refreshHeader()
                    addSystemLine("设置已保存：${curProvider.label} / ${cfgStore.modelOf(curProvider.id)}（对话已保留）")
                }
            }

            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                cfgStore.clearKey(curProvider.id)
                keyEt.setText("")
                testResult.text = "已清空 ${curProvider.label} 的 Key"
            }
        }

        testBtn.setOnClickListener {
            val p = curProvider
            val model = modelEt.text.toString().trim().ifEmpty { p.models.first().name }
            val key = keyEt.text.toString()
            val url = urlEt.text.toString()
            val local = url.contains("127.0.0.1") || url.contains("localhost")
            if (key.isBlank() && !local) {
                testResult.text = "先填 Key 再测"
                return@setOnClickListener
            }
            testResult.text = "正在测试 ${p.label} / $model …"
            Thread {
                val r = AiClient(cfgStore.candidate(p, model, key, url)).test()
                main.post { testResult.text = r }
            }.start()
        }

        dlg.show()
    }

    /** Spinner 在没有下拉箭头时容易看着像纯文本，给它加个底 */
    private fun spSpacer(v: android.widget.Spinner): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = roundCard(this@MainActivity, pal.cardAlt, pal.border, 12)
        }
        box.addView(v, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    private fun refreshHeader() {
        val cfg = cfgStore.active()
        val skill = SkillPresets.byId(cfgStore.skillId)
        titleTv.text = "Hexora"
        subTv.text = buildString {
            append("项目 $currentGame")
            append("  ·  ${cfg.provider.label} / ${cfg.modelLabel}")
            append(if (cfg.vision) "  ·  视觉开" else "  ·  视觉关")
            append("  ·  ${skill.label}")
        }
        modelBtn.text = "${cfg.provider.label} / ${cfg.model} ▾"
        projBtn.text = "$currentGame ▾"
        previewLabel.text = "项目：$currentGame"
    }

    // ==================== 小工具 ====================

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun alert(title: String, msg: String) {
        AlertDialog.Builder(themed())
            .setTitle(title)
            .setMessage(msg.take(9000))
            .setPositiveButton("好", null)
            .show()
    }

    private fun copyToClipboard(s: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("path", s))
        toast("已复制")
    }

    private fun showTree() {
        Thread {
            val d = File(gameRoot, currentGame)
            val sb = StringBuilder("${d.absolutePath}\n\n")
            val files = d.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
            if (files.isEmpty()) sb.append("(空)")
            files.forEach {
                sb.append(it.relativeTo(d).path).append("   ").append(it.length()).append("B\n")
            }
            main.post { alert("${currentGame} 文件", sb.toString()) }
        }.start()
    }

    private fun showConsole() {
        alert("console 输出", logs.tail(120).joinToString("\n").ifBlank { "(暂无输出)" })
    }

    private fun pickGame() {
        val dirs = gameRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith("_") }
            ?.map { it.name }?.sorted() ?: emptyList()
        if (dirs.isEmpty()) {
            toast("还没有游戏，让 AI 先建一个")
            return
        }
        AlertDialog.Builder(themed())
            .setTitle("选择游戏")
            .setItems(dirs.toTypedArray()) { _, i ->
                openGame(dirs[i])
                refreshHeader()
                addSystemLine("已切换到 ${dirs[i]}")
            }
            .show()
    }

    // ==================== WebView ====================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                safeBrowsingEnabled = false
            }
        }
        web.webChromeClient = WebChromeClient()
        web.setBackgroundColor(pal.bg)

        bridge = EngineBridge(web, File(gameRoot, currentGame), logs)
        web.addJavascriptInterface(bridge, "Native")

        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler(
                "/games/",
                WebViewAssetLoader.InternalStoragePathHandler(this, gameRoot)
            )
            .addPathHandler("/lib/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val res = loader.shouldInterceptRequest(request.url)
                if (res != null) return res
                if (request.url.host == "appassets.androidplatform.net") {
                    val msg = "资源不存在：${request.url.path}\n游戏目录：${File(gameRoot, currentGame).absolutePath}"
                    return WebResourceResponse(
                        "text/plain", "utf-8", 200, "OK",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(msg.toByteArray())
                    )
                }
                return null
            }
        }
    }

    // ==================== GameUi 实现 ====================

    override fun currentGameId(): String = currentGame

    override fun openGame(id: String) {
        if (id != currentGame) {
            // 切项目 = 换一份会话库：先把旧项目这轮对话落盘，再切新库
            runCatching { persistSessions() }
            sessStore = null
        }
        currentGame = id
        cfgStore.lastGame = id   // 落盘：供下次冷启动 / 覆盖安装后恢复
        val dir = File(gameRoot, id).apply { mkdirs() }
        bridge.setSandbox(dir)
        // Maker 桥把项目路径写在启动参数里：切项目必须重启它，
        // 否则你让它生图，素材会落进「上一个项目」。
        Thread { runCatching { McpBoot.ensure(this@MainActivity) { } } }.start()
        lastLoadedGame = id
        lastLoadedStamp = dirStamp(dir)
        val url = "https://appassets.androidplatform.net/games/$id/index.html"
        main.post {
            logs.add("[system] 打开项目 $id")
            // 该项目的对话历史重新装载（项目之间绝不共享上下文）
            reloadSessionsForProject()
            if (File(dir, "index.html").exists()) {
                web.loadUrl(url)
            } else {
                // 没有 index.html 时给个说明页，而不是白屏
                web.loadDataWithBaseURL(
                    null,
                    "<html><body style='background:#0F1011;color:#9A9A9A;font-family:sans-serif;padding:40px;line-height:1.8'>" +
                        "<h3 style='color:#EDEDED;font-weight:500'>「" + id + "」还没有 index.html</h3>" +
                        "<p>回到「对话」页告诉 AI 你想做什么，它会构建到这个目录：</p>" +
                        "<p style='color:#4FCFB4;word-break:break-all'>" + dir.absolutePath + "</p>" +
                        "</body></html>",
                    "text/html", "utf-8", null
                )
            }
        }
    }

    override fun reloadGame() {
        main.post { web.reload() }
    }

    override fun runJsSync(code: String, timeoutMs: Int): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return "\"不能在主线程执行 JS\""

        val latch = CountDownLatch(1)
        val holder = AtomicReference("null")

        main.post {
            runCatching {
                web.evaluateJavascript(code) { v ->
                    holder.set(v ?: "null")
                    latch.countDown()
                }
            }.onFailure {
                holder.set(JSONObject.quote("eval 失败: ${it.message}"))
                latch.countDown()
            }
        }

        val ok = latch.await(timeoutMs.toLong().coerceIn(500, 60_000), TimeUnit.MILLISECONDS)
        return if (ok) holder.get() else "\"超时 (${timeoutMs}ms)\""
    }

    override fun snapshotCss(maxWidth: Int, quality: Int): ByteArray? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null

        val latch = CountDownLatch(1)
        val holder = AtomicReference<ByteArray?>()

        main.post {
            runCatching {
                val w = web.width.coerceAtLeast(1)
                val h = web.height.coerceAtLeast(1)
                val density = resources.displayMetrics.density.coerceAtLeast(1f)

                val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                web.draw(Canvas(raw))

                // 换算成 CSS 像素：模型在图上量到的坐标可直接用于 tap()
                val cssW = (w / density).toInt().coerceAtLeast(1)
                val cssH = (h / density).toInt().coerceAtLeast(1)
                val targetW = cssW.coerceAtMost(maxWidth.coerceAtLeast(64))
                val targetH = (cssH.toFloat() * targetW / cssW).toInt().coerceAtLeast(1)

                val out = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
                val bos = ByteArrayOutputStream()
                out.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), bos)

                raw.recycle()
                if (out !== raw) out.recycle()
                holder.set(bos.toByteArray())
            }.onFailure { holder.set(null) }
            latch.countDown()
        }

        latch.await(15, TimeUnit.SECONDS)
        return holder.get()
    }

    override fun pointer(x: Float, y: Float, kind: Int) {
        val type = when (kind) {
            0 -> "down"
            1 -> "up"
            else -> "move"
        }
        val js = "(function(){var t=document.elementFromPoint($x,$y)||document.body;" +
            "var o={bubbles:true,cancelable:true,clientX:$x,clientY:$y,pointerId:1," +
            "pointerType:'touch',isPrimary:true,view:window};" +
            "t.dispatchEvent(new PointerEvent('pointer$type',o));return 'ok';})()"
        runJsSync(js, 4000)
    }

    override fun inputText(text: String) {
        val quoted = JSONObject.quote(text)
        val js = "(function(){var el=document.activeElement;" +
            "if(!el||!('value' in el))return 'no-focus';el.value=$quoted;" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';})()"
        runJsSync(js, 4000)
    }

    override fun consoleTail(n: Int): List<String> = logs.tail(n)

    override fun consoleClear() = logs.clear()

    // ==================== 资源 ====================

    /** 首次启动把 assets 里的示例游戏释放出来，否则 WebView 会白屏 */
    private fun seedBundledGames() {
        val names = runCatching { assets.list("games")?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
        for (n in names) {
            if (n.startsWith("_")) continue
            val dst = File(gameRoot, n)
            if (File(dst, "index.html").exists() && File(dst, "engine.js").exists()) continue
            runCatching { copyAssetTree("games/$n", dst) }
        }
    }

    private fun copyAssetTree(assetPath: String, dst: File) {
        val children = assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            dst.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> dst.outputStream().use { input.copyTo(it) } }
            return
        }
        dst.mkdirs()
        for (c in children) copyAssetTree("$assetPath/$c", File(dst, c))
    }

    /** 所有游戏共用一份运行时 engine.js，每次启动覆盖刷新 */
    private fun seedSharedRuntime() {
        val dir = File(gameRoot, "_shared").apply { mkdirs() }
        runCatching {
            assets.open("games/demo/engine.js").use { input ->
                File(dir, "engine.js").outputStream().use { input.copyTo(it) }
            }
        }
        // 广告接入技能资产：adkit.js 模板 + 说明文档（AI 用 game_read 读 _shared/adkit.js 直接复制进游戏）
        runCatching {
            for (n in assets.list("adkit")?.toList().orEmpty()) {
                assets.open("adkit/$n").use { input ->
                    File(dir, n).outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    // ==================== 生命周期 ====================

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (activeTab != 1) {
            showTab(1)
            return
        }
        if (web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        runCatching { runner?.cancel() }
        runCatching { web.destroy() }
        super.onDestroy()
    }
}