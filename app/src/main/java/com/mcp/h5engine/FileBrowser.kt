package com.mcp.h5engine

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件浏览器 —— 翻手机上的目录、看文件、重命名、删除、新建文件夹。
 *
 * ## 权限分两档（这点很关键，用户一直卡在这）
 *
 * · **没开 Shizuku**：App 是 untrusted_app，Android 10+ 的 scoped storage
 *   会把 `/sdcard` 下的**普通文件**整个过滤掉 —— 目录名看得见、里面的文件看不见，
 *   读任何文件都是 Permission denied。所以这里会明确提示「去开 Shizuku」，
 *   而不是让用户以为「文件夹是空的」。
 *
 * · **开了 Shizuku**：以 shell（uid 2000）身份读，`/sdcard` 全都能看，
 *   包括 `/sdcard/Android/data` 下别的 App 的目录。
 *
 * ## 为什么有两条读路径
 *
 * [Shizuku2] 那条是 `ls` / `cat`（走 shell 进程），权限高；
 * 直接 `File.listFiles()` 那条权限低但**没开 Shizuku 时也能用**
 * （至少能看 App 自己的目录）。先试 Shizuku，不行再退回直读，
 * 这样「装没装 Shizuku」都能用，只是能看到的范围不同。
 */
class FileBrowser(
    private val ctx: Context,
    private val pal: Palette,
    private val dp: (Int) -> Int,
    private val toast: (String) -> Unit
) {

    /** 当前目录 */
    private var cur: File = File("/sdcard")
    private var dlg: Dialog? = null
    private lateinit var listBox: LinearLayout
    private lateinit var pathTv: TextView
    private lateinit var hintTv: TextView

    /** 有没有 Shizuku 提权 */
    private val elevated: Boolean get() = Shizuku2.isReady()

    /**
     * 有没有「所有文件访问」权限。
     *
     * 这条和 Shizuku **是两条独立的路**：
     *   · 有它 = 能读写 /sdcard 下的普通文件（**不用装 Shizuku**）；
     *   · 没它 + 没 Shizuku = 只能看见目录名，文件全被 scoped storage 挡住。
     * 之前这里只认 Shizuku，导致开了「所有文件访问」的用户仍被判定为「读不了」。
     */
    private val hasAllFiles: Boolean get() = runCatching {
        android.os.Environment.isExternalStorageManager()
    }.getOrDefault(false)

    /** 能不能真正读到文件内容 */
    private val canReadFiles: Boolean get() = elevated || hasAllFiles

    fun show(start: File? = null) {
        start?.let { if (it.isDirectory) cur = it }

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.setBackgroundColor(pal.bg)

        // ---- 顶栏：路径 + 关闭 ----
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
        }
        pathTv = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(pal.text)
            setTextIsSelectable(true)
        }
        head.addView(pathTv, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 17f
            setTextColor(pal.sub)
            setPadding(dp(10), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener { dlg?.dismiss() }
        })
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        // ---- 权限提示条 ----
        hintTv = TextView(ctx).apply {
            textSize = 11.5f
            setTextColor(pal.sub)
            setPadding(dp(14), dp(2), dp(14), dp(8))
        }
        root.addView(hintTv, LinearLayout.LayoutParams(-1, -2))

        // ---- 工具行：上一级 / 刷新 / 新建文件夹 ----
        val bar = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(10), 0, dp(10), dp(6))
        }
        val barRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        barRow.addView(chip("↑ 上一级") { goUp() })
        barRow.addView(chip("⟳ 刷新") { render() })
        barRow.addView(chip("＋ 新建文件夹") { mkdir() })
        barRow.addView(chip("跳转…") { jumpTo() })
        bar.addView(barRow)
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        // ---- 列表 ----
        listBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(20))
        }
        root.addView(
            ScrollView(ctx).apply { addView(listBox) },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val d = Dialog(ctx, android.R.style.Theme_Material_Light_NoActionBar)
        d.setContentView(root)
        dlg = d
        d.show()
        d.window?.setLayout(-1, -1)
        render()
    }

    private fun chip(label: String, onClick: () -> Unit): View =
        TextView(ctx).apply {
            text = label
            textSize = 12.5f
            setTextColor(pal.accent)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            isClickable = true
            setOnClickListener { onClick() }
        }

    // ==================== 渲染 ====================

    private fun render() {
        pathTv.text = cur.absolutePath
        hintTv.text = when {
            elevated -> "已提权（Shizuku）：能看受保护目录，包括 Android/data"
            hasAllFiles -> "已授权「所有文件访问」：/sdcard 下都能读写（不需要 Shizuku）"
            else -> "⚠️ 只能看到目录名，普通文件被系统挡住 —— " +
                "去「发布页 → 切换项目 → 授权所有文件访问」（不用装 Shizuku），" +
                "或用「工作区目录」授权一个目录"
        }
        listBox.removeAllViews()

        val entries = listEntries()
        if (entries == null) {
            listBox.addView(TextView(ctx).apply {
                text = "读不了这个目录（权限不足）。\n\n" +
                    if (elevated) "已提权仍然读不了 —— 可能是路径不存在或该目录连 shell 都不许读。"
                    else "去「发布页 → Shizuku 提权」开启后再来。"
                textSize = 12.5f
                setTextColor(pal.sub)
                setPadding(dp(6), dp(10), dp(6), dp(10))
            })
            return
        }
        if (entries.isEmpty()) {
            listBox.addView(TextView(ctx).apply {
                text = if (elevated) "（空目录）" else "（看起来是空的 —— 但更可能是权限被挡，开 Shizuku 再确认）"
                textSize = 12.5f
                setTextColor(pal.sub)
                setPadding(dp(6), dp(10), dp(6), dp(10))
            })
            return
        }

        for (e in entries) {
            listBox.addView(rowOf(e))
        }
    }

    /** 一条目录项 */
    private data class Entry(val name: String, val dir: Boolean, val size: Long, val path: String)

    /**
     * 列目录。优先走 Shizuku（权限高），不行退回直接 File API。
     * 返回 null = 读不了。
     */
    private fun listEntries(): List<Entry>? {
        if (elevated) {
            // 走 shell：`ls -la` 拿到的信息最全（能看到受保护目录）
            val (code, out) = Shizuku2.sh("ls -la ${Shizuku2.shellQuote(cur.absolutePath)}", 15_000)
            if (code == 0 && out.isNotBlank()) {
                val list = parseLs(out)
                if (list.isNotEmpty()) return list
            }
        }
        // 退回直读（没提权时至少能看到 App 自己目录里的东西）
        val fs = cur.listFiles() ?: return null
        return fs.map { Entry(it.name, it.isDirectory, if (it.isFile) it.length() else 0L, it.absolutePath) }
            .sortedWith(compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() })
    }

    /**
     * 解析 `ls -la` 的输出。
     *
     * 格式（POSIX）：`权限 链接数 属主 属组 大小 月 日 时间 名字`
     * 名字里可能有空格，所以**从第 9 列往后整段当名字**。
     */
    private fun parseLs(out: String): List<Entry> {
        val list = mutableListOf<Entry>()
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("total ")) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 9) continue
            val perms = parts[0]
            if (perms.length < 10) continue
            val name = parts.drop(8).joinToString(" ")
            if (name == "." || name == "..") continue
            val size = parts[4].toLongOrNull() ?: 0L
            list += Entry(name, perms.startsWith("d"), size, File(cur, name).absolutePath)
        }
        return list.sortedWith(compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() })
    }

    private fun rowOf(e: Entry): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(11), dp(6), dp(11))
        }
        row.addView(TextView(ctx).apply {
            text = if (e.dir) "📁" else "📄"
            textSize = 15f
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(9) })

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = e.name
            textSize = 13.5f
            setTextColor(pal.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        if (!e.dir) {
            col.addView(TextView(ctx).apply {
                text = humanSize(e.size)
                textSize = 10.5f
                setTextColor(pal.faint)
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        row.isClickable = true
        row.setOnClickListener {
            if (e.dir) {
                cur = File(e.path)
                render()
            } else {
                preview(e)
            }
        }
        row.setOnLongClickListener { menuFor(e); true }
        return row
    }

    private fun humanSize(b: Long): String = when {
        b >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1 shl 10 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> "$b B"
    }

    // ==================== 操作 ====================

    private fun goUp() {
        val p = cur.parentFile ?: return
        if (!p.canRead() && !elevated) { toast("上级读不了"); return }
        cur = p
        render()
    }

    private fun jumpTo() {
        val et = EditText(ctx).apply {
            setText(cur.absolutePath)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(ctx)
            .setTitle("跳到路径")
            .setView(et)
            .setPositiveButton("去") { _, _ ->
                val f = File(et.text.toString().trim())
                if (f.isDirectory) { cur = f; render() } else toast("不是目录")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun mkdir() {
        val et = EditText(ctx).apply { hint = "文件夹名" }
        AlertDialog.Builder(ctx)
            .setTitle("在 ${cur.name} 下新建文件夹")
            .setView(et)
            .setPositiveButton("建") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isBlank()) return@setPositiveButton
                val f = File(cur, n)
                val ok = if (elevated) {
                    Shizuku2.sh("mkdir -p ${Shizuku2.shellQuote(f.absolutePath)}").first == 0
                } else {
                    f.mkdirs()
                }
                toast(if (ok) "已建 ${f.name}" else "建不了（权限？）")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 长按：重命名 / 删除 */
    private fun menuFor(e: Entry) {
        val items = arrayOf("重命名", "删除")
        AlertDialog.Builder(ctx)
            .setTitle(e.name)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> rename(e)
                    1 -> confirmDelete(e)
                }
            }
            .show()
    }

    private fun rename(e: Entry) {
        val et = EditText(ctx).apply { setText(e.name) }
        AlertDialog.Builder(ctx)
            .setTitle("重命名")
            .setView(et)
            .setPositiveButton("改") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isBlank() || n == e.name) return@setPositiveButton
                val dst = File(cur, n).absolutePath
                val src = e.path
                val ok = if (elevated) {
                    Shizuku2.sh("mv ${Shizuku2.shellQuote(src)} ${Shizuku2.shellQuote(dst)}").first == 0
                } else {
                    File(src).renameTo(File(dst))
                }
                toast(if (ok) "已重命名" else "改不了（权限？）")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(ctx)
            .setTitle("删除 ${e.name}？")
            .setMessage("删了不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                val ok = if (elevated) {
                    Shizuku2.sh("rm -rf ${Shizuku2.shellQuote(e.path)}").first == 0
                } else {
                    if (e.dir) File(e.path).deleteRecursively() else File(e.path).delete()
                }
                toast(if (ok) "已删除" else "删不了（权限？）")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 点文件：文本给看内容，其它给个信息 */
    private fun preview(e: Entry) {
        val f = File(e.path)
        val text = e.name.substringAfterLast('.', "").lowercase() in
            setOf("txt", "md", "json", "js", "ts", "html", "htm", "css", "lua", "xml", "yml", "yaml", "csv", "log", "ini", "cfg")
        if (!text) {
            AlertDialog.Builder(ctx)
                .setTitle(e.name)
                .setMessage("${humanSize(e.size)}\n${e.path}")
                .setPositiveButton("好", null)
                .show()
            return
        }
        // 读内容：优先 Shizuku（能读受保护文件）
        val content = if (elevated) {
            Shizuku2.readFile(e.path, 200_000)?.toString(Charsets.UTF_8)
        } else {
            runCatching { f.readText().take(200_000) }.getOrNull()
        }
        if (content == null) {
            AlertDialog.Builder(ctx)
                .setTitle(e.name)
                .setMessage("读不到内容。\n\n" + if (elevated) "可能不是文本文件。" else "去开 Shizuku 才能读这个位置的文件。")
                .setPositiveButton("好", null)
                .show()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(e.name)
            .setMessage(content.take(20_000))
            .setPositiveButton("好", null)
            .show()
    }
}
