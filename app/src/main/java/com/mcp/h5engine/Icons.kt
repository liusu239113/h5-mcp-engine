package com.mcp.h5engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * 手绘风线性图标：用 Path 直接描线，不用 emoji、不用字体图标。
 * 好处是线宽/颜色都能跟主题走，缩放也不会糊。
 */
class LineIcon(
    private val kind: String,
    private val color: Int,
    private val stroke: Float
) : Drawable() {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = this@LineIcon.color
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val s = minOf(w, h) / 24f
        canvas.save()
        canvas.translate(b.left + (w - 24f * s) / 2f, b.top + (h - 24f * s) / 2f)
        canvas.scale(s, s)

        val path = Path()
        when (kind) {
            // 文件夹：一条折线勾出的「文件」
            "folder" -> {
                path.moveTo(3f, 7.5f)
                path.lineTo(3f, 19f)
                path.lineTo(21f, 19f)
                path.lineTo(21f, 8.5f)
                path.lineTo(11.5f, 8.5f)
                path.lineTo(9.5f, 5.5f)
                path.lineTo(3f, 5.5f)
                path.close()
                path.moveTo(6.8f, 12.5f)
                path.lineTo(15.5f, 12.5f)
                path.moveTo(6.8f, 15.6f)
                path.lineTo(12.5f, 15.6f)
            }
            // 图层：三层斜切，像 Maker 那个结构图标
            "layers" -> {
                path.moveTo(12f, 3.5f)
                path.lineTo(20.5f, 8f)
                path.lineTo(12f, 12.5f)
                path.lineTo(3.5f, 8f)
                path.close()
                path.moveTo(4.5f, 12.4f)
                path.lineTo(12f, 16.4f)
                path.lineTo(19.5f, 12.4f)
                path.moveTo(4.5f, 16.6f)
                path.lineTo(12f, 20.6f)
                path.lineTo(19.5f, 16.6f)
            }
            // 代码：一对尖括号
            "code" -> {
                path.moveTo(9.5f, 6.5f)
                path.lineTo(4f, 12f)
                path.lineTo(9.5f, 17.5f)
                path.moveTo(14.5f, 6.5f)
                path.lineTo(20f, 12f)
                path.lineTo(14.5f, 17.5f)
            }
            // 加号
            "plus" -> {
                path.moveTo(12f, 5f)
                path.lineTo(12f, 19f)
                path.moveTo(5f, 12f)
                path.lineTo(19f, 12f)
            }
            // 对话气泡
            "chat" -> {
                path.moveTo(4.5f, 6f)
                path.lineTo(19.5f, 6f)
                path.lineTo(19.5f, 16f)
                path.lineTo(9.5f, 16f)
                path.lineTo(5f, 20f)
                path.lineTo(5f, 16f)
                path.close()
            }
            // 刷新（环形箭头，简化为两段弧 + 一个尖）
            "refresh" -> {
                path.addArc(4.5f, 4.5f, 19.5f, 19.5f, -70f, 250f)
                path.moveTo(19.5f, 4.5f)
                path.lineTo(19.5f, 10f)
                path.lineTo(14f, 10f)
            }
        }
        canvas.drawPath(path, p)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        p.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        p.colorFilter = cf
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 图标按钮：38dp 方，带发丝描边和按下反馈 */
fun iconButton(
    ctx: Context,
    p: Palette,
    kind: String,
    sizeDp: Int = 38,
    marginStartDp: Int = 0,
    onClick: () -> Unit
): View = ImageView(ctx).apply {
    setImageDrawable(LineIcon(kind, p.sub, ctx.dp(1.9f.toInt().coerceAtLeast(2)).toFloat()))
    setPadding(ctx.dp(9), ctx.dp(9), ctx.dp(9), ctx.dp(9))
    background = pressable(roundCard(ctx, p.cardAlt, p.border, 10), 0x14000000)
    layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp)).apply {
        leftMargin = ctx.dp(marginStartDp)
    }
    setOnClickListener { onClick() }
}