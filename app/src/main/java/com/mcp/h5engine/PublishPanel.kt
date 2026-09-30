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
        container.addView(toolsCard())
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
                post {
                    host.pubConfirm(
                        "登录 TapTap",
                        "没能拿到授权链接。CLI 原始输出：\n\n" + start.raw.take(700),
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
        ops.addView(iconBtn("upload", "上传 icon") { uploadFlow() })
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

    private fun actionBar(st: JSONObject): View {
        val col = LinearLayout(host.pubCtx).apply { orientation = LinearLayout.VERTICAL }
        val blockers = st.optJSONArray("blockers")?.length() ?: 0
        col.addView(btn(
            if (blockers > 0) "还有 $blockers 项要补，先去补" else "更新线上版本",
            primary = true,
            wide = true
        ) {
            // 不用 return@btn：尾随 lambda 里用标签返回，可读性和可靠性都差，
            // 直接 if/else 表达同样的意思
            if (blockers > 0) host.pubToast("先把上面的待办补完") else submitFlow()
        })
        col.addView(TextView(host.pubCtx).apply {
            setText("玩家玩到的线上版本将会随之更新")
            textSize = 11f
            setTextColor(pal.faint)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        return col
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

    private fun toolsCard(): View {
        val c = card(pal.cardAlt, pal.border)
        c.addView(TextView(host.pubCtx).apply {
            setText("素材")
            textSize = 13f
            typeface = MEDIUM
            setTextColor(pal.text)
        })
        c.addView(TextView(host.pubCtx).apply {
            setText("上传一张图到 TapTap 素材库，或者让 AI 现做一张。传成功的图会进素材库，后面补资料能直接引用。")
            textSize = 11.5f
            setTextColor(pal.sub)
            setPadding(0, dp(5), 0, 0)
        })
        val row = LinearLayout(host.pubCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(btn("上传图片") { uploadFlow() })
        row.addView(btn("让 AI 做图") {
            host.pubAskAi(
                "帮我为 TapTap 商店页做一张宣传图。先看当前游戏是什么、已经有哪些素材，" +
                    "需要什么尺寸就按官方规格来。做完直接上传到 TapTap 素材库" +
                    "（app $appId，厂商 $devId）。"
            )
        })
        c.addView(row)
        return c
    }

    private fun uploadFlow() {
        if (appId.isBlank()) { host.pubToast("先选一个游戏"); return }
        host.pubPickImage { f ->
            busy = true
            host.pubToast("正在上传 ${f.name}…")
            Thread {
                // 先 dry-run 让用户看清要传什么，再真传 —— 官方要求写操作先预览
                val pre = TapCli.uploadImage(host.pubCtx, f, devId, appId, dryRun = true)
                post {
                    host.pubConfirm(
                        "上传这张图？",
                        "文件：${f.name}（${f.length() / 1024} KB）\n" +
                            "到：app $appId / 厂商 $devId 的素材库\n\n" +
                            (if (pre.raw.isNotBlank()) pre.raw.take(500) + "\n\n" else "") +
                            "确认后才会真正上传。"
                    ) {
                        Thread {
                            val r = TapCli.uploadImage(host.pubCtx, f, devId, appId, dryRun = false)
                            busy = false
                            post {
                                host.pubToast(if (r.ok) "已上传" else "上传失败：" + r.message)
                                if (r.ok) loadStatus()
                                else host.pubConfirm("上传失败", r.raw.take(800), {})
                            }
                        }.start()
                    }
                    // 用户确认之前不算忙，别再拦住别的操作
                    busy = false
                }
            }.start()
        }
    }

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
    }
}
