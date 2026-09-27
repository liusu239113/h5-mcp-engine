package com.mcp.h5engine

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * 宿主 Activity
 *
 * 资源加载策略（关键）：
 *  - assets/games/... 映射到 https://appassets.androidplatform.net/assets/games/...
 *  - 沙箱目录（filesDir/games/<id>）映射到 https://appassets.androidplatform.net/games/...
 *
 * 用 https + 虚拟域名而不是 file://，好处：
 *   1) 是 secure context → ServiceWorker / WebGL / crypto.subtle / WebAudio 全可用
 *   2) fetch 不会被 file:// 的 CORS 策略打死
 *   3) 沙箱目录天然隔离，游戏只能读自己的存档
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: EngineBridge

    /** 换成你的游戏 id，或从 Intent / 游戏列表里动态取 */
    private val gameId = "demo"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sandbox = File(filesDir, "games/$gameId").apply { mkdirs() }

        web = WebView(this)
        setContentView(web, ViewGroup.LayoutParams(-1, -1))

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            // 不需要 file:// 了，全部走 AssetLoader
            allowFileAccess = false
            allowContentAccess = false

            // 游戏基本都要：音频/视频不要求用户手势
            mediaPlaybackRequiresUserGesture = false

            // 本地资源天天在变，别让 WebView 缓存旧版本
            cacheMode = WebSettings.LOAD_DEFAULT

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false  // 本地游戏资源没必要，还会误拦
            }
        }

        web.webChromeClient = WebChromeClient()

        bridge = EngineBridge(web, sandbox)
        web.addJavascriptInterface(bridge, "Native")

        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/games/", WebViewAssetLoader.InternalStoragePathHandler(this, sandbox))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
        }

        web.loadUrl("https://appassets.androidplatform.net/assets/games/$gameId/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}