package com.mcp.h5engine

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
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
    private val history = mutableListOf<ChatMsg>()
    private val pendingShots = mutableListOf<ByteArray>()
    private val cfgStore by lazy { AiConfigStore(this) }

    private lateinit var pal: Palette
    private lateinit var gameRoot: File

    @Volatile
    private var currentGame = "demo"

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

        gameRoot = File(filesDir, "games").apply { mkdirs() }
        seedBundledGames()
        seedSharedRuntime()

        pal = paletteOf(this, themeModeOf(cfgStore.themeMode))

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
        addSystemLine("直接说「做个贪吃蛇」，或点 🖼 把画面发给 AI 让它改。")
    }

    private var topHolder: LinearLayout? = null
    private var navHolder: LinearLayout? = null

    // ==================== 顶栏 ====================

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
            text = "H5 游戏工作台"
            setTextColor(pal.text)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }

        val settings = TextView(this).apply {
            text = "⚙ 设置"
            textSize = 12.5f
            setTextColor(pal.sub)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 20), 0x3312BFA3)
            setOnClickListener { showSettings() }
        }

        row.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(settings)
        top.addView(row)

        subTv = TextView(this).apply {
            setTextColor(pal.sub)
            textSize = 11.5f
        }
        top.addView(subTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })

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

    private fun navItemView(icon: String, label: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(9))
            background = pressable(roundCard(this@MainActivity, pal.navBg, pal.navBg, 0, 0), 0x2212BFA3)
        }
        box.addView(TextView(this).apply {
            text = icon
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(pal.navIdle)
        })
        box.addView(TextView(this).apply {
            text = label
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(pal.navIdle)
            setPadding(0, dp(2), 0, 0)
        })
        return box
    }

    private fun buildNav() {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pal.navBg)
        }
        navChat = navItemView("💬", "对话")
        navPreview = navItemView("🖥", "预览")
        navPub = navItemView("🚀", "发布")
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
                c.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }

    private fun showTab(tab: Int) {
        activeTab = tab
        chatPage.visibleIf(tab == 0)
        previewPage.visibleIf(tab == 1)
        pubPage.visibleIf(tab == 2)
        tintNav(navChat, tab == 0)
        tintNav(navPreview, tab == 1)
        tintNav(navPub, tab == 2)
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
        }
        page.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        page.addView(View(this).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(pal.navBg)
        }

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

        val attachBtn = chipOf(this, pal, "＋ 图片", false).apply {
            setOnClickListener { pickImage.launch("image/*") }
        }
        val shotBtn = chipOf(this, pal, "🖼 当前画面", false).apply {
            setOnClickListener { attachScreenshot() }
        }
        modelBtn = TextView(this).apply {
            textSize = 11.5f
            setTextColor(pal.sub)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { showSettings() }
        }
        stopBtn = chipOf(this, pal, "■ 停", false).apply {
            visibility = View.GONE
            setOnClickListener {
                runner?.cancel()
                toast("已请求停止")
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
        return page
    }

    // ==================== 气泡 ====================

    private fun addBubble(text: String, fromUser: Boolean) {
        val tv = TextView(this).apply {
            setText(text)
            textSize = if (fromUser) 14f else 13.5f
            setTextColor(if (fromUser) pal.userText else pal.aiText)
            setPadding(dp(13), dp(10), dp(13), dp(10))
            setTextIsSelectable(true)
            if (fromUser) {
                background = roundCard(this@MainActivity, pal.userBubble, pal.userBubble, 16, 0)
            } else {
                background = roundCard(this@MainActivity, pal.aiBubble, pal.border, 16)
            }
        }
        chatList.addView(tv, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(5)
            bottomMargin = dp(5)
            if (fromUser) leftMargin = dp(46) else rightMargin = dp(30)
        })
        scrollChatToBottom()
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

    private fun scrollChatToBottom() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateAttachInfo() {
        if (pendingShots.isEmpty()) {
            attachInfo.visibility = View.GONE
        } else {
            attachInfo.visibility = View.VISIBLE
            attachInfo.text = "已附 ${pendingShots.size} 张图 · 点这里清除"
            attachInfo.setOnClickListener {
                pendingShots.clear()
                updateAttachInfo()
            }
        }
    }

    // ==================== 发送 ====================

    @Volatile
    private var lastUserText = ""

    private fun send() {
        if (running) {
            toast("AI 正在干活，先点「停」或等它做完")
            return
        }
        val text = inputEt.text.toString().trim().ifEmpty { lastUserText }
        if (text.isEmpty() && pendingShots.isEmpty()) return

        val cfg = cfgStore.active()
        val local = cfg.baseUrl.contains("127.0.0.1") || cfg.baseUrl.contains("localhost")
        if (cfg.apiKey.isBlank() && !local) {
            showSettings()
            toast("先填 ${cfg.provider.label} 的 API Key")
            return
        }

        val images = pendingShots.toList()
        pendingShots.clear()
        updateAttachInfo()
        inputEt.setText("")
        lastUserText = text

        addBubble(if (text.isEmpty()) "（看这张图）" else text, true)
        if (images.isNotEmpty()) addSystemLine("（附带 ${images.size} 张图片）")

        val skill = SkillPresets.byId(cfgStore.skillId)
        val tools = EngineTools(this, gameRoot)
        val r = AgentRunner(
            cfg = cfg,
            skill = skill,
            tools = tools,
            visionFallback = cfgStore.visionFallback,
            shotDir = File(gameRoot, "_shots")
        ) { ev -> onAgentEvent(ev) }

        runner = r
        running = true
        sendBtn.isEnabled = false
        sendBtn.alpha = 0.5f
        stopBtn.visibleIf(true)

        Thread {
            try {
                r.run(history, text.ifEmpty { "请看我发的图片，并按图片内容改进游戏。" }, images)
            } catch (t: Throwable) {
                main.post { addErrorCard(AiClient.friendly("${t.javaClass.simpleName}: ${t.message}")) }
            } finally {
                main.post {
                    running = false
                    sendBtn.isEnabled = true
                    sendBtn.alpha = 1f
                    stopBtn.visibleIf(false)
                }
            }
        }.start()
    }

    private fun onAgentEvent(ev: String) {
        main.post {
            when {
                ev.startsWith("AI: ") -> addBubble(ev.removePrefix("AI: "), false)
                ev.startsWith("✗") -> addErrorCard(ev.removePrefix("✗ 请求失败：").trim())
                else -> addSystemLine(ev)
            }
        }
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
        val reload = chipOf(this, pal, "⟳ 重载", false).apply {
            setOnClickListener {
                reloadGame()
                toast("已重载画面")
            }
        }
        val shot = chipOf(this, pal, "🖼 发给 AI", false).apply {
            setOnClickListener { attachScreenshot() }
        }
        val switch = chipOf(this, pal, "切换", false).apply {
            setOnClickListener { pickGame() }
        }

        bar.addView(previewLabel, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(reload)
        bar.addView(shot, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        bar.addView(switch, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })

        @SuppressLint("SetJavaScriptEnabled")
        val w = WebView(this).apply { setBackgroundColor(pal.bg) }
        web = w
        val wrap = FrameLayout(this).apply { addView(w, FrameLayout.LayoutParams(-1, -1)) }

        page.addView(bar, LinearLayout.LayoutParams(-1, -2))
        page.addView(View(this).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)))
        page.addView(wrap, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
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
            Triple("📦 导出工程包", "打包成 zip，并自动另存到「下载/H5Games」", { exportZip() }),
            Triple("📄 查看当前游戏文件", "列出文件与体积", { showTree() }),
            Triple("🧾 查看 console 输出", "游戏里的 log / warn / error", { showConsole() }),
            Triple("🎮 切换当前游戏", "切到别的游戏继续改", { pickGame() }),
            Triple("📋 复制工程路径", gameRoot.absolutePath, { copyToClipboard(gameRoot.absolutePath) }),
            Triple("🧹 清空对话历史", "AI 会忘掉之前的上下文", { clearHistory() })
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
            "✅ 最近导出：${f.name}\n${f.parentFile?.absolutePath}\n（点这里可以分享或复制路径）"
        } else {
            "还没有导出过。点下面的「导出工程包」，导出后会告诉你文件在哪。"
        }
    }

    private fun exportZip() {
        toast("正在打包…")
        Thread {
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                val out = File(dir, "h5games_${System.currentTimeMillis()}.zip")
                ZipOutputStream(out.outputStream()).use { z ->
                    for (g in gameRoot.listFiles().orEmpty().sortedBy { it.name }) {
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
            textSize = 12f
            setTextColor(pal.faint)
            setPadding(dp(2), dp(14), 0, dp(6))
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
        val themeChips = listOf("☀ 浅色", "🌙 深色", "⚙ 跟随系统")
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

        val testBtn = ghostBtnOf(ctx, pal, "🔌 测试连通")
        col.addView(testBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        val testResult = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(pal.sub)
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        col.addView(testResult)

        // ---------- 模型 ----------
        section("模型")
        val modelSp = android.widget.Spinner(ctx)
        val modelEt = input("模型名（可直接手填最新模型）", cfgStore.modelOf(curProvider.id))
        col.addView(modelEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        fun syncModels(p: Provider) {
            modelSp.adapter = android.widget.ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item,
                p.models.map { "${it.label} · ${it.name}" }
            )
            col.addView(modelSp, LinearLayout.LayoutParams(-1, -2))
        }
        modelSp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long
            ) {
                curProvider.models.getOrNull(pos)?.let { modelEt.setText(it.name) }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

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

        // 初始化：先同步一次模型列表
        syncModels(curProvider)

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
                syncModels(curProvider)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

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
                cfgStore.maxSteps = stepsEt.text.toString().toIntOrNull() ?: 40
                cfgStore.visionFallback = fbCb.isChecked
                cfgStore.visionMode = visIds[visSel]
                val oldTheme = cfgStore.themeMode
                cfgStore.themeMode = themeIds[themeSel]
                history.clear()
                chatList.removeAllViews()
                dlg.dismiss()
                if (oldTheme != themeIds[themeSel]) {
                    toast("主题已切换，正在刷新界面…")
                    recreate()
                } else {
                    refreshHeader()
                    addSystemLine("设置已保存：${curProvider.label} / ${cfgStore.modelOf(curProvider.id)}（历史已清空）")
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
        titleTv.text = "H5 游戏工作台"
        subTv.text = buildString {
            append("当前游戏 $currentGame")
            append("  ·  ${cfg.provider.label} / ${cfg.modelLabel}")
            append(if (cfg.vision) "  ·  👁 视觉开" else "  ·  视觉关")
            append("  ·  ${skill.label}")
        }
        modelBtn.text = "✦ ${cfg.provider.label} / ${cfg.model} ▾"
        previewLabel.text = "当前游戏：$currentGame"
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
        currentGame = id
        val dir = File(gameRoot, id).apply { mkdirs() }
        bridge.setSandbox(dir)
        val url = "https://appassets.androidplatform.net/games/$id/index.html"
        main.post {
            logs.add("[system] 打开游戏 $id")
            web.loadUrl(url)
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