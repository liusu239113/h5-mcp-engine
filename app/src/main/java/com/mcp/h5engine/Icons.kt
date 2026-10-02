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
    /**
     * 【已废弃，保留只为不改动一堆调用点】
     *
     * 这个参数历史上被当成「线宽」传，但它是**缩放前**的路径坐标单位：
     * draw() 里会 canvas.scale(图标边长 / 24)，所以传进来的值会被乘上一个 s。
     * 调用点普遍传的是「图标尺寸」（dp(16) / dp(17) 这种），一乘就变成上百像素的线宽，
     * 整张图标糊成一个实心方块 —— 这就是界面上那些「绿色小方块」的由来。
     *
     * 现在线宽在 24 单位的路径网格上固定取值，任何尺寸下都是同一支笔画的，
     * 画风自然统一。参数留着是因为调用点太多，逐个改收益不大、风险更大。
     */
    @Suppress("UNUSED_PARAMETER")
    private val stroke: Float
) : Drawable() {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // 24 单位网格上的 1.8 ≈ 视觉上 1.5~2dp 的线，够细不糊，小图标（11~13dp）也看得清
        strokeWidth = 1.8f
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
            // 复制：两张叠在一起的纸（手绘感靠起收笔的圆头 + 不闭合的左上角）
            "copy" -> {
                path.moveTo(9.2f, 8.6f)
                path.lineTo(19f, 8.6f)
                path.lineTo(19f, 19.4f)
                path.lineTo(9.2f, 19.4f)
                path.close()
                path.moveTo(15.4f, 5.2f)
                path.lineTo(5.2f, 5.2f)
                path.lineTo(5.2f, 15.2f)
            }
            // 链接：两个相互扣住的环
            "link" -> {
                path.addCircle(9.6f, 12f, 3.4f, Path.Direction.CW)
                path.addCircle(14.4f, 12f, 3.4f, Path.Direction.CW)
                path.moveTo(11.4f, 12f)
                path.lineTo(12.6f, 12f)
            }
            // 打开：方框 + 冲出右上角的箭头
            "open" -> {
                path.moveTo(9.4f, 7.2f)
                path.lineTo(5f, 7.2f)
                path.lineTo(5f, 19f)
                path.lineTo(16.8f, 19f)
                path.lineTo(16.8f, 14.6f)
                path.moveTo(11.6f, 5f)
                path.lineTo(19f, 5f)
                path.lineTo(19f, 12.4f)
                path.moveTo(19f, 5f)
                path.lineTo(10.6f, 13.4f)
            }
            // 向下箭头：回到底部按钮
            "down" -> {
                path.moveTo(12f, 5.2f)
                path.lineTo(12f, 18.6f)
                path.moveTo(6.6f, 13.2f)
                path.lineTo(12f, 18.6f)
                path.lineTo(17.4f, 13.2f)
            }
            // 小喇叭 + 两道声波：预览「有声」
            "volume" -> {
                path.moveTo(4f, 9.6f)
                path.lineTo(7.4f, 9.6f)
                path.lineTo(11.4f, 5.8f)
                path.lineTo(11.4f, 18.2f)
                path.lineTo(7.4f, 14.4f)
                path.lineTo(4f, 14.4f)
                path.close()
                path.moveTo(14.4f, 9.6f)
                path.quadTo(16.1f, 12f, 14.4f, 14.4f)
                path.moveTo(17.4f, 7.2f)
                path.quadTo(20.4f, 12f, 17.4f, 16.8f)
            }
            // 小喇叭 + 叉：预览「已静音」
            "mute" -> {
                path.moveTo(4f, 9.6f)
                path.lineTo(7.4f, 9.6f)
                path.lineTo(11.4f, 5.8f)
                path.lineTo(11.4f, 18.2f)
                path.lineTo(7.4f, 14.4f)
                path.lineTo(4f, 14.4f)
                path.close()
                path.moveTo(15f, 9.7f)
                path.lineTo(20.2f, 14.9f)
                path.moveTo(20.2f, 9.7f)
                path.lineTo(15f, 14.9f)
            }
            // 放大镜 + 加号：放大画面
            "zoom_in" -> {
                path.addCircle(10.6f, 10.6f, 6.4f, Path.Direction.CW)
                path.moveTo(15.4f, 15.4f)
                path.lineTo(20.4f, 20.4f)
                path.moveTo(10.6f, 7.8f)
                path.lineTo(10.6f, 13.4f)
                path.moveTo(7.8f, 10.6f)
                path.lineTo(13.4f, 10.6f)
            }
            // 放大镜 + 减号：缩小画面
            "zoom_out" -> {
                path.addCircle(10.6f, 10.6f, 6.4f, Path.Direction.CW)
                path.moveTo(15.4f, 15.4f)
                path.lineTo(20.4f, 20.4f)
                path.moveTo(7.8f, 10.6f)
                path.lineTo(13.4f, 10.6f)
            }
            // 相机：把当前画面发给 AI
            "camera" -> {
                path.moveTo(3.6f, 8.6f)
                path.lineTo(7.6f, 8.6f)
                path.lineTo(9.2f, 6.2f)
                path.lineTo(14.8f, 6.2f)
                path.lineTo(16.4f, 8.6f)
                path.lineTo(20.4f, 8.6f)
                path.lineTo(20.4f, 19f)
                path.lineTo(3.6f, 19f)
                path.close()
                path.addCircle(12f, 13.6f, 3.2f, Path.Direction.CW)
            }
            // 齿轮：设置入口（替代 ⚙）
            "gear" -> {
                path.addCircle(12f, 12f, 3.3f, Path.Direction.CW)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    val c = Math.cos(a).toFloat()
                    val s = Math.sin(a).toFloat()
                    path.moveTo(12f + c * 5.6f, 12f + s * 5.6f)
                    path.lineTo(12f + c * 8.3f, 12f + s * 8.3f)
                }
            }
            // 音符：音频素材（替代 🎵）
            "music" -> {
                path.moveTo(9.2f, 17.6f)
                path.lineTo(9.2f, 7.4f)
                path.lineTo(18.2f, 5.4f)
                path.lineTo(18.2f, 15.6f)
                path.addCircle(6.9f, 17.9f, 2.4f, Path.Direction.CW)
                path.addCircle(15.9f, 15.9f, 2.4f, Path.Direction.CW)
            }
            // 对勾：成功状态（替代 ✓ / ✅）
            "check" -> {
                path.moveTo(5f, 12.6f)
                path.lineTo(9.9f, 17.5f)
                path.lineTo(19f, 6.9f)
            }
            // 叉：失败 / 关闭（替代 ✕ / ❌）
            "close" -> {
                path.moveTo(6.2f, 6.2f)
                path.lineTo(17.8f, 17.8f)
                path.moveTo(17.8f, 6.2f)
                path.lineTo(6.2f, 17.8f)
            }
            // 拼图：子任务（替代 🧩）
            "puzzle" -> {
                path.moveTo(5f, 9.2f)
                path.lineTo(9.2f, 9.2f)
                path.lineTo(9.2f, 5.4f)
                path.lineTo(14.8f, 5.4f)
                path.lineTo(14.8f, 9.2f)
                path.lineTo(19f, 9.2f)
                path.lineTo(19f, 14.8f)
                path.lineTo(14.8f, 14.8f)
                path.lineTo(14.8f, 18.6f)
                path.lineTo(9.2f, 18.6f)
                path.lineTo(9.2f, 14.8f)
                path.lineTo(5f, 14.8f)
                path.close()
            }
            // 播放三角
            "play" -> {
                path.moveTo(8f, 5.6f)
                path.lineTo(19f, 12f)
                path.lineTo(8f, 18.4f)
                path.close()
            }
            // 停止方块
            "stop" -> {
                path.moveTo(7f, 7f)
                path.lineTo(17f, 7f)
                path.lineTo(17f, 17f)
                path.lineTo(7f, 17f)
                path.close()
            }
            // 垃圾桶：删除
            "trash" -> {
                path.moveTo(4.6f, 7f)
                path.lineTo(19.4f, 7f)
                path.moveTo(9.5f, 7f)
                path.lineTo(9.5f, 4.8f)
                path.lineTo(14.5f, 4.8f)
                path.lineTo(14.5f, 7f)
                path.moveTo(6.6f, 7f)
                path.lineTo(7.7f, 19.4f)
                path.lineTo(16.3f, 19.4f)
                path.lineTo(17.4f, 7f)
                path.moveTo(10.4f, 10.4f)
                path.lineTo(10.4f, 16.4f)
                path.moveTo(13.6f, 10.4f)
                path.lineTo(13.6f, 16.4f)
            }
            // 铅笔：编辑
            "edit" -> {
                path.moveTo(4.8f, 19.2f)
                path.lineTo(5.6f, 15.1f)
                path.lineTo(15.9f, 4.8f)
                path.lineTo(19.2f, 8.1f)
                path.lineTo(8.9f, 18.4f)
                path.close()
                path.moveTo(14.2f, 6.5f)
                path.lineTo(17.5f, 9.8f)
            }
            // 纸飞机：发送
            "send" -> {
                path.moveTo(4.4f, 12f)
                path.lineTo(19.6f, 4.8f)
                path.lineTo(14.9f, 19.2f)
                path.lineTo(11.5f, 13.3f)
                path.close()
                path.moveTo(11.5f, 13.3f)
                path.lineTo(19.6f, 4.8f)
            }
            // 五角星：默认配置标记
            "star" -> {
                val cx = 12f
                val cy = 12.4f
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 8.2f else 3.5f
                    val a = Math.toRadians((-90 + i * 36).toDouble())
                    val x = cx + (Math.cos(a) * r).toFloat()
                    val y = cy + (Math.sin(a) * r).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            // 盾牌 + 勾：授权 / 已就绪
            "shield" -> {
                path.moveTo(12f, 3.6f)
                path.lineTo(19.4f, 6.6f)
                path.lineTo(19.4f, 12f)
                path.quadTo(19.4f, 18f, 12f, 20.6f)
                path.quadTo(4.6f, 18f, 4.6f, 12f)
                path.lineTo(4.6f, 6.6f)
                path.close()
                path.moveTo(8.8f, 11.9f)
                path.lineTo(11.2f, 14.3f)
                path.lineTo(15.4f, 9.5f)
            }
            // 钥匙：密钥 / 凭据
            "key" -> {
                path.addCircle(8.2f, 12f, 3.6f, Path.Direction.CW)
                path.moveTo(11.5f, 12f)
                path.lineTo(20f, 12f)
                path.moveTo(17.4f, 12f)
                path.lineTo(17.4f, 15.2f)
                path.moveTo(20f, 12f)
                path.lineTo(20f, 14.6f)
            }
            // 机器人：AI
            "robot" -> {
                path.moveTo(5f, 9.6f)
                path.lineTo(19f, 9.6f)
                path.lineTo(19f, 18.6f)
                path.lineTo(5f, 18.6f)
                path.close()
                path.moveTo(12f, 9.6f)
                path.lineTo(12f, 6.6f)
                path.addCircle(12f, 5.4f, 1.2f, Path.Direction.CW)
                path.addCircle(9.4f, 14.1f, 1.1f, Path.Direction.CW)
                path.addCircle(14.6f, 14.1f, 1.1f, Path.Direction.CW)
            }
            // 右尖角：列表项尾部箭头（替代 ❯ 文本）
            "chevron" -> {
                path.moveTo(9.6f, 5.6f)
                path.lineTo(16f, 12f)
                path.lineTo(9.6f, 18.4f)
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

/**
 * 小图标 ImageView —— 用来**替代 emoji TextView**。
 *
 * 界面上原来散着一批 `text = "🎵"` / `"📁"` / `"✓"` 这类写法：
 * emoji 的字形由系统字体决定，跟主题色脱节、深色模式下还会发彩，
 * 跟这套「单色线稿」的视觉口径冲突。换成 LineIcon 现描即可统一。
 *
 * @param sizeDp 图标本体尺寸（dp），内部已按 24 单位网格等比缩放
 */
fun iconView(ctx: Context, p: Palette, kind: String, sizeDp: Int, color: Int? = null): ImageView =
    ImageView(ctx).apply {
        setImageDrawable(LineIcon(kind, color ?: p.sub, 1.8f))
        // LineIcon 没实现 getIntrinsicWidth，ImageView 会按 0 处理 WRAP_CONTENT ——
        // 所以这里必须自带一个「有尺寸」的 LayoutParams，调用方不传也不会缩没。
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
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