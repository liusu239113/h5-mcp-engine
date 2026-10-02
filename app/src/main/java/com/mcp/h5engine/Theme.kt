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

/** 中等字重：标题用它，比 DEFAULT_BOLD 少三分火气 */
val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

/**
 * 等宽字体：模型名、路径、轮数、token 这类「技术信息」用它。
 * 编辑器观感有一半来自这个 —— 数字对齐、宽度固定，一眼就知道这是「工程视图」而不是聊天 App。
 */
val MONO: Typeface = Typeface.create("monospace", Typeface.NORMAL)

/**
 * 配色口径：中性纸感底 + 纯白卡面 + 一道发丝级描边 + 唯一强调色（深松石绿）。
 * 不用渐变、不用高饱和色块、不用表情符号：
 * 质感靠字距、留白、细描边和克制的对比度来做。
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
    val navIdle: Int,
    /** 底部状态栏（对齐游戏编辑器 / IDE 底部那条）：底色 + 文字 */
    val statusBg: Int,
    val statusFg: Int,
    /** 次强调色：只给「技术信息」用（模型名、路径、轮数），和主强调色区分开 */
    val accent2: Int,
    /** 工具调用组卡的底色 / 描边（比普通卡片再低一档，不抢正文） */
    val groupBg: Int,
    val groupBorder: Int
) {
    companion object {
        val LIGHT = Palette(
            dark = false,
            bg = 0xFFF5F5F3.toInt(),
            card = 0xFFFFFFFF.toInt(),
            cardAlt = 0xFFF0F0EE.toInt(),
            border = 0xFFE3E3E0.toInt(),
            text = 0xFF1A1A1A.toInt(),
            sub = 0xFF6B6B68.toInt(),
            faint = 0xFF9C9C98.toInt(),
            accent = 0xFF12695C.toInt(),
            accentSoft = 0xFFE7F0EE.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            userBubble = 0xFFE7F0EE.toInt(),
            userText = 0xFF12302B.toInt(),
            aiBubble = 0xFFFFFFFF.toInt(),
            aiText = 0xFF232323.toInt(),
            errBg = 0xFFFBEDEB.toInt(),
            errText = 0xFF9A2F22.toInt(),
            codeBg = 0xFFF0F0EE.toInt(),
            codeText = 0xFF5A5A57.toInt(),
            navBg = 0xFFFFFFFF.toInt(),
            navActive = 0xFF12695C.toInt(),
            navIdle = 0xFF8E8E8A.toInt(),
            statusBg = 0xFF12695C.toInt(),
            statusFg = 0xFFEAF4F2.toInt(),
            accent2 = 0xFF2E6BE6.toInt(),
            groupBg = 0xFFF7F8F7.toInt(),
            groupBorder = 0xFFE7E8E6.toInt()
        )

        val DARK = Palette(
            dark = true,
            bg = 0xFF0F1011.toInt(),
            card = 0xFF171819.toInt(),
            cardAlt = 0xFF1E1F21.toInt(),
            border = 0xFF2A2C2E.toInt(),
            text = 0xFFEDEDED.toInt(),
            sub = 0xFF9A9A9A.toInt(),
            faint = 0xFF6C6C6C.toInt(),
            accent = 0xFF4FCFB4.toInt(),
            accentSoft = 0xFF14302C.toInt(),
            onAccent = 0xFF06231E.toInt(),
            userBubble = 0xFF1B3733.toInt(),
            userText = 0xFFD3EFE9.toInt(),
            aiBubble = 0xFF1B1C1E.toInt(),
            aiText = 0xFFE9E9E9.toInt(),
            errBg = 0xFF2E1D1B.toInt(),
            errText = 0xFFE79A92.toInt(),
            codeBg = 0xFF1E1F21.toInt(),
            codeText = 0xFFAEB0B2.toInt(),
            navBg = 0xFF141516.toInt(),
            navActive = 0xFF4FCFB4.toInt(),
            navIdle = 0xFF85858A.toInt(),
            statusBg = 0xFF14302C.toInt(),
            statusFg = 0xFF7FE3CD.toInt(),
            accent2 = 0xFF6FA8FF.toInt(),
            groupBg = 0xFF141516.toInt(),
            groupBorder = 0xFF232527.toInt()
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

/** 小圆角描边卡面：半径收敛到 10~12dp，比大圆角更「工具感」 */
fun roundCard(ctx: Context, fill: Int, border: Int, radiusDp: Int = 12, borderDp: Int = 1): GradientDrawable {
    val g = GradientDrawable()
    g.cornerRadius = ctx.dp(radiusDp).toFloat()
    g.setColor(fill)
    if (borderDp > 0) g.setStroke(ctx.dp(borderDp), border)
    return g
}

/** 给任意背景套一层水波纹 —— 所有可点区域都有按下反馈 */
fun pressable(fill: Drawable, rippleColor: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), fill, null)

fun Context.cardBg(p: Palette, radiusDp: Int = 12, fill: Int? = null, border: Int? = null): Drawable =
    pressable(
        roundCard(this, fill ?: p.card, border ?: p.border, radiusDp),
        0x14000000
    )

// ==================== 常用控件 ====================

fun labelOf(ctx: Context, p: Palette, t: String, size: Float = 12f, color: Int? = null, bold: Boolean = false): TextView =
    TextView(ctx).apply {
        text = t
        textSize = size
        setTextColor(color ?: p.sub)
        if (bold) typeface = MEDIUM
    }

/** 胶囊标签：可点，带按下反馈。字距 +0.04 让中文标签不那么「挤」 */
fun chipOf(ctx: Context, p: Palette, t: String, active: Boolean): TextView =
    TextView(ctx).apply {
        text = t
        textSize = 12.5f
        letterSpacing = 0.04f
        gravity = Gravity.CENTER
        setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        setTextColor(if (active) p.onAccent else p.text)
        background = pressable(
            roundCard(ctx, if (active) p.accent else p.cardAlt, if (active) p.accent else p.border, 10),
            0x14000000
        )
    }

/** 主要按钮（实心） */
fun primaryBtnOf(ctx: Context, p: Palette, t: String): TextView =
    TextView(ctx).apply {
        text = t
        textSize = 14f
        letterSpacing = 0.1f
        gravity = Gravity.CENTER
        setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
        setTextColor(p.onAccent)
        typeface = MEDIUM
        background = pressable(roundCard(ctx, p.accent, p.accent, 12, 0), 0x2AFFFFFF)
    }

/** 次要按钮（描边） */
fun ghostBtnOf(ctx: Context, p: Palette, t: String): TextView =
    TextView(ctx).apply {
        text = t
        textSize = 13f
        letterSpacing = 0.06f
        gravity = Gravity.CENTER
        setPadding(ctx.dp(16), ctx.dp(11), ctx.dp(16), ctx.dp(11))
        setTextColor(p.text)
        background = pressable(roundCard(ctx, p.card, p.border, 12), 0x14000000)
    }

/** 一行列表项：标题 + 说明 + 右侧箭头（线稿），整行可点且有反馈 */
fun listRowOf(ctx: Context, p: Palette, title: String, sub: String): LinearLayout {
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(14), ctx.dp(14))
        background = ctx.cardBg(p)
    }
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    col.addView(labelOf(ctx, p, title, 14.5f, p.text, true))
    if (sub.isNotBlank()) {
        col.addView(TextView(ctx).apply {
            text = sub
            textSize = 11.5f
            setTextColor(p.sub)
            setPadding(0, ctx.dp(4), 0, 0)
        })
    }
    row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(iconView(ctx, p, "chevron", 13, p.faint),
        LinearLayout.LayoutParams(ctx.dp(15), ctx.dp(15)).apply { leftMargin = ctx.dp(8) })
    return row
}

fun View.visibleIf(b: Boolean) {
    visibility = if (b) View.VISIBLE else View.GONE
}