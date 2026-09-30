package com.mcp.h5engine

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 宿主（MainActivity）需要提供给发布面板的能力。
 *
 * 为什么不直接引用 MainActivity：那边全是 private 成员，而且已经七千多行。
 * 用一个窄接口把依赖说清楚，面板自己就是可测、可读的。
 */
interface PublishHost {
    val pubCtx: Context
    val pubPal: Palette
    fun pubDp(v: Int): Int
    fun pubToast(s: String)
    fun pubOpenUrl(url: String)
    fun pubConfirm(title: String, msg: String, onOk: () -> Unit)
    /** 选一张本地图片（相册），回调里给的是已经拷进应用私有目录的文件 */
    fun pubPickImage(onPicked: (File) -> Unit)
    /** 把一段需求丢给 AI 去干活（比如「生成一张 16:9 的宣传图」） */
    fun pubAskAi(prompt: String)
    /** 素材工作区目录（AI 生成的图、用户上传的图都落这儿） */
    fun pubWorkspaceDir(): File
    /** 把外部文件收进工作区，返回收好之后的新文件 */
    fun pubIngest(src: File, kind: String): File
}

/**
 * 发布页 —— 对齐 TapTap Maker 的「发布到 TapTap」。
 *
 *   ┌ 发布到 TapTap                      前往开发者中心 ↗ ┐
 *   │ [登录卡片 / 当前账号]                              │
 *   │ 发布哪个游戏：[篮球人生：篮途 ▾]                    │
 *   │ ┌ 游戏基本信息        5/5 已完成 ⌄ ┐               │
 *   │ │  游戏 icon   [图]  ✦生成  ↑上传  ✕删除          │
 *   │ │  游戏名称    篮球人生：篮途                      │
 *   │ └───────────────────────────────────┘             │
 *   │ ┌ 游戏资料            3/3 已完成 ⌄ ┐               │
 *   │ │  游戏简介 / 游戏截图 / 实机视频                  │
 *   │ └───────────────────────────────────┘             │
 *   │ ┌ 宣传推广物料        3/3 已完成 ⌄ ┐               │
 *   │ └───────────────────────────────────┘             │
 *   │        [ 更新线上版本 ]                            │
 *   └────────────────────────────────────────────────────┘
 *
 * 数据来源全部是 CLI：`analyze-app-status` 给进度和阻断项，`get-app-module` 给字段值。
 * **不自己硬编码字段表** —— 官方字段会随平台/游戏类型变，硬编码必然过期。
 */
class PublishPanel(
    private val host: PublishHost,
    private val container: LinearLayout
) {

    private val pal get() = host.pubPal
    private val dp = { v: Int -> host.pubDp(v) }

    // ---------- 状态 ----------
    @Volatile private var busy = false
    /** 当前账号下的厂商 id（选游戏要用） */
    private var devId = ""
    /** 当前选中的游戏 */
    private var appId = ""
    private var appTitle = ""
    /** analyze-app-status 的结果缓存 */
    private var status: JSONObject? = null

    /**
     * 各模块的「已完成 / 总数」——发布页上那个绿色的 `3/3 已完成`。
     *
     * 来源是 `get-app-module`：只统计**可见且必填**的字段，
     * 其中 current_value 非空的算已完成。这跟官方页面的口径一致，
     * 也**不会因为平台/游戏类型不同而算错**（字段集合本来就是按当前条件投影出来的）。
     */
    private val moduleStats = HashMap<String, Pair<Int, Int>>()

    /**
     * 各字段的原始数据（`current_value` / `image_spec` / `required` …），按 field_id 存。
     *
     * 素材槽位要用它显示「这个位置现在是什么、该传什么规格」——
     * 光有「3/3 已完成」这种计数不够，用户要知道**具体哪个位置空着**。
     */
    private val fieldCache = HashMap<String, JSONObject>()

    /** 拉各模块字段统计（发布页那三个分组用得到的那几个模块） */
    private fun loadModules() {
        if (appId.isBlank() || devId.isBlank()) return
        for (m in GROUP_MODULES) {
            val r = TapCli.raw(
                host.pubCtx,
                listOf("app", "get-app-module", "--dev-id", devId, "--app-id", appId, "--module", m),
                timeoutMs = 90_000
            )
            if (!r.ok) continue
            val fields = r.obj("result")?.optJSONObject("fields")
                ?: r.data.optJSONObject("fields")
                ?: continue
            var total = 0
            var done = 0
            for (k in fields.keys()) {
                val f = fields.optJSONObject(k) ?: continue
                // 全量缓存：素材槽位要按 field_id 查当前值（包括非必填的素材字段）
                fieldCache[k] = f
                if (!f.optBoolean("visible", true)) continue
                if (!f.optBoolean("required", false)) continue
                total++
                if (isFilled(f.opt("current_value"))) done++
            }
            moduleStats[m] = done to total
            post { render() }
        }
    }

    /** 字段值算不算「已填」——空串 / null / 空数组 / 空对象都算没填 */
    private fun isFilled(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> false
        is String -> v.isNotBlank() && v != "null"
        is org.json.JSONArray -> v.length() > 0
        is JSONObject -> v.length() > 0
        else -> true
    }
    /** 折叠状态：三个分组默认都展开 */
    private val collapsed = mutableSetOf<String>()

    // ==================== 入口 ====================

    fun render() {
        container.removeAllViews()
        if (!TapCli.available(host.pubCtx)) {
            container.addView(notInstalledCard())
            return
        }
        container.addView(header())
        container.addView(authCard())
        if (devId.isNotBlank() || appId.isNotBlank()) {
            container.addView(gamePicker())
        }
        val st = status
        if (st != null) {
            container.addView(progressGroups(st))
            container.addView(actionBar(st))
        } else {
            container.addView(hintCard(
                "点上面的「检查发布状态」拉一次资料，" +
                    "就能看到每个模块还缺什么、能不能提审。"
            ))
        }
        // 素材槽位：按字段列，上传后直接写进对应字段（不是只丢进素材库）
        if (appId.isNotBlank()) container.addView(materialSlotsCard())
    }

    /** 进页面时自动拉一次：先看登录状态，登录了就顺带定位厂商 */
    fun refresh() {
        if (busy) return
        busy = true
        render()
        Thread {
            val st = TapCli.authStatus(host.pubCtx)
            val loggedIn = st.ok && looksLoggedIn(st)
            if (loggedIn && devId.isBlank()) {
                val devs = TapCli.developerList(host.pubCtx)
                devId = firstId(devs, "developer_id", "id", "developerId")
                curDevId = devId
            }
            busy = false
            post { render() }
        }.start()
    }

    private fun post(r: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(r)

    // ==================== 头部 ====================

    private fun header(): View = LinearLayout(host.pubCtx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), dp(10))
        addView(TextView(host.pubCtx).apply {
            setText("发布到 TapTap")
            textSize = 16f
            typeface = MEDIUM
            setTextColor(pal.text)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(host.pubCtx).apply {
            setText("开发者中心 ↗")
            textSize = 12.5f
            setTextColor(pal.accent)
            setPadding(dp(8), dp(6), dp(4), dp(6))
            setOnClickListener { host.pubOpenUrl("https://developer.taptap.cn/") }
        })
    }

    private fun notInstalledCard(): View = card(pal.errBg, pal.errBg).apply {
        addView(TextView(host.pubCtx).apply {
            setText("发布工具没随安装包进来")
            textSize = 13.5f
            typeface = MEDIUM
            setTextColor(pal.errText)
        })
        addView(TextView(host.pubCtx).apply {
            setText(
                "发布功能依赖 TapTap 官方 CLI。这个包在构建时没被下载进来（CI 日志里搜 " +
                    "「SKIP taptap-cli」能看到原因）。装一个带它的新版本即可，" +
                    "其它功能不受影响。"
            )
            textSize = 12f
            setTextColor(pal.errText)
            setPadding(0, dp(6), 0, 0)
        })
    }

    // ==================== 登录 ====================

    private fun looksLoggedIn(r: TapCli.Result): Boolean {
        val t = (r.raw + r.message).lowercase()
        if (t.contains("not logged") || t.contains("未登录") || t.contains("no credential")) return false
        // auth status 成功、且没提「未登录」，就当已登录
        return r.ok || r.data.length() > 0
    }

    private fun authCard(): View {
        val c = card(pal.card, pal.border)
        val title = TextView(host.pubCtx).apply {
            setText("TapTap 账号")
            textSize = 13.5f
            typeface = MEDIUM
            setTextColor(pal.text)
        }
        c.addView(title)
        val sub = TextView(host.pubCtx).apply {
            setText(if (devId.isBlank()) "还没登录，或还没定位到厂商" else "已定位厂商 $devId")
            textSize = 12f
            setTextColor(pal.sub)
            setPadding(0, dp(5), 0, 0)
        }
        c.addView(sub)

        val row = LinearLayout(host.pubCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(btn("登录 TapTap", primary = true) { login() })
        row.addView(btn("刷新状态") { refresh() })
        if (devId.isNotBlank()) row.addView(btn("退出登录") { logout() })
        c.addView(row)
        return c
    }

    /**
     * 登录 —— 走官方给 AI 用的两段式流程：
     *
     *   ① `auth login --no-wait --json`  →  拿 verification_url + device_code
     *   ② 开浏览器让用户点授权，同时拿 device_code 去 `auth login --device-code` 轮询
     *   ③ 轮询返回成功 → 刷新面板
     *
     * 用户不用管「登录完回来点刷新」这种话 —— 轮询会一直等到他点完为止。
     */
    private fun login() {
        if (busy) return
        busy = true
        host.pubToast("正在取授权链接…")
        Thread {
            val start = TapCli.authLoginStart(host.pubCtx)
            val url = start.data.optString("verification_url").ifBlank {
                Regex("\"verification_url\"\\s*:\\s*\"([^\"]+)\"")
                    .find(start.raw)?.groupValues?.get(1).orEmpty()
            }
            val code = start.data.optString("device_code").ifBlank {
                Regex("\"device_code\"\\s*:\\s*\"([^\"]+)\"")
                    .find(start.raw)?.groupValues?.get(1).orEmpty()
            }

            if (url.isBlank() || code.isBlank()) {
                busy = false
                // 网络类失败要说人话 —— 「TapTap OAuth request failed」这种原文
                // 用户看不懂，也不知道该干嘛。
                val isNet = start.raw.contains("network") || start.raw.contains("transport") ||
                    start.raw.contains("timeout") || start.raw.contains("refused")
                post {
                    host.pubConfirm(
                        "登录 TapTap 失败",
                        if (isNet) {
                            "连不上 TapTap 的服务器。常见原因：\n\n" +
                                "① **手机上开着 VPN / 代理**，而发布工具没走那个代理 ——\n" +
                                "   本版已自动把系统代理传给发布工具；如果还不行，\n" +
                                "   试着把 VPN 换成全局模式，或者暂时关掉它再登录。\n" +
                                "② 网络本身不通（切换 WiFi / 流量再试）。\n" +
                                "③ 公司网络 / 校园网有防火墙，拦了 taptap 的域名。\n\n" +
                                "原始错误：\n" + start.raw.take(500)
                        } else {
                            "没能拿到授权链接。原始输出：\n\n" + start.raw.take(700)
                        },
                        {}
                    )
                }
                return@Thread
            }

            post {
                host.pubOpenUrl(url)
                host.pubToast("已打开授权页 —— 在那边点确认，这边会自动完成")
            }

            // ② 轮询等他点完（CLI 自己会一直等到授权成功或过期）
            val done = TapCli.authLoginPoll(host.pubCtx, code)
            busy = false
            post {
                if (done.ok) {
                    host.pubToast("登录成功")
                    refresh()
                } else {
                    host.pubConfirm(
                        "登录没完成",
                        "可能没点确认、或者授权过期了。再点一次「登录 TapTap」即可。\n\n" +
                            done.raw.take(600),
                        {}
                    )
                }
            }
        }.start()
    }

    private fun logout() {
        host.pubConfirm("退出 TapTap 登录", "退出后发布相关操作都要重新授权。") {
            Thread {
                TapCli.authLogout(host.pubCtx)
                devId = ""; appId = ""; appTitle = ""; status = null
                post { render() }
            }.start()
        }
    }

    // ==================== 选游戏 ====================

    private fun gamePicker(): View {
        val c = card(pal.card, pal.border)
        c.addView(TextView(host.pubCtx).apply {
            setText("发布哪个游戏")
            textSize = 12.5f
            setTextColor(pal.sub)
        })
        val row = LinearLayout(host.pubCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(TextView(host.pubCtx).apply {
            setText(appTitle.ifBlank { if (appId.isBlank()) "还没选" else "app $appId" })
            textSize = 14f
            typeface = MEDIUM
            setTextColor(pal.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("选择") { pickGame() })
        c.addView(row)
        if (appId.isNotBlank()) {
            c.addView(TextView(host.pubCtx).apply {
                setText("app id $appId")
                textSize = 11f
                typeface = MONO
                setTextColor(pal.faint)
                setPadding(0, dp(6), 0, 0)
            })
        }
        return c
    }

    private fun pickGame() {
        if (busy) return
        if (devId.isBlank()) { host.pubToast("先登录，才能列出你的游戏"); return }
        busy = true
        host.pubToast("正在拉游戏列表…")
        Thread {
            val r = TapCli.appList(host.pubCtx, devId)
            busy = false
            val items = mutableListOf<Pair<String, String>>()   // id to title
            r.arr("list")?.let { a -> collectApps(a, items) }
            if (items.isEmpty()) collectApps(r.data.optJSONArray("items"), items)
            post {
                if (items.isEmpty()) {
                    host.pubConfirm(
                        "没读到游戏",
                        "这个账号下没列出游戏，或者返回结构变了。原始输出：\n\n" + r.raw.take(800),
                        {}
                    )
                } else {
                    val labels = items.map { it.second }.toTypedArray()
                    android.app.AlertDialog.Builder(host.pubCtx)
                        .setTitle("选择要发布的游戏")
                        .setItems(labels) { _, i ->
                            appId = items[i].first
                            appTitle = items[i].second
                            // 同步给 AI 工具：用户在页面上选好了，AI 就别再问一遍
                            curAppId = appId
                            curDevId = devId
                            status = null
                            render()
                            loadStatus()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }.start()
    }

    /** CLI 各接口的列表字段名不完全一致，这里做一层宽松抽取 */
    private fun collectApps(arr: JSONArray?, out: MutableList<Pair<String, String>>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = firstNonBlank(o, "app_id", "appId", "id")
            if (id.isBlank()) continue
            val title = firstNonBlank(o, "title", "name", "app_name").ifBlank { "app $id" }
            if (out.none { it.first == id }) out += id to title
        }
    }

    // ==================== 状态 / 进度 ====================

    fun loadStatus() {
        if (busy || appId.isBlank() || devId.isBlank()) return
        busy = true
        render()
        Thread {
            val r = TapCli.analyzeStatus(host.pubCtx, devId, appId)
            if (r.ok) status = r.data
            busy = false
            post {
                if (!r.ok) {
                    host.pubConfirm("拉取发布状态失败", r.message + "\n\n" + r.raw.take(600), {})
                }
                render()
            }
            // 再拉各模块的字段统计（算「3/3 已完成」用）。
            // 放在后面单独一轮：它要打好几个请求，慢，不该拖着状态显示不出来。
            loadModules()
        }.start()
    }

    /**
     * 三个分组：基本信息 / 游戏资料 / 宣传推广物料。
     *
     * 进度数**不自己算** —— 直接读 analyze-app-status 给的 blockers / warnings / suggestions，
     * 按模块归类。自己算的话，官方字段一变就全错。
     */
    private fun progressGroups(st: JSONObject): View {
        val col = LinearLayout(host.pubCtx).apply { orientation = LinearLayout.VERTICAL }
        val groups = listOf(
            Triple("basic", "游戏基本信息", listOf("basic-info", "developer-info", "platform-status")),
            Triple("assets", "游戏资料", listOf("assets-upload", "release-settings")),
            Triple("promo", "宣传推广物料", listOf("profile-promotion", "windows-exclusive"))
        )
        for ((key, title, modules) in groups) {
            col.addView(groupCard(key, title, modules, st))
        }
        // 阻断项单独列出来：这是「为什么还不能提审」的答案
        val blockers = st.optJSONArray("blockers")
        if (blockers != null && blockers.length() > 0) {
            val c = card(pal.errBg, pal.errBg)
            c.addView(TextView(host.pubCtx).apply {
                setText("还差 ${blockers.length()} 项才能提审")
                textSize = 13f
                typeface = MEDIUM
                setTextColor(pal.errText)
            })
            for (i in 0 until minOf(blockers.length(), 8)) {
                c.addView(TextView(host.pubCtx).apply {
                    setText("· " + blockerText(blockers.opt(i)))
                    textSize = 12f
                    setTextColor(pal.errText)
                    setPadding(0, dp(5), 0, 0)
                })
            }
            col.addView(c, lp())
        }
        return col
    }

    private fun blockerText(o: Any?): String = when (o) {
        is JSONObject -> firstNonBlank(o, "message", "detail", "code", "title")
            .ifBlank { o.toString().take(160) }
        else -> o?.toString().orEmpty().take(160)
    }

    /**
     * 一个分组卡 —— 对齐官方那套：
     *
     *   ┌────────────────────────────────────────┐
     *   │ 游戏资料                   3/3 已完成 ⌄ │   ← 标题 + 绿色进度 + 折叠箭头
     *   │ 游戏简介、游戏截图和实机视频都已准备完成。│   ← 一句人话描述
     *   │ ────────────────────────────────────── │
     *   │ [展开后] 具体字段 / 素材卡              │
     *   └────────────────────────────────────────┘
     */
    private fun groupCard(key: String, title: String, modules: List<String>, st: JSONObject): View {
        val c = card(pal.card, pal.border)
        val open = key !in collapsed

        val head = LinearLayout(host.pubCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                if (open) collapsed += key else collapsed -= key
                render()
            }
        }
        head.addView(TextView(host.pubCtx).apply {
            setText(title)
            textSize = 15f
            typeface = MEDIUM
            setTextColor(pal.text)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        // 进度：有模块统计就用「3/3 已完成」，否则退回「还有 N 项要补」
        val stat = groupStat(modules)
        if (stat != null) {
            val (done, total) = stat
            head.addView(TextView(host.pubCtx).apply {
                setText("$done/$total 已完成")
                textSize = 13f
                typeface = MEDIUM
                // 全齐 = 绿色（官方就是绿的）；没齐 = 提醒色
                setTextColor(if (done >= total && total > 0) pal.accent else pal.errText)
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        head.addView(TextView(host.pubCtx).apply {
            setText(if (open) "⌄" else "›")
            textSize = 15f
            setTextColor(pal.faint)
        })
        c.addView(head)

        // 描述行：官方那套是「XX、YY 都已准备完成。」/「还差 XX」
        c.addView(TextView(host.pubCtx).apply {
            setText(groupDesc(key, modules, stat, st))
            textSize = 12.5f
            setTextColor(pal.sub)
            setPadding(0, dp(6), 0, 0)
        })

        if (!open) return c

        c.addView(View(host.pubCtx).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(4) })

        // 展开内容：icon 预览卡（基本信息那组）+ 具体待办
        if (key == "basic") c.addView(iconSlotCard())

        val items = todoItems(st, modules)
        if (items.isEmpty()) {
            c.addView(TextView(host.pubCtx).apply {
                setText("都齐了，不用再补。")
                textSize = 12.5f
                setTextColor(pal.faint)
                setPadding(0, dp(10), 0, 0)
            })
        } else {
            for ((mod, msg) in items) {
                val row = LinearLayout(host.pubCtx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, 0)
                    isClickable = true
                    setOnClickListener {
                        host.pubAskAi("帮我看看 TapTap 发布资料这一项怎么补：$msg（模块 $mod）")
                    }
                }
                row.addView(TextView(host.pubCtx).apply {
                    setText("·")
                    textSize = 13f
                    setTextColor(pal.errText)
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) })
                row.addView(TextView(host.pubCtx).apply {
                    setText(msg)
                    textSize = 12.5f
                    setTextColor(pal.text)
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                // 官方的素材操作是**图标**，不是文字按钮 —— 这里跟着做
                row.addView(iconBtn("skill", "让 AI 处理这一项") {
                    host.pubAskAi(
                        "这个 TapTap 发布资料项还没补齐，请帮我处理：$msg" +
                            "（模块 $mod，app $appId，厂商 $devId）。" +
                            "先读一次当前值，需要我提供文案/图片就先问我。"
                    )
                })
                c.addView(row)
            }
        }
        return c
    }

    /** 这一组的 已完成/总数（把组内几个模块的数字合起来） */
    private fun groupStat(modules: List<String>): Pair<Int, Int>? {
        var done = 0
        var total = 0
        var any = false
        for (m in modules) {
            val s = moduleStats[m] ?: continue
            any = true
            done += s.first
            total += s.second
        }
        return if (any) done to total else null
    }

    /** 官方那种描述行：「XX、YY 都已准备完成。」/「还差 XX」 */
    private fun groupDesc(
        key: String,
        modules: List<String>,
        stat: Pair<Int, Int>?,
        st: JSONObject
    ): String {
        val what = when (key) {
            "basic" -> "游戏 icon、游戏名、游戏类型、发布厂商和开发者的话"
            "assets" -> "游戏简介、游戏截图和实机视频"
            else -> "宣传主图和其他推广图片"
        }
        val todo = countTodo(st, modules)
        return when {
            stat != null && stat.second > 0 && stat.first >= stat.second -> "$what 都已准备完成。"
            todo > 0 -> "$what 里还有 $todo 项没准备好。"
            else -> "$what 都已准备完成。"
        }
    }

    /**
     * 游戏 icon 预览卡 —— 对齐官方那张「大图 + 右上角图标按钮」。
     *
     * 官方是 ✦（AI 生成）/ ↑（上传）两个图标叠在图上；
     * 这里同样用图标，不用文字按钮。
     */
    private fun iconSlotCard(): View {
        val box = LinearLayout(host.pubCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val holder = FrameLayout(host.pubCtx)

        // 图（有就显示，没有就给个占位方块）
        val iv = ImageView(host.pubCtx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundCard(host.pubCtx, pal.cardAlt, pal.border, 12)
            clipToOutline = true
            setImageDrawable(LineIcon("image", pal.faint, 1.8f))
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        holder.addView(iv, FrameLayout.LayoutParams(dp(96), dp(96)))

        // 右上角两个图标按钮：✦ 让 AI 生成 / ↑ 上传
        val ops = LinearLayout(host.pubCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        ops.addView(iconBtn("skill", "让 AI 生成 icon") {
            host.pubAskAi(
                "帮我为这个游戏生成一张 TapTap 商店用的 icon（官方规格，1:1）。" +
                    "做完直接上传到素材库（app $appId，厂商 $devId）。"
            )
        })
        // 走统一的槽位上传（传素材库 + 写进 icon 字段两步）
        ops.addView(iconBtn("upload", "上传 icon") {
            uploadToSlot(SLOTS.first { it.fieldId == "icon" })
        })
        holder.addView(ops, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END })

        box.addView(holder, LinearLayout.LayoutParams(dp(96), dp(96)))
        box.addView(TextView(host.pubCtx).apply {
            setText("游戏 icon")
            textSize = 11.5f
            setTextColor(pal.faint)
            setPadding(0, dp(6), 0, 0)
        })
        return box
    }

    /** 圆形图标按钮（官方的素材操作就是这种） */
    private fun iconBtn(kind: String, desc: String, onClick: () -> Unit): View =
        ImageView(host.pubCtx).apply {
            setImageDrawable(LineIcon(kind, pal.text, 1.8f))
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = pressable(roundCard(host.pubCtx, pal.card, pal.border, 9), 0x14000000)
            contentDescription = desc
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(6) }
        }

    /** 把 blockers / warnings / suggestions 按模块归到某一组里 */
    private fun todoItems(st: JSONObject, modules: List<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (k in listOf("blockers", "warnings", "suggestions")) {
            val arr = st.optJSONArray(k) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                val msg = if (o != null) firstNonBlank(o, "message", "detail", "title")
                    .ifBlank { o.toString() } else arr.opt(i)?.toString().orEmpty()
                if (msg.isBlank()) continue
                val mod = if (o != null) firstNonBlank(o, "module", "module_id", "field", "field_id") else ""
                // 没给模块的，按关键字粗归一下；归不进去就只放在第一组，避免重复三遍
                val hit = modules.any { mod.contains(it) } ||
                    modules.any { m -> msg.contains(groupKeyword(m)) }
                if (hit || modules.first().startsWith("basic")) {
                    if (out.none { it.second == msg }) out += mod to msg
                }
            }
        }
        return out.take(12)
    }

    private fun countTodo(st: JSONObject, modules: List<String>): Int = todoItems(st, modules).size

    private fun groupKeyword(module: String): String = when (module) {
        "basic-info" -> "简介"
        "developer-info" -> "厂商"
        "platform-status" -> "平台"
        "assets-upload" -> "截图"
        "release-settings" -> "发布"
        "profile-promotion" -> "宣传"
        "windows-exclusive" -> "封面"
        else -> module
    }

    // ==================== 底部动作 ====================

    /**
     * 底部那一个按钮 —— **我要发布**。
     *
     * 用户要的就是这个：前面所有素材先在本地备好（发布页是**预览台**），
     * 点这一下才真正动手，而且**一次把该做的全做完**：
     *   ① 把本地备好的素材逐个传上去 → 写进对应字段
     *   ② 复查一遍还缺不缺（analyze-app-status）
     *   ③ 生成快照 → 预检 → 让用户确认 → 提审
     *
     * 中途任何一步失败都会**停在那儿并说清是哪一步**，
     * 不会闷头往下走（否则会出现「图没传成功但已经提审了」这种事）。
     */
    private fun actionBar(st: JSONObject): View {
        val col = LinearLayout(host.pubCtx).apply { orientation = LinearLayout.VERTICAL }
        val staged = SLOTS.count { stagedFile(it.fieldId) != null }
        val blockers = st.optJSONArray("blockers")?.length() ?: 0

        col.addView(btn(
            when {
                staged > 0 -> "我要发布（含 $staged 项本地素材）"
                blockers > 0 -> "我要发布（还有 $blockers 项待补）"
                else -> "我要发布"
            },
            primary = true,
            wide = true
        ) {
            publishAll(staged)
        })
        col.addView(TextView(host.pubCtx).apply {
            setText("会把本地备好的素材传上去、写进对应字段，然后提审。过程中每一步都会让你确认。")
            textSize = 11f
            setTextColor(pal.faint)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        return col
    }

    /**
     * 一键发布：传素材 → 写字段 → 提审。
     *
     * 分阶段跑、每阶段报结果 —— 因为这是**不可逆**的操作链，
     * 用户必须知道走到哪一步了、卡在哪一步。
     */
    private fun publishAll(staged: Int) {
        if (busy) return
        if (appId.isBlank() || devId.isBlank()) { host.pubToast("先登录并选一个游戏"); return }

        val todo = SLOTS.filter { stagedFile(it.fieldId) != null }
        host.pubConfirm(
            "开始发布",
            buildString {
                append("会按顺序做：\n")
                if (todo.isEmpty()) append("  ① 本地没有待传素材，跳过上传\n")
                else {
                    append("  ① 上传 ${todo.size} 项本地素材并写进对应字段：\n")
                    todo.forEach { append("      · ${it.title}\n") }
                }
                append("  ② 复查资料还缺什么\n")
                append("  ③ 生成快照 → 预检 → 你确认 → 提交审核\n\n")
                append("提交后版本会进审核流，这一步不可撤销。")
            }
        ) {
            busy = true
            host.pubToast("开始发布…")
            Thread { runPublishPipeline(todo) }.start()
        }
    }

    private fun runPublishPipeline(todo: List<Slot>) {
        val log = StringBuilder()

        // ---------- ① 传素材 + 写字段 ----------
        var uploaded = 0
        for (slot in todo) {
            val f = stagedFile(slot.fieldId) ?: continue
            post { host.pubToast("上传「${slot.title}」…") }

            if (slot.video) {
                // 视频走另一条命令，拿到的是 videoId 而不是 URL
                val (up, vid) = TapCli.uploadVideoId(host.pubCtx, f, devId, appId, slot.fieldId)
                if (!up.ok || vid.isBlank()) {
                    busy = false
                    post {
                        host.pubConfirm(
                            "卡在「${slot.title}」",
                            "视频上传失败，**后面的步骤都还没做**：\n\n" +
                                up.message + "\n" + up.raw.take(600),
                            {}
                        )
                    }
                    return
                }
                val ch = org.json.JSONArray().put(
                    JSONObject().put("field_id", slot.fieldId).put("op", "replace").put("value", vid)
                ).toString()
                val save = TapCli.saveChanges(
                    host.pubCtx, devId, appId, ch,
                    idempotencyKey = "pub-" + appId + "-" + slot.fieldId + "-" + System.currentTimeMillis()
                )
                if (!save.ok) {
                    busy = false
                    post {
                        host.pubConfirm(
                            "卡在「${slot.title}」",
                            "视频传上去了（videoId $vid），但写进字段失败：\n\n" +
                                save.message + "\n" + save.raw.take(600),
                            {}
                        )
                    }
                    return
                }
            } else {
                val (up, url) = TapCli.uploadImageUrl(host.pubCtx, f, devId, appId)
                if (!up.ok || url.isBlank()) {
                    busy = false
                    post {
                        host.pubConfirm(
                            "卡在「${slot.title}」",
                            "图片上传失败，**后面的步骤都还没做**：\n\n" +
                                up.message + "\n" + up.raw.take(600),
                            {}
                        )
                    }
                    return
                }
                val ch = org.json.JSONArray().put(
                    JSONObject().put("field_id", slot.fieldId).put("op", "replace").put("value", url)
                ).toString()
                val save = TapCli.saveChanges(
                    host.pubCtx, devId, appId, ch,
                    idempotencyKey = "pub-" + appId + "-" + slot.fieldId + "-" + System.currentTimeMillis()
                )
                if (!save.ok) {
                    busy = false
                    post {
                        host.pubConfirm(
                            "卡在「${slot.title}」",
                            "图传上去了（$url），但写进字段失败 —— 多半是规格不符。\n\n" +
                                save.message + "\n" + save.raw.take(600) +
                                "\n\n→ 用「让 AI 做」重做一张，或换个尺寸再试。",
                            {}
                        )
                    }
                    return
                }
            }
            uploaded++
            log.append("✅ ${slot.title} 已写入\n")
            // 传完一张就清掉暂存，避免重复传
            unstage(slot.fieldId)
        }

        // ---------- ② 复查 ----------
        post { host.pubToast("复查资料…") }
        val st = TapCli.analyzeStatus(host.pubCtx, devId, appId)
        val blockers = if (st.ok) (st.data.optJSONArray("blockers")?.length() ?: 0) else -1
        status = if (st.ok) st.data else status

        if (!st.ok) {
            busy = false
            post {
                render()
                host.pubConfirm(
                    "素材处理完了，但复查失败",
                    log.toString() + "\n复查那步没成功，**没有继续提审**：\n" + st.message,
                    {}
                )
            }
            return
        }
        if (blockers > 0) {
            busy = false
            post {
                render()
                host.pubConfirm(
                    "素材处理完了，但还不能提审",
                    log.toString() + "\n还差 $blockers 项必填。补完再点一次「我要发布」。",
                    {}
                )
            }
            return
        }

        // ---------- ③ 提审 ----------
        busy = false
        post {
            render()
            host.pubConfirm(
                "素材齐了，要提审吗？",
                log.toString() + "\n没有阻断项了。接下来生成快照 → 预检 → 提交审核。",
                { submitFlow() }
            )
        }
    }

    /**
     * 提审链路：快照 → 预检 → 让用户确认 → 提交。
     *
     * 官方要求三步都带着同一个 review_fingerprint，而且**提交是 high-risk-write**：
     * 必须用户明确点过确认才带 --yes。所以这里每一步都把结果摊给用户看，
     * 绝不「一键静默提审」。
     */
    private fun submitFlow() {
        if (busy) return
        host.pubConfirm(
            "提交审核",
            "接下来会：① 生成审核快照 ② 预检 ③ 把结果给你看，确认后才真正提交。\n" +
                "提交后这个版本进入审核流程。要继续吗？"
        ) {
            busy = true
            host.pubToast("正在生成快照…")
            Thread {
                val snap = TapCli.prepareReviewSnapshot(host.pubCtx, devId, appId)
                val fp = if (snap.ok) findFingerprint(snap) else ""
                when {
                    !snap.ok -> {
                        busy = false
                        post { host.pubConfirm("生成快照失败", snap.message + "\n\n" + snap.raw.take(700), {}) }
                    }
                    fp.isBlank() -> {
                        busy = false
                        post {
                            host.pubConfirm(
                                "没拿到复核指纹",
                                "官方要求提交时带上快照给的 review_fingerprint，这次没读到。原始输出：\n\n" +
                                    snap.raw.take(800), {}
                            )
                        }
                    }
                    else -> {
                        val data = JSONObject()
                            .put("release_schedule", JSONObject().put("kind", "immediate"))
                            .put("review_fingerprint", fp)
                        val pre = TapCli.precheckReview(host.pubCtx, devId, appId, data.toString())
                        busy = false
                        post {
                            val txt = buildString {
                                append("复核指纹：").append(fp.take(24)).append("…\n\n")
                                append(if (pre.ok) "预检通过，可以提交。\n\n" else "预检有问题：\n")
                                append(pre.message).append('\n')
                                append(pre.raw.take(900))
                            }
                            // 预检没过就不给「继续」按钮 —— 免得用户点了才发现提交不了
                            if (pre.ok) {
                                host.pubConfirm("预检结果", txt) { doSubmit(data.toString(), fp) }
                            } else {
                                host.pubConfirm("预检结果", txt + "\n\n（先按上面的提示处理，再回来提交）", {})
                            }
                        }
                    }
                }
            }.start()
        }
    }

    private fun doSubmit(dataJson: String, fp: String) {
        if (busy) return
        busy = true
        host.pubToast("正在提交审核…")
        Thread {
            val r = TapCli.submitReview(
                host.pubCtx, devId, appId, dataJson,
                // 幂等键要稳定：同一个指纹重复提交不会重复建单
                idempotencyKey = "submit-" + appId + "-" + fp.take(16),
                confirmed = true
            )
            busy = false
            post {
                if (r.ok) {
                    host.pubConfirm("已提交", "这个版本已经进入审核流程。\n\n" + r.message, {})
                } else {
                    // 官方提示：写入结果未知时先读服务端状态，别换幂等键盲目重试
                    host.pubConfirm(
                        "提交没成功",
                        r.message + "\n\n（先别重复点提交。去开发者中心看一下版本状态，" +
                            "或者把下面这段发给 AI 让它判断。）\n\n" + r.raw.take(900),
                        {}
                    )
                }
                loadStatus()
            }
        }.start()
    }

    // ==================== 素材工具 ====================

    /**
     * 素材槽位 —— 对齐官方「按**字段**管素材」的模型。
     *
     * 之前这里只有一个「上传图片」按钮，**根本没说传的是哪张、传到哪个字段**。
     * 而 TapTap 商店页需要的是一整套：icon、游戏截图（多张）、
     * 横版封面、竖版封面、方形宣传图、实机视频……每个都是**独立的字段**。
     * 只传一张图扔进素材库，等于什么都没做（官方原话：
     * 「上传成功不等于资料字段已写入，也不等于可提审」）。
     *
     * 所以这里按字段列槽位：每个槽位显示当前值、规格、以及「换/加」按钮，
     * 上传完**立刻写进对应字段**（upload → save-changes 两步走）。
     */
    private fun materialSlotsCard(): View {
        val c = card(pal.card, pal.border)
        c.addView(TextView(host.pubCtx).apply {
            setText("商店页素材")
            textSize = 15f
            typeface = MEDIUM
            setTextColor(pal.text)
        })
        c.addView(TextView(host.pubCtx).apply {
            setText("每个位置是独立的字段。上传后会**直接写进对应字段**，不是只丢进素材库。")
            textSize = 11.5f
            setTextColor(pal.sub)
            setPadding(0, dp(5), 0, dp(4))
        })

        for (slot in SLOTS) {
            val online = currentFieldValue(slot.fieldId)     // 线上现在是什么
            val local = stagedFile(slot.fieldId)             // 本地备好了什么
            val extra = stagedCount(slot.fieldId)

            val row = LinearLayout(host.pubCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(11), 0, 0)
            }

            // 缩略图：本地有就显示本地那张（这才是要发布的），否则显示线上的
            val iv = ImageView(host.pubCtx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundCard(host.pubCtx, pal.cardAlt, pal.border, 10)
                clipToOutline = true
                setImageDrawable(LineIcon(if (slot.video) "video" else "image", pal.faint, 1.8f))
                setPadding(dp(18), dp(18), dp(18), dp(18))
                when {
                    local != null -> {
                        setPadding(0, 0, 0, 0)
                        runCatching { android.graphics.BitmapFactory.decodeFile(local.absolutePath) }
                            .getOrNull()?.let { setImageBitmap(it) }
                    }
                    online.startsWith("http") -> loadThumbInto(this, online)
                }
            }
            row.addView(iv, LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(12) })

            val col = LinearLayout(host.pubCtx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(host.pubCtx).apply {
                setText(slot.title)
                textSize = 13.5f
                typeface = MEDIUM
                setTextColor(pal.text)
            })
            col.addView(TextView(host.pubCtx).apply {
                // 三态说清楚：本地备好了 / 线上已有 / 两边都空
                setText(
                    when {
                        local != null ->
                            "已备好${if (extra > 1) "（共 $extra 张）" else ""} · " +
                                "${local.length() / 1024} KB · 待发布"
                        slot.video && online.isNotBlank() -> "线上已设置"
                        !slot.video && online.isNotBlank() -> "线上已有（可换）"
                        else -> "还没准备 · ${slot.spec}"
                    }
                )
                textSize = 11f
                setTextColor(
                    when {
                        local != null -> pal.accent      // 本地备好 = 可以发了
                        online.isNotBlank() -> pal.sub
                        else -> pal.errText              // 空着 = 要补
                    }
                )
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            // 加一张 / 换一张（存本地，不上传）
            row.addView(iconBtn("upload", "从相册选一张放进「${slot.title}」") {
                pickIntoSlot(slot)
            })
            if (local != null) {
                row.addView(iconBtn("clear", "清掉本地的这一张") {
                    unstage(slot.fieldId)
                    render()
                })
            }
            row.addView(iconBtn("skill", "让 AI 为「${slot.title}」做一张") {
                host.pubAskAi(
                    "帮我为 TapTap 商店页的「${slot.title}」位置做一张素材。" +
                        "规格要求：${slot.spec}（**控制在 4MB 以内**）。" +
                        "做完存到发布暂存区 _publish/${slot.fieldId}.jpg，先别上传 —— " +
                        "我要在发布页确认后再一起发。"
                )
            })
            c.addView(row)
        }

        c.addView(TextView(host.pubCtx).apply {
            setText(
                "这里只是**本地准备区** —— 选好的图先存这儿，你能看到齐不齐、长什么样。\n" +
                    "确认无误后点最下面的「我要发布」，才会一次性传上去并写进对应字段。\n" +
                    "（图会自动压到 4MB 以内 —— 官方对宣传图就是限 4MB。）"
            )
            textSize = 11f
            setTextColor(pal.faint)
            setPadding(0, dp(11), 0, 0)
        })
        return c
    }

    /** 从相册选一张，存进本地暂存区（**不上传**） */
    private fun pickIntoSlot(slot: Slot) {
        host.pubPickImage { f ->
            busy = true
            Thread {
                val out = stageImage(slot.fieldId, f)
                busy = false
                post {
                    if (out == null) host.pubToast("这张图读不了，换一张试试")
                    else {
                        host.pubToast("已备好「${slot.title}」（${out.length() / 1024} KB）")
                        render()
                    }
                }
            }.start()
        }
    }

    // ==================== 本地素材暂存 ====================

    /**
     * 本地发布素材暂存区：`<项目>/_publish/`。
     *
     * 这是用户要的「先把图存在发布区域」——
     * 发布页先看**本地这批**备齐没有、长什么样，确认了再一键推上去。
     * 不是「点一下传一张、传到哪不知道」。
     *
     * 文件名 = 字段名（截图带序号），所以一个字段对应一个本地文件，一目了然。
     */
    private fun stageDir(): File = File(host.pubWorkspaceDir().parentFile, "_publish").apply { mkdirs() }

    /** 这个槽位本地有没有备好的图 */
    private fun stagedFile(fieldId: String): File? {
        val d = stageDir()
        // 精确名优先；截图那种多图的取第一个匹配
        val exact = File(d, "$fieldId.jpg")
        if (exact.isFile) return exact
        val png = File(d, "$fieldId.png")
        if (png.isFile) return png
        return d.listFiles()
            ?.filter { it.isFile && it.name.startsWith("${fieldId}_") }
            ?.sortedBy { it.name }
            ?.firstOrNull()
    }

    /** 这个槽位本地备了几张（截图可能多张） */
    private fun stagedCount(fieldId: String): Int =
        stageDir().listFiles()?.count { it.isFile && it.name.startsWith("${fieldId}_") } ?: 0

    /**
     * 把一张图收进暂存区。
     *
     * **同时压到 4MB 以下** —— 官方 `banner_4` 等字段的 `maxSizeMB` 就是 4，
     * 超了会被拒；而手机拍/生成的大图很容易超。这里统一压好，
     * 免得用户在「上传失败」里才发现是尺寸问题。
     */
    private fun stageImage(fieldId: String, src: File): File? = runCatching {
        val d = stageDir()
        val raw = src.readBytes()
        val out = File(d, "$fieldId.jpg")

        // 先按最长边 2048 缩，再按质量压，直到 ≤ 4MB
        val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: return@runCatching null
        var scale = 1.0f
        if (maxOf(bmp.width, bmp.height) > 2048) {
            scale = 2048f / maxOf(bmp.width, bmp.height)
        }
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true) else bmp

        var quality = 88
        var bytes: ByteArray
        while (true) {
            val bos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bos)
            bytes = bos.toByteArray()
            // 4MB 是官方硬上限，留点余量
            if (bytes.size <= 3_900_000 || quality <= 40) break
            quality -= 12
        }
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        out.writeBytes(bytes)
        out
    }.getOrNull()

    /** 从暂存区删掉（用户反悔了） */
    private fun unstage(fieldId: String) {
        val d = stageDir()
        d.listFiles()?.filter { it.isFile && (it.name == "$fieldId.jpg" || it.name.startsWith("${fieldId}_")) }
            ?.forEach { runCatching { it.delete() } }
    }

    /** 读某个字段的当前值（从已缓存的模块数据里取） */
    private fun currentFieldValue(fieldId: String): String {
        val f = fieldCache[fieldId] ?: return ""
        val v = f.opt("current_value") ?: return ""
        return when (v) {
            is String -> if (v == "null") "" else v
            is org.json.JSONArray -> if (v.length() > 0) v.optString(0) else ""
            JSONObject.NULL -> ""
            else -> v.toString()
        }
    }

    /**
     * 上传一张图到**指定字段**。
     *
     * 两步走（官方流程）：
     *   ① `upload` 把图收进素材库，拿到 https URL
     *   ② `save-changes` 用 `op=replace` 把它写进目标字段
     *
     * 少任何一步都不算完成 —— 只做 ① 的话图只是躺在素材库里，商店页看不到。
     */
    private fun uploadToSlot(slot: Slot) {
        if (appId.isBlank()) { host.pubToast("先选一个游戏"); return }
        host.pubPickImage { f ->
            busy = true
            host.pubToast("正在上传到「${slot.title}」…")
            Thread {
                // ① 先预览一次，让用户看清要传什么
                val (pre, _) = TapCli.uploadImageUrl(host.pubCtx, f, devId, appId, dryRun = true)
                post {
                    host.pubConfirm(
                        "上传到「${slot.title}」？",
                        "文件：${f.name}（${f.length() / 1024} KB）\n" +
                            "写入字段：${slot.fieldId}\n" +
                            "规格要求：${slot.spec}\n\n" +
                            "会先传进素材库，再写进这个字段。确认后执行。" +
                            (if (pre.raw.isNotBlank()) "\n\n预览：\n" + pre.raw.take(400) else "")
                    ) {
                        Thread {
                            val (up, url) = TapCli.uploadImageUrl(host.pubCtx, f, devId, appId)
                            if (!up.ok || url.isBlank()) {
                                busy = false
                                post {
                                    host.pubConfirm(
                                        "上传失败",
                                        "第一步（传素材库）就没过：" + up.message + "\n\n" + up.raw.take(700),
                                        {}
                                    )
                                }
                                return@Thread
                            }
                            // ② 写进字段
                            val changes = org.json.JSONArray().put(
                                JSONObject()
                                    .put("field_id", slot.fieldId)
                                    .put("op", "replace")
                                    .put("value", url)
                            ).toString()
                            val save = TapCli.saveChanges(
                                host.pubCtx, devId, appId, changes,
                                idempotencyKey = "slot-" + appId + "-" + slot.fieldId + "-" + System.currentTimeMillis()
                            )
                            busy = false
                            post {
                                if (save.ok) {
                                    host.pubToast("已写入「${slot.title}」")
                                    status = null
                                    loadStatus()
                                } else {
                                    host.pubConfirm(
                                        "图传上去了，但写进字段失败",
                                        "素材库那一步成功了（$url），" +
                                            "但写进 ${slot.fieldId} 被拒：\n\n" +
                                            save.message + "\n" + save.raw.take(700),
                                        {}
                                    )
                                }
                            }
                        }.start()
                    }
                    busy = false
                }
            }.start()
        }
    }

    /** 把 http(s) 图片异步拉下来塞进 ImageView（缩略图用，失败就保持占位图标） */
    private fun loadThumbInto(iv: ImageView, url: String) {
        Thread {
            val bytes = runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.inputStream.use { it.readBytes() }
            }.getOrNull() ?: return@Thread
            val bmp = runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 160) s *= 2
                android.graphics.BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = s }
                )
            }.getOrNull() ?: return@Thread
            post {
                iv.setPadding(0, 0, 0, 0)
                iv.setImageBitmap(bmp)
            }
        }.start()
    }

    /** 商店页需要的素材槽位（字段 id 照官方 app-edit-field-map） */
    private data class Slot(
        val fieldId: String,
        val title: String,
        val spec: String,
        val video: Boolean = false
    )

    // ==================== 小工具 ====================

    private fun card(fill: Int, border: Int): LinearLayout =
        LinearLayout(host.pubCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = roundCard(host.pubCtx, fill, border, 14)
        }

    private fun lp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }

    private fun btn(
        label: String,
        primary: Boolean = false,
        wide: Boolean = false,
        onClick: () -> Unit
    ): TextView = TextView(host.pubCtx).apply {
        setText(label)
        textSize = 13f
        gravity = Gravity.CENTER
        typeface = MEDIUM
        setPadding(dp(14), dp(9), dp(14), dp(9))
        setTextColor(if (primary) pal.onAccent else pal.text)
        background = pressable(
            roundCard(
                host.pubCtx,
                if (primary) pal.accent else pal.cardAlt,
                if (primary) pal.accent else pal.border,
                10
            ), 0x14000000
        )
        setOnClickListener { onClick() }
        layoutParams = if (wide) LinearLayout.LayoutParams(-1, -2)
        else LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
    }

    private fun hintCard(msg: String): View {
        val c = card(pal.card, pal.border)
        c.addView(TextView(host.pubCtx).apply {
            setText(msg)
            textSize = 12.5f
            setTextColor(pal.sub)
        })
        val b = btn("检查发布状态", primary = true) {
            if (appId.isBlank()) pickGame() else loadStatus()
        }
        // btn() 返回时自带 layoutParams（-2 宽），这里换成一个带 topMargin 的新的 —— 
        // 比 as? 强转再改属性稳
        b.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) }
        c.addView(b)
        return c
    }

    // ---------- 纯文本工具 ----------

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }

    private fun firstId(r: TapCli.Result, vararg keys: String): String {
        r.arr("list")?.let { a ->
            for (i in 0 until a.length()) {
                a.optJSONObject(i)?.let { o ->
                    val v = firstNonBlank(o, *keys)
                    if (v.isNotBlank()) return v
                }
            }
        }
        return firstNonBlank(r.data, *keys)
    }

    private fun findUrl(s: String): String? {
        val m = Regex("https?://[^\\s\"'<>]+").find(s) ?: return null
        return m.value.trimEnd(')', '，', '。')
    }

    private fun findFingerprint(r: TapCli.Result): String {
        for (k in listOf("review_fingerprint", "fingerprint", "reviewFingerprint")) {
            val v = r.data.optString(k, "")
            if (v.isNotBlank()) return v
            r.obj("result")?.optString(k, "")?.let { if (it.isNotBlank()) return it }
        }
        // 兜底：从原始输出里抠
        return Regex("\"review_fingerprint\"\\s*:\\s*\"([^\"]+)\"").find(r.raw)?.groupValues?.get(1).orEmpty()
    }

    companion object {
        /**
         * 发布页当前选中的厂商 / 游戏。
         *
         * 存成静态是因为 AI 工具（Tools.kt 的 taptap_publish）也要用：
         * 用户在发布页选好了游戏，AI 就不该再问一遍「你要发布哪个」。
         * 进程内共享，重启就重来 —— 反正发布页会重新拉一次。
         */
        @Volatile var curDevId: String = ""
        @Volatile var curAppId: String = ""

        /**
         * 发布页那三个分组各自覆盖哪些模块 —— 用来算「3/3 已完成」。
         *
         * 分组口径照官方页面的说法来：
         *   游戏基本信息 = icon / 游戏名 / 类型 / 厂商 / 开发者的话
         *   游戏资料     = 简介 / 截图 / 实机视频
         *   宣传推广物料 = 宣传主图 / 其他推广图
         * 模块 id 则照 CLI 的 module_id（见官方 app-edit-field-map）。
         */
        private val GROUP_MODULES = listOf(
            "basic-info", "developer-info", "platform-status",
            "assets-upload", "release-settings",
            "profile-promotion", "windows-exclusive"
        )

        /**
         * 商店页要的素材槽位 —— 字段 id 照官方 `app-edit-field-map`。
         *
         * 官方原话：「上传成功不等于资料字段已写入」。
         * 所以 UI 按**字段**列，而不是给一个笼统的「上传图片」按钮 ——
         * 后者用户根本不知道传的是哪张、会出现在商店页的哪个位置。
         *
         * 规格写的是官方常见值；**实际以字段返回的 `image_spec` 为准**，
         * 这里只是给用户一个直观预期（AI 侧会去读真的 spec）。
         */
        private val SLOTS = listOf(
            Slot("icon", "游戏 icon", "正方形，1:1，建议 512×512 以上"),
            Slot("screenshots", "游戏截图", "多张，按上传顺序展示，建议 ≥3 张"),
            Slot("banner_4", "横版封面", "16:9 横版宣传主图"),
            Slot("square_promo_image", "方形宣传图", "1:1 方形推广图"),
            Slot("trailer", "实机视频", "宣传视频（上传后写 videoId）", video = true),
            Slot("gameplay_demo_video", "玩法演示视频", "与实机视频**不能是同一个**", video = true)
        )
    }
}
