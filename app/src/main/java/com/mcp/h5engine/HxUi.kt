package com.mcp.h5engine

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Hexora 统一弹窗 / 控件。
 *
 * ## 为什么不用系统 AlertDialog
 *
 * 全 App 原来有 30 处 `AlertDialog.Builder`，各自设置标题、灰字、系统按钮，
 * 跟本 App 的「纸感底 + 松石绿强调色」完全脱节 —— 弹出来就是一个系统味十足的白框。
 * 更糟的是输入框直接裸 `EditText`：没有圆角、没有内边距、留着系统下划线，
 * 是「很原生 / 很简陋」观感的主要来源。
 *
 * 这里用一个自绘 [HxDialog] 顶掉。Builder 的 API 刻意跟 `AlertDialog.Builder`
 * 对齐（setTitle / setMessage / setView / setItems / setPositiveButton …），
 * 所以替换调用点时几乎只是换个类名。
 *
 * ## 视觉口径（跟 Theme.Palette 一致）
 *
 * · 卡面：pal.card + 18dp 圆角 + 1dp 发丝描边
 * · 标题：16sp 中等字重 pal.text
 * · 正文：13.5sp pal.sub，行距 1.35
 * · 主按钮：实心 pal.accent + 白字（危险动作换 errText 底）
 * · 次按钮：描边卡面
 * · 列表项：整行可点 + 水波纹 + 项间发丝分隔
 */

// ==================== 按钮 ====================

/** 圆角实心 / 描边按钮 */
fun hxButton(ctx: Context, p: Palette, text: String, primary: Boolean = false, danger: Boolean = false): TextView =
    TextView(ctx).apply {
        this.text = text
        textSize = 13.5f
        letterSpacing = 0.06f
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = if (primary || danger) MEDIUM else Typeface.DEFAULT
        setPadding(ctx.dp(18), ctx.dp(11), ctx.dp(18), ctx.dp(11))
        when {
            danger -> {
                setTextColor(p.errText)
                background = pressable(roundCard(ctx, p.errBg, p.errBg, 11, 0), 0x22000000)
            }
            primary -> {
                setTextColor(p.onAccent)
                background = pressable(roundCard(ctx, p.accent, p.accent, 11, 0), 0x2AFFFFFF)
            }
            else -> {
                setTextColor(p.text)
                background = pressable(roundCard(ctx, p.cardAlt, p.border, 11), 0x14000000)
            }
        }
    }

// ==================== 输入框 ====================

/**
 * 统一样式的输入框 —— **不要再裸用 `EditText(ctx)`**。
 *
 * 白底 + 12dp 圆角 + 发丝描边 + 舒适内边距，去掉系统那根下划线。
 */
fun hxInput(
    ctx: Context,
    p: Palette,
    hint: String = "",
    value: String = "",
    numeric: Boolean = false,
    password: Boolean = false,
    multiLine: Boolean = false
): EditText = EditText(ctx).apply {
    this.hint = hint
    textSize = 14f
    letterSpacing = 0.02f
    setText(value)
    setTextColor(p.text)
    setHintTextColor(p.faint)
    setPadding(ctx.dp(14), ctx.dp(13), ctx.dp(14), ctx.dp(13))
    includeFontPadding = false
    background = roundCard(ctx, p.card, p.border, 12)
    when {
        multiLine -> {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        numeric -> inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        password -> inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else -> setSingleLine()
    }
}

/** 标签 + 输入框（标签永远可见，不靠 hint） */
fun hxField(
    ctx: Context,
    p: Palette,
    label: String,
    value: String = "",
    numeric: Boolean = false,
    password: Boolean = false,
    hint: String = ""
): LinearLayout {
    val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    if (label.isNotBlank()) {
        wrap.addView(TextView(ctx).apply {
            text = label
            textSize = 12.5f
            setTextColor(p.sub)
            setPadding(ctx.dp(2), 0, ctx.dp(2), ctx.dp(6))
        })
    }
    val et = hxInput(ctx, p, hint.ifBlank { label }, value, numeric, password)
    wrap.addView(et, LinearLayout.LayoutParams(-1, -2))
    // 把输入框挂到容器的 tag 上，调用方用 `wrap.tag as? EditText` 取回，
    // 不用靠「第几个子 View」这种一改就碎的约定。
    wrap.tag = et
    return wrap
}

/** 一行说明文字（次要信息） */
fun hxNote(ctx: Context, p: Palette, t: String, color: Int? = null): TextView =
    TextView(ctx).apply {
        text = t
        textSize = 11.5f
        setTextColor(color ?: p.faint)
        setPadding(ctx.dp(2), ctx.dp(8), ctx.dp(2), 0)
        setLineSpacing(ctx.dp(3).toFloat(), 1f)
    }

/** 发丝分隔线 */
fun hxDivider(ctx: Context, p: Palette): View =
    View(ctx).apply { setBackgroundColor(p.border) }

// ==================== 弹窗 ====================

/**
 * 自绘统一弹窗。
 *
 * 用法跟 AlertDialog.Builder 基本一致：
 * ```
 * HxDialog.Builder(this, pal)
 *     .setTitle("远端地址")
 *     .setMessage("…")
 *     .setView(input)
 *     .setPositiveButton("保存") { … }
 *     .setNegativeButton("取消", null)
 *     .show()
 * ```
 */
class HxDialog internal constructor(
    private val ctx: Context,
    private val pal: Palette,
    private val spec: Spec
) : Dialog(ctx) {

    /** 弹窗的全部内容（Builder 往里填，onCreate 里画） */
    internal data class Spec(
        var title: String = "",
        var message: String? = null,
        var body: View? = null,
        var items: List<String>? = null,
        var onItem: ((Int) -> Unit)? = null,
        var positive: Pair<String, (() -> Unit)?>? = null,
        var negative: Pair<String, (() -> Unit)?>? = null,
        var neutral: Pair<String, (() -> Unit)?>? = null,
        var danger: Boolean = false,
        var cancelable: Boolean = true,
        var onShow: ((HxDialog) -> Unit)? = null
    )

    /** 三个按钮的视图，供 `btn(BUTTON_POSITIVE)` 取用（对齐 dlg.getButton 的用法） */
    private val buttons = HashMap<Int, TextView>()

    /** 取按钮视图（对应 AlertDialog.getButton） */
    fun btn(which: Int): TextView? = buttons[which]

    /** 显示后的回调（对应 AlertDialog.setOnShowListener）；只在首次显示时触发一次 */
    fun setOnShowListener(l: (HxDialog) -> Unit) {
        spec.onShow = l
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(
            buildRoot(),
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(if (pal.dark) 0.62f else 0.42f)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setCancelable(spec.cancelable)
        setCanceledOnTouchOutside(spec.cancelable)
    }

    override fun onStart() {
        super.onStart()
        spec.onShow?.invoke(this)
    }

    /** 屏幕高度的 52% —— 长正文（git 输出、文件清单）在这之内滚动 */
    private fun maxBodyHeight(): Int {
        val dm = ctx.resources.displayMetrics
        return (dm.heightPixels * 0.52f).toInt()
    }

    private fun buildRoot(): View {
        // 外层负责左右留白，让卡片不贴屏幕边
        val outer = FrameLayout(ctx).apply {
            setPadding(ctx.dp(22), ctx.dp(20), ctx.dp(22), ctx.dp(20))
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundCard(ctx, pal.card, pal.border, 18)
            clipToOutline = true
        }

        if (spec.title.isNotBlank()) {
            card.addView(TextView(ctx).apply {
                text = spec.title
                textSize = 16.5f
                typeface = MEDIUM
                letterSpacing = 0.03f
                setTextColor(pal.text)
                includeFontPadding = false
                setPadding(ctx.dp(20), ctx.dp(19), ctx.dp(20), 0)
            })
        }

        spec.message?.takeIf { it.isNotBlank() }?.let { msg ->
            val tv = TextView(ctx).apply {
                text = msg
                textSize = 13.5f
                setTextColor(pal.sub)
                setLineSpacing(ctx.dp(4).toFloat(), 1f)
                includeFontPadding = false
                setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), 0)
                setTextIsSelectable(true)
            }
            // 短内容按内容高，长的封顶到屏高一半后滚动 —— 用 MaxHeightScrollView
            // 而不是写死高度，否则两行字的提示也会占掉半屏
            card.addView(HxScrollView(ctx, maxBodyHeight()).apply {
                isFillViewport = false
                addView(tv, LinearLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        spec.body?.let { b ->
            card.addView(b, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = ctx.dp(14)
                leftMargin = ctx.dp(20)
                rightMargin = ctx.dp(20)
            })
        }

        spec.items?.let { list ->
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(8), 0)
            }
            list.forEachIndexed { i, label ->
                box.addView(hxItemRow(ctx, pal, label) {
                    dismiss()
                    spec.onItem?.invoke(i)
                }, LinearLayout.LayoutParams(-1, -2))
                if (i != list.lastIndex) {
                    box.addView(hxDivider(ctx, pal), LinearLayout.LayoutParams(-1, ctx.dp(1)).apply {
                        leftMargin = ctx.dp(12)
                        rightMargin = ctx.dp(12)
                    })
                }
            }
            card.addView(
                HxScrollView(ctx, maxBodyHeight()).apply { addView(box) },
                LinearLayout.LayoutParams(-1, -2)
            )
        }

        // ---- 按钮区 ----
        val hasBtn = spec.positive != null || spec.negative != null || spec.neutral != null
        if (hasBtn) {
            card.addView(hxDivider(ctx, pal), LinearLayout.LayoutParams(-1, ctx.dp(1)).apply {
                topMargin = ctx.dp(18)
            })
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(12), ctx.dp(11), ctx.dp(12), ctx.dp(11))
            }
            fun add(which: Int, p: Pair<String, (() -> Unit)?>?, primary: Boolean, danger: Boolean) {
                val tv = hxButton(ctx, pal, p.first, primary, danger)
                tv.setOnClickListener {
                    // 有回调：先关弹窗再执行（跟 AlertDialog 行为一致）
                    dismiss()
                    p.second?.invoke()
                }
                buttons[which] = tv
                row.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(8) })
            }
            spec.negative?.let { add(BUTTON_NEGATIVE, it, false, false) }
            spec.neutral?.let { add(BUTTON_NEUTRAL, it, false, false) }
            spec.positive?.let { add(BUTTON_POSITIVE, it, true, spec.danger) }
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        outer.addView(card, FrameLayout.LayoutParams(-1, -2))
        return outer
    }

    // ==================== Builder ====================

    class Builder(private val ctx: Context, private val pal: Palette) {
        private val spec = Spec()

        fun setTitle(t: String) = apply { spec.title = t }
        fun setMessage(m: String?) = apply { spec.message = m }
        fun setView(v: View?) = apply { spec.body = v }
        fun setCancelable(b: Boolean) = apply { spec.cancelable = b }

        /** 危险动作：主按钮变红（删除类操作） */
        fun setDanger(d: Boolean) = apply { spec.danger = d }

        fun setItems(items: Array<String>, onItem: (Int) -> Unit) = apply {
            spec.items = items.toList()
            spec.onItem = onItem
        }

        fun setItems(items: List<String>, onItem: (Int) -> Unit) = apply {
            spec.items = items
            spec.onItem = onItem
        }

        fun setPositiveButton(text: String, listener: (() -> Unit)?) = apply {
            spec.positive = text to listener
        }

        fun setNegativeButton(text: String, listener: (() -> Unit)?) = apply {
            spec.negative = text to listener
        }

        fun setNeutralButton(text: String, listener: (() -> Unit)?) = apply {
            spec.neutral = text to listener
        }

        fun setOnShowListener(l: (HxDialog) -> Unit) = apply { spec.onShow = l }

        fun create(): HxDialog = HxDialog(ctx, pal, spec)

        fun show(): HxDialog {
            val d = create()
            d.show()
            return d
        }
    }

    companion object {
        // 跟 AlertDialog 用同一套常量值，调用点不必改语义
        const val BUTTON_POSITIVE = android.content.DialogInterface.BUTTON_POSITIVE
        const val BUTTON_NEGATIVE = android.content.DialogInterface.BUTTON_NEGATIVE
        const val BUTTON_NEUTRAL = android.content.DialogInterface.BUTTON_NEUTRAL
    }
}

/**
 * 有**最大高度**的 ScrollView：内容短时按内容高，超过 maxPx 才滚动。
 *
 * 系统 ScrollView 的 wrap_content 在「包一层再包一层」时经常量不准，
 * 写死高度又会让两行字的提示占掉半屏 —— 所以自己拦一次 onMeasure。
 */
internal class HxScrollView(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        if (measuredHeight > maxPx) {
            setMeasuredDimension(measuredWidth, maxPx)
        }
    }
}

/** 弹窗里的一行列表项：整行可点 + 水波纹 */
internal fun hxItemRow(ctx: Context, p: Palette, label: String, onClick: () -> Unit): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
        background = pressable(roundCard(ctx, p.card, p.card, 10, 0), 0x14000000)
        isClickable = true
        addView(TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(p.text)
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { onClick() }
    }
