package com.mcp.h5engine

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
 * 宿主：像手机上的 AI 游戏工作台。
 *
 *   [对话]  和 AI 聊天，可带图 / 带当前游戏截图
 *   [预览]  全屏看游戏画面
 *   [发布]  导出工程 zip / 查看路径 / 看 console
 *
 * AI 直接调用引擎工具（写文件、热重载、执行 JS、截图、点击），
 * 带视觉的模型会拿到截图，形成「写代码 → 看画面 → 改」的闭环。
 */
class MainActivity : AppCompatActivity(), GameUi {

    // ---------- 视图 ----------
    private lateinit var web: WebView
    private lateinit var bridge: EngineBridge
    private lateinit var chatPage: LinearLayout
    private lateinit var pubPage: LinearLayout
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var inputEt: EditText
    private lateinit var sendBtn: Button
    private lateinit var modelBtn: TextView
    private lateinit var attachInfo: TextView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var navChat: TextView
    private lateinit var navPreview: TextView
    private lateinit var navPub: TextView

    private val main = Handler(Looper.getMainLooper())
    private val logs = LogBuffer()
    private val history = mutableListOf<ChatMsg>()
    private val pendingShots = mutableListOf<ByteArray>()
    private val cfgStore by lazy { AiConfigStore(this) }

    private lateinit var gameRoot: File

    @Volatile
    private var currentGame = "demo"

    @Volatile
    private var running = false

    @Volatile
    private var runner: AgentRunner? = null

    private var activeTab = 0

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gameRoot = File(filesDir, "games").apply { mkdirs() }
        seedBundledGames()
        seedSharedRuntime()

        // ==================== 顶栏 ====================
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(6))
            setBackgroundColor(0xFF0F1318.toInt())
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleTv = TextView(this).apply {
            setTextColor(0xFFE6E9EF.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        val settingsBtn = TextView(this).apply {
            text = "⚙️"
            textSize = 17f
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { showSettings() }
        }
        topRow.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(settingsBtn)

        subTv = TextView(this).apply {
            setTextColor(0xFF6B7A8D.toInt())
            textSize = 11f
        }
        top.addView(topRow)
        top.addView(subTv)

        // ==================== 预览页（WebView） ====================
        web = WebView(this)

        // ==================== 对话页 ====================
        chatPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0F14.toInt())
        }

        chatList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        chatScroll = ScrollView(this).apply { addView(chatList) }
        chatPage.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // 输入区
        val inputBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(0xFF141A21.toInt())
        }

        attachInfo = TextView(this).apply {
            setTextColor(0xFF4ADE80.toInt())
            textSize = 11f
            visibility = View.GONE
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        inputBox.addView(attachInfo)

        inputEt = EditText(this).apply {
            hint = "提出创意、问题，或随便聊聊…"
            textSize = 13.5f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 4
            setTextColor(0xFFE6E9EF.toInt())
            setHintTextColor(0xFF5C6B7E.toInt())
            setBackgroundColor(0x00000000)
        }
        inputBox.addView(inputEt)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val attachBtn = TextView(this).apply {
            text = "＋"
            textSize = 20f
            setTextColor(0xFF9FB3C8.toInt())
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { pickImage.launch("image/*") }
        }
        val shotBtn = TextView(this).apply {
            text = "🖼"
            textSize = 16f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { attachScreenshot() }
        }
        modelBtn = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8AB4F8.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { showSettings() }
        }
        sendBtn = Button(this).apply {
            text = "发送"
            setOnClickListener { send() }
        }
        inputRow.addView(attachBtn)
        inputRow.addView(shotBtn)
        inputRow.addView(modelBtn, LinearLayout.LayoutParams(0, -2, 1f))
        inputRow.addView(sendBtn)
        inputBox.addView(inputRow)

        chatPage.addView(inputBox, LinearLayout.LayoutParams(-1, -2))

        // ==================== 发布页 ====================
        pubPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFF0B0F14.toInt())
            visibility = View.GONE
        }

        // ==================== 底栏 ====================
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0F1318.toInt())
            setPadding(0, dp(6), 0, dp(6))
        }
        navChat = navItem("对话").also { nav.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        navPreview = navItem("预览").also { nav.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        navPub = navItem("发布").also { nav.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }

        navChat.setOnClickListener { showTab(0) }
        navPreview.setOnClickListener { showTab(1) }
        navPub.setOnClickListener { showTab(2) }

        // ==================== 组装 ====================
        val stage = FrameLayout(this)
        stage.addView(web, FrameLayout.LayoutParams(-1, -1))
        stage.addView(pubPage, FrameLayout.LayoutParams(-1, -1))

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootView.addView(top, LinearLayout.LayoutParams(-1, -2))
        rootView.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        rootView.addView(chatPage, LinearLayout.LayoutParams(-1, 0, 1f))
        rootView.addView(nav, LinearLayout.LayoutParams(-1, -2))

        // 对话页和预览页各占一半权重，靠可见性切换
        chatPage.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        stage.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)

        setContentView(rootView)

        setupWebView()
        buildPublishPage()
        showTab(0)

        refreshHeader()
        openGame(currentGame)
        addSystemLine("引擎已就绪。当前游戏：${currentGame}")
        addSystemLine("可以直接说「做个打砖块」，或点 🖼 把画面发给 AI 让它改。")
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
        web.setBackgroundColor(0xFF0E1116.toInt())

        bridge = EngineBridge(web, File(gameRoot, currentGame), logs)
        web.addJavascriptInterface(bridge, "Native")

        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/games/", WebViewAssetLoader.InternalStoragePathHandler(this, gameRoot))
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

    // ==================== 页签 ====================

    private fun navItem(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(10), dp(6), dp(10))
    }

    private fun showTab(tab: Int) {
        activeTab = tab
        chatPage.visibility = if (tab == 0) View.VISIBLE else View.GONE
        web.visibility = if (tab == 1) View.VISIBLE else View.GONE
        pubPage.visibility = if (tab == 2) View.VISIBLE else View.GONE

        for ((i, v) in listOf(navChat, navPreview, navPub).withIndex()) {
            v.setTextColor(if (i == tab) 0xFF4ADE80.toInt() else 0xFF6B7A8D.toInt())
            v.typeface = if (i == tab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // ==================== 对话渲染 ====================

    private fun addBubble(text: String, fromUser: Boolean) {
        val tv = TextView(this).apply {
            setText(text)
            textSize = if (fromUser) 13.5f else 13f
            setTextColor(if (fromUser) 0xFF08130C.toInt() else 0xFFE6E9EF.toInt())
            setPadding(dp(11), dp(8), dp(11), dp(8))
            typeface = if (fromUser) Typeface.DEFAULT else Typeface.MONOSPACE
            setTextIsSelectable(true)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (fromUser) 0xFF4ADE80.toInt() else 0xFF1B212A.toInt())
            }
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(4)
            bottomMargin = dp(4)
            if (fromUser) leftMargin = dp(44) else rightMargin = dp(44)
        }
        chatList.addView(tv, lp)
        scrollChatToBottom()
    }

    private fun addSystemLine(text: String) {
        val tv = TextView(this).apply {
            setText(text)
            textSize = 11f
            setTextColor(0xFF5C6B7E.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            typeface = Typeface.MONOSPACE
        }
        chatList.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
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
            attachInfo.text = "已附 ${pendingShots.size} 张图（发送时一起给 AI）  ✕ 清除"
            attachInfo.setOnClickListener {
                pendingShots.clear()
                updateAttachInfo()
            }
        }
    }

    // ==================== 发送 ====================

    private fun send() {
        if (running) {
            toast("AI 正在干活，先点停止或等它做完")
            return
        }
        val text = inputEt.text.toString().trim()
        if (text.isEmpty() && pendingShots.isEmpty()) return

        val cfg = cfgStore.active()
        val local = cfg.provider.baseUrl.contains("127.0.0.1") ||
            cfg.provider.baseUrl.contains("localhost")
        if (cfg.apiKey.isBlank() && !local) {
            showSettings()
            toast("先填 ${cfg.provider.label} 的 API Key")
            return
        }

        val images = pendingShots.toList()
        pendingShots.clear()
        updateAttachInfo()
        inputEt.setText("")

        addBubble(
            if (text.isEmpty()) "（看这张图）" else text,
            true
        )
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

        Thread {
            try {
                r.run(history, text.ifEmpty { "请看我发的图片，并按图片内容改进游戏。" }, images)
            } catch (t: Throwable) {
                main.post { addSystemLine("✗ 异常: ${t.javaClass.simpleName}: ${t.message}") }
            } finally {
                main.post {
                    running = false
                    sendBtn.isEnabled = true
                }
            }
        }.start()
    }

    private fun onAgentEvent(ev: String) {
        main.post {
            when {
                ev.startsWith("AI: ") -> addBubble(ev.removePrefix("AI: "), false)
                ev.startsWith("✗") || ev.startsWith("⚠") || ev.startsWith("■") -> addSystemLine(ev)
                ev.startsWith("✓") -> addSystemLine(ev)
                ev.startsWith("⋯") -> addSystemLine(ev)
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

    // ==================== 发布页 ====================

    private fun buildPublishPage() {
        fun big(t: String): TextView = TextView(this).apply {
            text = t
            textSize = 15f
            setTextColor(0xFFE6E9EF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(10))
        }

        fun item(t: String, sub: String, onClick: () -> Unit): LinearLayout {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0xFF141A21.toInt())
                }
                setOnClickListener { onClick() }
            }
            box.addView(TextView(this).apply {
                text = t
                textSize = 14f
                setTextColor(0xFFE6E9EF.toInt())
            })
            box.addView(TextView(this).apply {
                text = sub
                textSize = 11f
                setTextColor(0xFF6B7A8D.toInt())
                setPadding(0, dp(3), 0, 0)
            })
            return box
        }

        pubPage.addView(big("工程"))

        pubPage.addView(item("导出全部游戏 zip", "打包到应用外部目录，用 MT 管理器可直接取", { exportZip() }),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        pubPage.addView(item("查看当前游戏文件", "列出 ${currentGame} 的文件与体积", { showTree() }),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        pubPage.addView(item("查看 console 输出", "游戏里的 log / warn / error", { showConsole() }),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        pubPage.addView(item("复制工程路径", gameRoot.absolutePath, {
            copyToClipboard(gameRoot.absolutePath)
        }), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        pubPage.addView(item("清空对话历史", "AI 会忘掉之前的上下文", {
            history.clear()
            chatList.removeAllViews()
            addSystemLine("对话历史已清空")
        }), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        pubPage.addView(item("切换当前游戏", "列出所有游戏并切换预览", { pickGame() }),
            LinearLayout.LayoutParams(-1, -2))
    }

    private fun exportZip() {
        Thread {
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                val out = File(dir, "h5games_${System.currentTimeMillis()}.zip")
                ZipOutputStream(out.outputStream()).use { z ->
                    for (g in gameRoot.listFiles().orEmpty().sortedBy { it.name }) {
                        if (!g.isDirectory) continue
                        for (f in g.walkTopDown().filter { it.isFile }) {
                            z.putNextEntry(ZipEntry("${g.name}/${f.relativeTo(g).path}"))
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                    }
                }
                main.post { addSystemLine("已导出：${out.absolutePath}") }
            }.onFailure { e ->
                main.post { addSystemLine("导出失败：${e.message}") }
            }
        }.start()
    }

    private fun showTree() {
        Thread {
            val d = File(gameRoot, currentGame)
            val sb = StringBuilder("${d.absolutePath}\n\n")
            val files = d.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
            if (files.isEmpty()) sb.append("(空)")
            files.forEach { sb.append(it.relativeTo(d).path).append("   ").append(it.length()).append("B\n") }
            main.post { alert("${currentGame} 文件", sb.toString()) }
        }.start()
    }

    private fun showConsole() {
        alert("console 输出", logs.tail(80).joinToString("\n").ifBlank { "(暂无输出)" })
    }

    private fun pickGame() {
        val dirs = gameRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        if (dirs.isEmpty()) {
            toast("还没有游戏")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择游戏")
            .setItems(dirs.toTypedArray()) { _, i ->
                currentGame = dirs[i]
                openGame(dirs[i])
                refreshHeader()
                addSystemLine("已切换到 ${dirs[i]}")
            }
            .show()
    }

    private fun alert(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg.take(8000))
            .setPositiveButton("好", null)
            .show()
    }

    private fun copyToClipboard(s: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("path", s))
        toast("已复制")
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

    // ==================== 顶栏刷新 ====================

    private fun refreshHeader() {
        val cfg = cfgStore.active()
        val skill = SkillPresets.byId(cfgStore.skillId)
        titleTv.text = "H5 引擎 · $currentGame"
        subTv.text = "${cfg.provider.label} / ${cfg.model}" +
            (if (cfg.vision) " 👁" else "") +
            "   ·   ${skill.label}   ·   Key ${AiConfigStore.mask(cfg.apiKey)}"
        modelBtn.text = "✦ ${cfg.provider.label} / ${cfg.model} ▾"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ==================== 设置 ====================

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }

        fun label(t: String): TextView = TextView(this).apply {
            text = t
            textSize = 12f
            setTextColor(0xFF6B7A8D.toInt())
            setPadding(0, dp(10), 0, 0)
        }

        val providerSp = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                AiProviders.ALL.map { it.label }
            )
        }
        providerSp.setSelection(
            AiProviders.ALL.indexOfFirst { it.id == cfgStore.providerId }.coerceAtLeast(0)
        )

        val keyEt = EditText(this).apply {
            hint = "API Key（只存本机，别外传）"
            textSize = 13f
            setText(cfgStore.keyOf(cfgStore.providerId))
        }

        val modelSp = Spinner(this)
        val modelEt = EditText(this).apply { hint = "模型名，可直接手填"; textSize = 13f }

        fun syncModels(pos: Int) {
            val p = AiProviders.ALL[pos]
            modelSp.adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                p.models.map { "${it.label} · ${it.name}" + if (it.vision) " 👁" else "" }
            )
            modelEt.setText(cfgStore.modelOf(p.id))
        }

        val skillSp = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                SkillPresets.ALL.map { it.label + if (it.needVision) " 👁建议" else "" }
            )
        }
        skillSp.setSelection(
            SkillPresets.ALL.indexOfFirst { it.id == cfgStore.skillId }.coerceAtLeast(0)
        )

        syncModels(providerSp.selectedItemPosition)

        providerSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val pv = AiProviders.ALL[pos]
                keyEt.hint = "${pv.keyHint}（${pv.label}）"
                keyEt.setText(cfgStore.keyOf(pv.id))
                syncModels(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        modelSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                AiProviders.ALL[providerSp.selectedItemPosition].models.getOrNull(pos)
                    ?.let { modelEt.setText(it.name) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val tempEt = EditText(this).apply {
            hint = "temperature"
            textSize = 13f
            setText(cfgStore.temperature.toString())
        }
        val stepsEt = EditText(this).apply {
            hint = "单次最多工具轮数"
            textSize = 13f
            setText(cfgStore.maxSteps.toString())
        }
        val visionCb = CheckBox(this).apply {
            text = "模型看不了图时，把截图存盘并把路径告诉它"
            textSize = 12f
            isChecked = cfgStore.visionFallback
        }

        box.addView(label("厂商（国内外主流都预设好了，Key 各家独立保存）"))
        box.addView(providerSp)
        box.addView(label("API Key"))
        box.addView(keyEt)
        box.addView(label("常用模型"))
        box.addView(modelSp)
        box.addView(label("实际调用模型名"))
        box.addView(modelEt)
        box.addView(label("技能包（决定 AI 的工作方式）"))
        box.addView(skillSp)
        box.addView(label("temperature"))
        box.addView(tempEt)
        box.addView(label("最多工具轮数"))
        box.addView(stepsEt)
        box.addView(visionCb)

        AlertDialog.Builder(this)
            .setTitle("AI 设置")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val pv = AiProviders.ALL[providerSp.selectedItemPosition]
                cfgStore.providerId = pv.id
                cfgStore.setKey(pv.id, keyEt.text.toString())
                val m = modelEt.text.toString().trim()
                if (m.isNotEmpty()) cfgStore.setModel(pv.id, m)
                cfgStore.skillId = SkillPresets.ALL[skillSp.selectedItemPosition].id
                cfgStore.temperature = tempEt.text.toString().toDoubleOrNull() ?: 0.4
                cfgStore.maxSteps = stepsEt.text.toString().toIntOrNull() ?: 40
                cfgStore.visionFallback = visionCb.isChecked
                history.clear()
                refreshHeader()
                addSystemLine("已切换：${pv.label} / ${cfgStore.modelOf(pv.id)}（历史已清空）")
            }
            .setNeutralButton("清空该家 Key") { _, _ ->
                val pv = AiProviders.ALL[providerSp.selectedItemPosition]
                cfgStore.clearKey(pv.id)
                keyEt.setText("")
                refreshHeader()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestIgnoreBattery() {
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (t: Throwable) {
            toast("请在系统设置里把本应用设为「无限制」")
        }
    }

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
    }

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