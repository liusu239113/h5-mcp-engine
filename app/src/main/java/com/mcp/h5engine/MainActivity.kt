package com.mcp.h5engine

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 宿主 = H5 游戏容器 + 内嵌 MCP 服务端
 *
 * 顶层 UI：状态栏（显示 AI 可连接的地址）+ WebView（游戏画面）
 *
 * AI（Operit / Claude / Cursor…）连上 http://<手机IP>:8765/mcp 之后，
 * 就能写文件、热重载、执行 JS、截图、模拟点击 —— 直接在手机上把 H5 游戏做出来。
 */
class MainActivity : AppCompatActivity(), GameUi {

    private lateinit var web: WebView
    private lateinit var bridge: EngineBridge
    private lateinit var statusTv: TextView
    private lateinit var addrTv: TextView
    private lateinit var toggleBtn: Button

    private val main = Handler(Looper.getMainLooper())
    private val logs = LogBuffer()

    private lateinit var gameRoot: File
    private var server: McpServer? = null

    @Volatile
    private var currentGame = "demo"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gameRoot = File(filesDir, "games").apply { mkdirs() }
        seedSharedRuntime()

        /* ---------------- UI ---------------- */

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 18)
            setBackgroundColor(0xFF12161C.toInt())
        }

        statusTv = TextView(this).apply {
            setTextColor(0xFF4ADE80.toInt())
            textSize = 13f
        }

        addrTv = TextView(this).apply {
            setTextColor(0xFF9FB3C8.toInt())
            textSize = 12f
            setPadding(0, 12, 0, 12)
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggleBtn = Button(this).apply { text = "停止服务" }
        val batteryBtn = Button(this).apply { text = "忽略电池优化" }
        val reloadBtn = Button(this).apply { text = "刷新" }
        btnRow.addView(toggleBtn)
        btnRow.addView(batteryBtn)
        btnRow.addView(reloadBtn)

        bar.addView(statusTv)
        bar.addView(addrTv)
        bar.addView(btnRow)

        web = WebView(this)
        rootView.addView(bar, LinearLayout.LayoutParams(-1, -2))
        rootView.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(rootView)

        /* ---------------- WebView ---------------- */

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = false
        }

        web.webChromeClient = WebChromeClient()

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
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
        }

        openGame(currentGame)

        /* ---------------- 交互 ---------------- */

        toggleBtn.setOnClickListener {
            if (server == null) startServer() else stopServer()
        }

        batteryBtn.setOnClickListener { requestIgnoreBattery() }

        reloadBtn.setOnClickListener {
            logs.add("[system] 手动刷新")
            web.reload()
        }

        startServer()
    }

    /* ===================== MCP 服务 ===================== */

    private fun startServer() {
        try {
            val s = McpServer(McpServer.DEFAULT_PORT, GameTools(this, gameRoot, this, logs))
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s

            statusTv.text = "● MCP 服务运行中  ·  端口 ${McpServer.DEFAULT_PORT}"
            addrTv.text = buildAddrText()
            toggleBtn.text = "停止服务"
            logs.add("[system] MCP 服务已启动 :${McpServer.DEFAULT_PORT}")
        } catch (t: Throwable) {
            statusTv.text = "✕ 启动失败: ${t.message}"
        }
    }

    private fun stopServer() {
        runCatching { server?.stop() }
        server = null
        statusTv.text = "○ 服务已停止"
        addrTv.text = "启动后这里会显示 AI 可连接的地址"
        toggleBtn.text = "启动服务"
        logs.add("[system] MCP 服务已停止")
    }

    private fun buildAddrText(): String {
        val port = McpServer.DEFAULT_PORT
        val ip = lanIp()
        val lan = if (ip != null) "http://$ip:$port/mcp" else "（未连接 Wi-Fi / 热点）"
        return "本机地址    http://127.0.0.1:$port/mcp\n局域网地址  $lan"
    }

    private fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (t: Throwable) {
        null
    }

    private fun requestIgnoreBattery() {
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (t: Throwable) {
            Toast.makeText(this, "请在系统设置里把本应用设为「无限制」", Toast.LENGTH_LONG).show()
        }
    }

    /* ===================== GameUi 实现 ===================== */

    override fun currentGame(): String = currentGame

    override fun launch(gameId: String): String {
        if (gameId.isBlank()) return "参数 game 为空"
        if (!File(File(gameRoot, gameId), "index.html").exists()) {
            return "找不到 $gameId/index.html（先用 game_create 创建）"
        }
        openGame(gameId)
        return "已启动 $gameId"
    }

    private fun openGame(id: String) {
        currentGame = id
        val dir = File(gameRoot, id).apply { mkdirs() }
        bridge.setSandbox(dir)

        val url = "https://appassets.androidplatform.net/games/$id/index.html"
        main.post {
            logs.add("[system] 打开游戏 $id")
            web.loadUrl(url)
        }
    }

    override fun reload(): String {
        main.post {
            logs.add("[system] 热重载")
            web.reload()
        }
        return "已重载当前游戏"
    }

    override fun evalJs(code: String, timeoutMs: Long): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return "不要在 UI 线程调用"

        val latch = CountDownLatch(1)
        val holder = AtomicReference<String>("null")

        main.post {
            runCatching {
                web.evaluateJavascript(code) { v ->
                    holder.set(v ?: "null")
                    latch.countDown()
                }
            }.onFailure {
                holder.set("\"eval 失败: ${it.message}\"")
                latch.countDown()
            }
        }

        val ok = latch.await(timeoutMs.coerceIn(500, 60_000), TimeUnit.MILLISECONDS)
        return if (ok) holder.get() else "超时 (${timeoutMs}ms)"
    }

    override fun screenshotBase64(maxWidth: Int): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null

        val latch = CountDownLatch(1)
        val holder = AtomicReference<String?>()

        main.post {
            runCatching {
                val w = web.width.coerceAtLeast(1)
                val h = web.height.coerceAtLeast(1)

                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                web.draw(Canvas(bmp))

                val out = if (maxWidth > 0 && w > maxWidth) {
                    val sh = (h * (maxWidth.toFloat() / w)).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bmp, maxWidth, sh, true)
                } else bmp

                val bos = ByteArrayOutputStream()
                out.compress(Bitmap.CompressFormat.PNG, 92, bos)
                holder.set(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
            }.onFailure { holder.set(null) }
            latch.countDown()
        }

        latch.await(10, TimeUnit.SECONDS)
        return holder.get()
    }

    override fun tap(x: Float, y: Float) {
        evalJs(
            """(function(){
                 var t = document.elementFromPoint($x, $y) || document.body;
                 var o = { bubbles:true, cancelable:true, clientX:$x, clientY:$y, view:window };
                 ['mousedown','mouseup','click'].forEach(function(n){
                    t.dispatchEvent(new MouseEvent(n, o));
                 });
                 return 'ok';
               })()""",
            4000
        )
    }

    override fun text(s: String) {
        val quoted = JSONObject.quote(s)
        evalJs(
            """(function(){
                 var el = document.activeElement;
                 if (!el || !('value' in el)) return 'no-focus';
                 el.value = $quoted;
                 el.dispatchEvent(new Event('input', { bubbles:true }));
                 el.dispatchEvent(new Event('change', { bubbles:true }));
                 return 'ok';
               })()""",
            4000
        )
    }

    /* ===================== 杂项 ===================== */

    /** 把运行时 engine.js 复制到 games/_shared/，所有游戏共用一份 */
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
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        web.destroy()
        super.onDestroy()
    }
}