package com.mcp.h5engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

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
            // 素材：方框 + 小圆（太阳）+ 山形折线
            "image" -> {
                path.moveTo(3.5f, 5.5f)
                path.lineTo(20.5f, 5.5f)
                path.lineTo(20.5f, 18.5f)
                path.lineTo(3.5f, 18.5f)
                path.close()
                path.addCircle(8.6f, 10.2f, 1.6f, Path.Direction.CW)
                path.moveTo(5.6f, 16.6f)
                path.lineTo(10.4f, 12.3f)
                path.lineTo(13.6f, 15.1f)
                path.lineTo(16.2f, 13.1f)
                path.lineTo(19.4f, 16.6f)
            }
            // 视频：方框 + 播放三角
            "video" -> {
                path.moveTo(3.5f, 5.5f)
                path.lineTo(20.5f, 5.5f)
                path.lineTo(20.5f, 18.5f)
                path.lineTo(3.5f, 18.5f)
                path.close()
                path.moveTo(10.4f, 9.6f)
                path.lineTo(15.6f, 12f)
                path.lineTo(10.4f, 14.4f)
                path.close()
            }
            // 音频：三根竖条（声波）
            "audio" -> {
                path.moveTo(6.5f, 10f)
                path.lineTo(6.5f, 14f)
                path.moveTo(12f, 6.6f)
                path.lineTo(12f, 17.4f)
                path.moveTo(17.5f, 9f)
                path.lineTo(17.5f, 15f)
            }
            // 文档：折角纸页 + 两条横线
            "doc" -> {
                path.moveTo(6f, 4f)
                path.lineTo(14f, 4f)
                path.lineTo(18.5f, 8.5f)
                path.lineTo(18.5f, 20f)
                path.lineTo(6f, 20f)
                path.close()
                path.moveTo(14f, 4f)
                path.lineTo(14f, 8.5f)
                path.lineTo(18.5f, 8.5f)
                path.moveTo(8.6f, 13f)
                path.lineTo(15.9f, 13f)
                path.moveTo(8.6f, 16.4f)
                path.lineTo(13.4f, 16.4f)
            }
            // 技能：闪电
            "skill" -> {
                path.moveTo(13.4f, 3.5f)
                path.lineTo(7.2f, 13.2f)
                path.lineTo(11.6f, 13.2f)
                path.lineTo(10.4f, 20.5f)
                path.lineTo(16.8f, 10.6f)
                path.lineTo(12.2f, 10.6f)
                path.close()
            }
            // 全屏：四角括号
            "fullscreen" -> {
                path.moveTo(4.5f, 9.5f)
                path.lineTo(4.5f, 4.5f)
                path.lineTo(9.5f, 4.5f)
                path.moveTo(14.5f, 4.5f)
                path.lineTo(19.5f, 4.5f)
                path.lineTo(19.5f, 9.5f)
                path.moveTo(19.5f, 14.5f)
                path.lineTo(19.5f, 19.5f)
                path.lineTo(14.5f, 19.5f)
                path.moveTo(9.5f, 19.5f)
                path.lineTo(4.5f, 19.5f)
                path.lineTo(4.5f, 14.5f)
            }
            // 列表视图：三条横线
            "list" -> {
                path.moveTo(9f, 7f)
                path.lineTo(20f, 7f)
                path.moveTo(9f, 12f)
                path.lineTo(20f, 12f)
                path.moveTo(9f, 17f)
                path.lineTo(20f, 17f)
                path.moveTo(4.6f, 7f)
                path.lineTo(5.4f, 7f)
                path.moveTo(4.6f, 12f)
                path.lineTo(5.4f, 12f)
                path.moveTo(4.6f, 17f)
                path.lineTo(5.4f, 17f)
            }
            // 网格视图：四宫格
            "grid" -> {
                path.moveTo(4.5f, 4.5f)
                path.lineTo(10.5f, 4.5f)
                path.lineTo(10.5f, 10.5f)
                path.lineTo(4.5f, 10.5f)
                path.close()
                path.moveTo(13.5f, 4.5f)
                path.lineTo(19.5f, 4.5f)
                path.lineTo(19.5f, 10.5f)
                path.lineTo(13.5f, 10.5f)
                path.close()
                path.moveTo(4.5f, 13.5f)
                path.lineTo(10.5f, 13.5f)
                path.lineTo(10.5f, 19.5f)
                path.lineTo(4.5f, 19.5f)
                path.close()
                path.moveTo(13.5f, 13.5f)
                path.lineTo(19.5f, 13.5f)
                path.lineTo(19.5f, 19.5f)
                path.lineTo(13.5f, 19.5f)
                path.close()
            }
            // 搜索：圆 + 斜柄
            "search" -> {
                path.addCircle(10.8f, 10.8f, 6.3f, Path.Direction.CW)
                path.moveTo(15.4f, 15.4f)
                path.lineTo(20f, 20f)
            }
            // 上传：托盘 + 上箭头
            "upload" -> {
                path.moveTo(4.5f, 15f)
                path.lineTo(4.5f, 19.5f)
                path.lineTo(19.5f, 19.5f)
                path.lineTo(19.5f, 15f)
                path.moveTo(12f, 16f)
                path.lineTo(12f, 5f)
                path.moveTo(7.8f, 9.2f)
                path.lineTo(12f, 5f)
                path.lineTo(16.2f, 9.2f)
            }
            // 文件夹（小尺寸行内用）
            "folder_sm" -> {
                path.moveTo(4f, 8f)
                path.lineTo(4f, 18.5f)
                path.lineTo(20f, 18.5f)
                path.lineTo(20f, 9f)
                path.lineTo(11.5f, 9f)
                path.lineTo(9.8f, 6.2f)
                path.lineTo(4f, 6.2f)
                path.close()
            }
            // 更多：三个竖点
            "more" -> {
                path.moveTo(12f, 5.4f)
                path.lineTo(12f, 6.4f)
                path.moveTo(12f, 11.5f)
                path.lineTo(12f, 12.5f)
                path.moveTo(12f, 17.6f)
                path.lineTo(12f, 18.6f)
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

/**
 * 工作区入口卡：24dp 手绘图标 + 下方 11.5sp 文字。
 * 这是 Maker 那种「完整操作区」，不是三个小方按钮 —— 上一版就是栽在这里。
 */
fun entryTile(
    ctx: Context,
    p: Palette,
    kind: String,
    label: String,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): LinearLayout {
    val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(ctx.dp(2), ctx.dp(8), ctx.dp(2), ctx.dp(7))
        background = pressable(roundCard(ctx, p.cardAlt, p.border, 12), 0x14000000)
        isClickable = true
    }
    val icon = ImageView(ctx).apply {
        // 42dp 容器 - 9dp*2 内边距 = 24dp 图标本体
        setImageDrawable(LineIcon(kind, p.sub, ctx.dp(2).toFloat()))
        setPadding(ctx.dp(9), ctx.dp(9), ctx.dp(9), ctx.dp(9))
    }
    col.addView(icon, LinearLayout.LayoutParams(ctx.dp(42), ctx.dp(42)))
    col.addView(TextView(ctx).apply {
        text = label
        textSize = 11.5f
        letterSpacing = 0.04f
        gravity = Gravity.CENTER
        setTextColor(p.sub)
        setPadding(0, ctx.dp(5), 0, 0)
    })
    col.setOnClickListener { onClick() }
    if (onLongClick != null) col.setOnLongClickListener { onLongClick(); true }
    return col
}

/** 待发送区 / 文件列表的一行：小图标 + 名字 + 可选尾部「＋」 */
fun attachRowOf(
    ctx: Context,
    p: Palette,
    kind: String,
    name: String,
    tail: String?,
    onTail: (() -> Unit)? = null
): LinearLayout {
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(9), ctx.dp(6), ctx.dp(9), ctx.dp(6))
        background = pressable(roundCard(ctx, p.cardAlt, p.border, 9), 0x14000000)
        isClickable = true
    }
    val iv = ImageView(ctx).apply {
        setImageDrawable(LineIcon(kind, p.sub, ctx.dp(2).toFloat()))
        setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
    }
    row.addView(iv, LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)))
    row.addView(TextView(ctx).apply {
        text = name
        textSize = 11.5f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        setTextColor(p.text)
        setPadding(ctx.dp(5), 0, ctx.dp(5), 0)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    if (tail != null) {
        row.addView(TextView(ctx).apply {
            text = tail
            textSize = 12.5f
            setTextColor(p.faint)
            gravity = Gravity.CENTER
            setPadding(ctx.dp(7), ctx.dp(2), ctx.dp(3), ctx.dp(2))
        }.apply {
            if (onTail != null) setOnClickListener { onTail() }
        })
    }
    return row
}