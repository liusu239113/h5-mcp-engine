package com.mcp.h5engine

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.io.FileOutputStream

/**
 * Maker（UrhoX）工程的预览通道。
 *
 * 为什么不能用系统 WebView：
 *   Android WebView 缺少站点隔离（Fission）。即使 COOP/COEP 响应头都对、
 *   反射调 setEnableSharedArrayBuffer(true)，window.crossOriginIsolated 仍然是 false，
 *   SharedArrayBuffer 不可用 → UrhoX 的 WASM 多线程引擎起不来 → 白屏。
 *   GeckoView（Firefox 内核）默认开 Fission，原生支持 SharedArrayBuffer 与 WASM 多线程，
 *   而且全部在 App 进程内渲染，不需要跳到外部浏览器。
 *
 * 还有第二层（手机端必挂的原因）：
 *   maker.taptap.cn 在 JS 里读 navigator.userAgent 有没有 "Electron" 来决定走
 *   electronAPI（IPC）还是浏览器 getDisplayMedia —— 手机端走后者会直接报
 *   "recording-request-start is not supported by this user agent"。
 *   所以 assets/web_extensions/makerbridge 会在页面里注入一个 Electron 外观的 electronAPI，
 *   并只改 JS 可见的 UA（HTTP 层仍是 iPad Safari，保住平板版布局和完整登录入口）。
 */
object MakerPreview {

    private const val TAG = "MakerPreview"

    /** Maker 控制台首页。拿到工程 appId 时会改走 app/<id>?tab=preview 深链 */
    const val CONSOLE = "https://maker.taptap.cn/"

    /**
     * HTTP 层伪装成 iPad Safari。
     * 配合 VIEWPORT_MODE_MOBILE，maker.taptap.cn 会下发平板版页面：
     * 触屏操作体验更好，而且登录面板齐全（TapTap 扫码 / QQ / 微信 / 手机号）。
     */
    private const val IPAD_UA =
        "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    @Volatile
    private var runtime: GeckoRuntime? = null

    /** 单例 GeckoRuntime：整个 App 一个内核进程，多次进出预览不重复初始化 */
    fun runtime(ctx: Context): GeckoRuntime {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val app = ctx.applicationContext

            // geckoview-config.yaml 必须落成真实文件：configFilePath 读的是磁盘路径
            val cfg = File(app.filesDir, "geckoview-config.yaml")
            runCatching {
                app.assets.open("geckoview-config.yaml").use { input ->
                    FileOutputStream(cfg).use { output -> input.copyTo(output) }
                }
            }.onFailure { Log.w(TAG, "写 geckoview-config.yaml 失败", it) }

            val settings = GeckoRuntimeSettings.Builder()
                .configFilePath(cfg.absolutePath)
                .javaScriptEnabled(true)
                // 命门：站点隔离 -> crossOriginIsolated -> SharedArrayBuffer -> WASM 多线程
                .fissionEnabled(true)
                .extensionsWebAPIEnabled(true)
                .extensionsProcessEnabled(true)
                .aboutConfigEnabled(true)
                .remoteDebuggingEnabled(true)
                .build()

            val r = GeckoRuntime.create(app, settings)

            // 注册 MakerBridge 内置扩展：JS 层把 UA 改成 Electron + 提供 electronAPI
            runCatching {
                r.webExtensionController.ensureBuiltIn(
                    "resource://android/assets/web_extensions/makerbridge/",
                    "makerbridge@hexora"
                )?.accept({ ext ->
                    Log.i(TAG, "makerbridge 已注册: ${ext?.location}")
                    ext?.setMessageDelegate(object : WebExtension.MessageDelegate {
                        override fun onMessage(
                            nativeApp: String,
                            msg: Any,
                            sender: WebExtension.MessageSender
                        ): GeckoResult<Any>? {
                            Log.d(TAG, "makerbridge message: $nativeApp")
                            return GeckoResult.fromValue(mapOf("ok" to true) as Any)
                        }

                        override fun onConnect(port: WebExtension.Port) {
                            Log.d(TAG, "makerbridge port: ${port.name}")
                        }
                    }, "makerbridge")
                }, { t -> Log.w(TAG, "makerbridge 注册失败", t) })
            }.onFailure { Log.w(TAG, "ensureBuiltIn 异常", it) }

            runtime = r
            return r
        }
    }

    /** 新建一个「平板版」会话 */
    fun newSession(): GeckoSession = GeckoSession(
        GeckoSessionSettings.Builder()
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .userAgentOverride(IPAD_UA)
            .build()
    )

    /** 建一个已经绑好（已 open 的）会话的 GeckoView */
    fun newView(ctx: Context): GeckoView {
        val session = newSession()
        session.open(runtime(ctx))
        return GeckoView(ctx).apply { setSession(session) }
    }
}
