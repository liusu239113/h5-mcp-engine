package com.mcp.h5engine

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

enum class ThemeMode { LIGHT, DARK, AUTO }

/**
 * 手绘简洁风配色：米白纸感底 + 圆角卡片 + 薄荷绿点缀。
 * 浅色是默认，深色是可选，不再是「黑黢黢」。
 */
data class Palette(
    val dark: Boolean,
    val bg: Int,
    val card: Int,
    val cardAlt: Int,
    val border: Int,
    val text: Int,
    val sub: Int,
    val faint: Int,
    val accent: Int,
    val accentSoft: Int,
    val onAccent: Int,
    val userBubble: Int,
    val userText: Int,
    val aiBubble: Int,
    val aiText: Int,
    val errBg: Int,
    val errText: Int,
    val codeBg: Int,
    val codeText: Int,
    val navBg: Int,
    val navActive: Int,
    val navIdle: Int
) {
    companion object {
        val LIGHT = Palette(
            dark = false,
            bg = 0xFFFAF7F2.toInt(),
            card = 0xFFFFFFFF.toInt(),
            cardAlt = 0xFFF4F0E9.toInt(),
            border = 0xFFE7E0D4.toInt(),
            text = 0xFF2A2925.toInt(),
            sub = 0xFF7A7469.toInt(),
            faint = 0xFFA9A296.toInt(),
            accent = 0xFF12BFA3.toInt(),
            accentSoft = 0xFFE3F6F2.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            userBubble = 0xFFDAF1EC.toInt(),
            userText = 0xFF17332E.toInt(),
            aiBubble = 0xFFFFFFFF.toInt(),
            aiText = 0xFF33322D.toInt(),
            errBg = 0xFFFDECEA.toInt(),
            errText = 0xFFB3352A.toInt(),
            codeBg = 0xFFF4F0E9.toInt(),
            codeText = 0xFF6A6355.toInt(),
            navBg = 0xFFFFFFFF.toInt(),
            navActive = 0xFF12BFA3.toInt(),
            navIdle = 0xFF9A948A.toInt()
        )

        val DARK = Palette(
            dark = true,
            bg = 0xFF16181C.toInt(),
            card = 0xFF1E2126.toInt(),
            cardAlt = 0xFF262A30.toInt(),
            border = 0xFF333941.toInt(),
            text = 0xFFECEDEF.toInt(),
            sub = 0xFF9BA1AA.toInt(),
            faint = 0xFF6E747D.toInt(),
            accent = 0xFF3BD6BC.toInt(),
            accentSoft = 0xFF1D3A36.toInt(),
            onAccent = 0xFF06231E.toInt(),
            userBubble = 0xFF24463F.toInt(),
            userText = 0xFFD7F2EC.toInt(),
            aiBubble = 0xFF22262C.toInt(),
            aiText = 0xFFECEDEF.toInt(),
            errBg = 0xFF3A2326.toInt(),
            errText = 0xFFFF9A93.toInt(),
            codeBg = 0xFF262A30.toInt(),
            codeText = 0xFFB9C0C8.toInt(),
            navBg = 0xFF1B1E22.toInt(),
            navActive = 0xFF3BD6BC.toInt(),
            navIdle = 0xFF828892.toInt()
        )
    }
}

fun paletteOf(ctx: Context, mode: ThemeMode): Palette = when (mode) {
    ThemeMode.LIGHT -> Palette.LIGHT
    ThemeMode.DARK -> Palette.DARK
    ThemeMode.AUTO -> {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night) Palette.DARK else Palette.LIGHT
    }
}

fun themeModeOf(id: String): ThemeMode = when (id) {
    "dark" -> ThemeMode.DARK
    "auto" -> ThemeMode.AUTO
    else -> ThemeMode.LIGHT
}

// ==================== 尺寸 / 背景 ====================

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** 圆角描边卡片背景 */
fun roundCard(ctx: Context, fill: Int, border: Int, radiusDp: Int = 14, borderDp: Int = 1): GradientDrawable {
    val g = GradientDrawable()
    g.cornerRadius = ctx.dp(radiusDp).toFloat()
    g.setColor(fill)
    if (borderDp > 0) g.setStroke(ctx.dp(borderDp), border)
    return g
}

/** 给任意背景套一层水波纹 —— 所有可点区域都有按下反馈 */
fun pressable(fill: Drawable, rippleColor: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), fill, null)

fun Context.cardBg(p: Palette, radiusDp: Int = 14, fill: Int? = null, border: Int? = null): Drawable =
    pressable(
        roundCard(this, fill ?: p.card, border ?: p.border, radiusDp),
        0x3312BFA3
    )

// ==================== 常用控件 ====================

fun labelOf(ctx: Context, p: Palette, t: String, size: Float = 12f, color: Int? = null, bold: Boolean = false): TextView =
    TextView(ctx).apply {
        text = t
        textSize = size
        setTextColor(color ?: p.sub)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

/** 胶囊标签：可点，带按下反馈 */
fun chipOf(ctx: Context, p: Palette, t: String, active: Boolean): TextView =
    TextView(ctx).apply {
        text = t
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(ctx.dp(12), ctx.dp(7), ctx.dp(12), ctx.dp(7))
        setTextColor(if (active) p.onAccent else p.text)
        background = pressable(
            roundCard(ctx, if (active) p.accent else p.cardAlt, if (active) p.accent else p.border, 20),
            0x55FFFFFF
        )
    }

/** 主要按钮（实心） */
fun primaryBtnOf(ctx: Context, p: Palette, t: String): TextView =
    TextView(ctx).apply {
        text = t
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(ctx.dp(18), ctx.dp(11), ctx.dp(18), ctx.dp(11))
        setTextColor(p.onAccent)
        typeface = Typeface.DEFAULT_BOLD
        background = pressable(roundCard(ctx, p.accent, p.accent, 22, 0), 0x66FFFFFF)
    }

/** 次要按钮（描边） */
fun ghostBtnOf(ctx: Context, p: Palette, t: String): TextView =
    TextView(ctx).apply {
        text = t
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
        setTextColor(p.text)
        background = pressable(roundCard(ctx, p.card, p.border, 22), 0x3312BFA3)
    }

/** 一行列表项：标题 + 说明 + 右侧箭头，整行可点且有反馈 */
fun listRowOf(ctx: Context, p: Palette, title: String, sub: String, arrow: String = "❯"): LinearLayout {
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(14), ctx.dp(13), ctx.dp(12), ctx.dp(13))
        background = ctx.cardBg(p)
    }
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    col.addView(labelOf(ctx, p, title, 14.5f, p.text, true))
    if (sub.isNotBlank()) {
        col.addView(TextView(ctx).apply {
            text = sub
            textSize = 11.5f
            setTextColor(p.sub)
            setPadding(0, ctx.dp(3), 0, 0)
        })
    }
    row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(TextView(ctx).apply {
        text = arrow
        textSize = 13f
        setTextColor(p.faint)
        setPadding(ctx.dp(8), 0, ctx.dp(4), 0)
    })
    return row
}

fun View.visibleIf(b: Boolean) {
    visibility = if (b) View.VISIBLE else View.GONE
}