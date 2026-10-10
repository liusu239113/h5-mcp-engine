package com.mcp.h5engine

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Cindy 风格对话层（WebView）。
 *
 * 设计原则：**只做「表现层」**，一行都不碰原有的聊天逻辑。
 *  - 上行（JS→原生）：HexoraNative.post(JSON)，交给 [host] 处理。
 *  - 下行（原生→JS）：[onEvent]，把 MainActivity.onAgentEvent 的原话事件
 *    翻译成 Cindy 的事件模型（text / thinking / tool / image / status / error …）。
 *
 * 为什么用 WebView 而不是手搓 View：
 *   Cindy 的对话流是 web 实现的，复刻它的卡片体系（思考块 / 工具组 / diff /
 *   计划审批 / 汇总）用 HTML 最省事，也最容易跟上游对齐样式；手机上单页面 WebView
 *   性能完全够用，且与主界面隔离，不会拖垮原有代码。
 */
class CindyUi(
    private val act: MainActivity,
    private val cfg: AiConfigStore,
    private val host: Host
) {

    interface Host {
        /** JS 上行动作。a = send/stop/newChat/workspace/skills/publish/open/… */
        fun onCindyAction(a: String, o: JSONObject)
    }

    lateinit var web: WebView
        private set

    private var ready = false
    private val snack = ArrayDeque<String>()

    // ---- 运行计时 / 速率：每秒推一次给界面（对应「正在工作… X分Y秒 · Z tok/s」）----
    private val tickHandle = Handler(Looper.getMainLooper())
    private var busyOn = false
    private var busyAt = 0L
    private var busyTok = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (!busyOn) return
            val sec = ((SystemClock.elapsedRealtime() - busyAt) / 1000L).coerceAtLeast(0)
            val now = TokenStats.inTokens + TokenStats.outTokens
            val rate = if (sec > 0) (now - busyTok).toDouble() / sec.toDouble() else 0.0
            push(
                JSONObject().put("t", "tick").put("sec", sec)
                    .put("tok", Math.round(rate * 10.0) / 10.0)
                    .put("total", now)
            )
            tickHandle.postDelayed(this, 1000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(): WebView {
        val w = WebView(act)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = true
        w.setBackgroundColor(0xFF181818.toInt())
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                while (snack.isNotEmpty()) raw(snack.removeFirst())
                // 页面就绪后主动推一次配置，免得用户点开设置是空的
                buildConfig()
            }
        }
        w.addJavascriptInterface(Bridge(), "HexoraNative")
        w.loadUrl("file:///android_asset/cindy/index.html")
        web = w
        return w
    }

    private fun raw(json: String) {
        act.runOnUiThread {
            try {
                web.evaluateJavascript("UI.on(JSON.parse(" + JSONObject.quote(json) + "))", null)
            } catch (e: Exception) {
                Log.w("CindyUi", "eval failed: $e")
            }
        }
    }

    fun push(json: String) {
        if (!ready) snack.addLast(json) else raw(json)
    }

    fun push(o: JSONObject) = push(o.toString())

    // ==================== 上行 ====================

    private inner class Bridge {
        @JavascriptInterface
        fun post(s: String) {
            try {
                val o = JSONObject(s)
                val a = o.optString("a")
                act.runOnUiThread { host.onCindyAction(a, o) }
            } catch (e: Exception) {
                Log.w("CindyUi", "bad post: $s / $e")
            }
        }
    }

    // ==================== 配置 ====================

    fun buildConfig() {
        val provs = JSONArray()
        for (p in AiProviders.ALL) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.label)
            o.put("baseUrl", p.baseUrl)
            o.put("keyHint", p.keyHint)
            o.put("group", p.group)
            o.put("editableBaseUrl", p.editableBaseUrl)
            val ms = JSONArray()
            for (m in p.models) {
                ms.put(
                    JSONObject()
                        .put("id", m.name)
                        .put("label", m.label)
                        .put("vision", m.vision)
                )
            }
            o.put("models", ms)
            provs.put(o)
        }
        val keys = JSONObject()
        for (p in AiProviders.ALL) {
            val k = cfg.keyOf(p.id)
            // 只回「打没打过码」，真 Key 不下发到 JS（免得被注入脚本顺手读走）
            if (k.isNotBlank()) keys.put(p.id, "已保存 ••••" + k.takeLast(4))
        }
        val cur = JSONObject()
            .put("provider", cfg.providerId)
            .put("model", cfg.modelOf(cfg.providerId))
            .put("baseUrl", cfg.baseUrlOf(cfg.providerId))
            .put("steps", cfg.maxSteps)
            .put("temperature", cfg.temperature)

        push(
            JSONObject()
                .put("t", "config")
                .put("providers", provs)
                .put("keys", keys)
                .put("cur", cur)
                .toString()
        )
    }

    fun saveConfig(o: JSONObject) {
        val pid = o.optString("provider")
        if (pid.isBlank()) return
        val p = AiProviders.ALL.firstOrNull { it.id == pid } ?: return
        cfg.providerId = pid
        val model = o.optString("model").trim()
        if (model.isNotBlank()) cfg.setModel(pid, model)
        val key = o.optString("key").trim()
        if (key.isNotBlank()) cfg.setKey(pid, key)
        val base = o.optString("baseUrl").trim()
        if (base.isNotBlank() && p.editableBaseUrl) cfg.setBaseUrl(pid, base)
        if (o.has("steps")) cfg.maxSteps = o.optInt("steps", cfg.maxSteps)
        if (o.has("temperature")) cfg.temperature = o.optDouble("temperature", cfg.temperature)
        toast("模型已切换")
        buildConfig()
    }

    // ==================== 下行事件翻译 ====================

    /**
     * 把 MainActivity 的原话事件翻译成 Cindy 事件模型。
     *
     * 原事件词表（见 onAgentEvent）：
     *   AIFINAL: / AI: / THINK: / TOOLRUN: / TOOL: / TOOLFAIL: / SHOT: /
     *   ASSET: / STEP: / [失败] / AUTHREQ: / RELOAD / INFO: / 其它=正文流
     */
    fun onEvent(ev: String) {
        try {
            when {
                ev.startsWith("AIFINAL: ") -> push(
                    JSONObject().put("t", "text").put("final", true)
                        .put("text", ev.removePrefix("AIFINAL: "))
                )
                ev.startsWith("AI: ") -> push(
                    JSONObject().put("t", "textdelta")
                        .put("text", ev.removePrefix("AI: ") + "\n")
                )
                ev.startsWith("[失败]") -> push(
                    JSONObject().put("t", "error")
                        .put("text", ev.removePrefix("[失败] 请求失败：").trim())
                )
                ev.startsWith("STEP:") -> push(
                    JSONObject().put("t", "step")
                        .put("n", ev.removePrefix("STEP:").trim().toIntOrNull() ?: 0)
                )
                ev.startsWith("THINK:") -> push(
                    JSONObject().put("t", "thinking")
                        .put("text", ev.removePrefix("THINK:"))
                )
                ev.startsWith("TOOLRUN:") -> {
                    val body = ev.removePrefix("TOOLRUN:").trim()
                    push(
                        JSONObject().put("t", "tool").put("state", "run")
                            .put("name", body.substringAfter("[").substringBefore("]"))
                            .put("target", body.substringAfter("]", "").trim())
                    )
                }
                ev.startsWith("TOOLFAIL:") -> {
                    val l = ev.removePrefix("TOOLFAIL:").trim()
                    push(
                        JSONObject().put("t", "tool").put("state", "fail")
                            .put("name", l.substringAfter("[").substringBefore("]"))
                            .put("head", l.substringAfter("] ", "").trim())
                    )
                }
                ev.startsWith("TOOL:") -> {
                    val l = ev.removePrefix("TOOL:").trim()
                    push(
                        JSONObject().put("t", "tool").put("state", "ok")
                            .put("name", l.substringAfter("[").substringBefore("]"))
                            .put("head", l.substringAfter("] ", "").trim())
                    )
                }
                ev.startsWith("SHOT:") -> pushImage(ev.removePrefix("SHOT:").trim())
                ev.startsWith("ASSET:") -> {
                    val p = ev.removePrefix("ASSET:").trim()
                    push(
                        JSONObject().put("t", "card").put("kind", "asset")
                            .put("k", "素材").put("v", p)
                    )
                    pushImage(p)
                }
                ev.startsWith("AUTHREQ:") -> push(
                    JSONObject().put("t", "sys").put("text", "需要 Maker 授权，正在取链接…")
                )
                ev == "RELOAD" -> push(
                    JSONObject().put("t", "toast").put("text", "预览已刷新")
                )
                ev.startsWith("INFO: ") -> push(
                    JSONObject().put("t", "sys").put("text", ev.removePrefix("INFO: "))
                )
                else -> if (ev.isNotBlank()) {
                    push(JSONObject().put("t", "textdelta").put("text", ev))
                }
            }
        } catch (e: Exception) {
            Log.w("CindyUi", "translate failed: $e")
        }
    }

    fun toast(text: String) =
        push(JSONObject().put("t", "toast").put("text", text))

fun busy(on: Boolean) {
        busyOn = on
        if (on) {
            busyAt = SystemClock.elapsedRealtime()
            busyTok = TokenStats.inTokens + TokenStats.outTokens
            tickHandle.removeCallbacks(ticker)
            tickHandle.postDelayed(ticker, 1000L)
        } else {
            tickHandle.removeCallbacks(ticker)
        }
        push(JSONObject().put("t", "busy").put("on", on))
    }

    fun clear() = push(JSONObject().put("t", "clear"))

    fun title(t: String) = push(JSONObject().put("t", "title").put("text", t))

    fun status(text: String) = push(JSONObject().put("t", "status").put("text", text))

    /** 图片走内联 data URI：file:// 的跨源限制会让 WebView 显示不出来。 */
    private fun pushImage(path: String) {
        val url = thumbDataUrl(File(path))
        if (url == null) {
            push(JSONObject().put("t", "card").put("kind", "file").put("k", "文件").put("v", path))
        } else {
            push(JSONObject().put("t", "image").put("src", url).put("path", path))
        }
    }

    private fun thumbDataUrl(f: File): String? {
        if (!f.exists() || f.length() == 0L) return null
        return try {
            val raw = BitmapFactory.decodeFile(f.absolutePath) ?: return null
            val max = 360
            val scale = minOf(1f, max.toFloat() / maxOf(raw.width, raw.height))
            val bmp: Bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * scale).toInt().coerceAtLeast(1),
                    (raw.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else raw
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 78, bos)
            "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        } catch (o: OutOfMemoryError) {
            null
        }
    }
}
