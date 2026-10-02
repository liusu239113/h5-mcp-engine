package com.mcp.h5engine

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 「谁在动我的文件？」—— 记录每一次写入和重载。
 *
 * ## 为什么需要它
 *
 * 真实事故（用户原话「我就开了一个会话，哪来的这种 bug，谁在动我的文件？」）：
 * 模型写完代码 → App **自动热重载**（这是设计好的行为）→ 模型发现页面
 * 「自己 1.5 秒内零变动、回到了主菜单」→ 它不知道重载是自己写文件引起的，
 * 于是得出结论：**「有人在外部写文件触发热重载」**。
 *
 * 然后它就跑去排查一个根本不存在的外部进程，用户看着它在查一个幻觉。
 *
 * 根因不是「有鬼」，而是**信息缺失**：App 替模型做了重载，却没告诉它。
 * 所以这里把「谁、什么时候、动了哪个文件、多大、触发了什么」记下来，
 * 模型怀疑的时候直接查这份流水就能自证 —— 不用猜。
 *
 * 记录范围刻意只放**会改变游戏内容的动作**（写文件 / 重载 / 建项目），
 * 不放日志、缓存这类噪音 —— 流水要能一眼看完。
 */
object FileJournal {

    /** @param source 谁干的：game_write / game_patch / game_create / 用户编辑 / auto-reload … */
    data class Entry(
        val at: Long,
        val source: String,
        val path: String,
        val bytes: Long,
        val note: String
    )

    /** 只留最近这些条：流水是给「刚才发生了什么」用的，不是审计日志 */
    private const val MAX = 200

    private val items = ArrayDeque<Entry>()

    @Synchronized
    fun record(source: String, path: String, bytes: Long, note: String = "") {
        items.addLast(Entry(System.currentTimeMillis(), source, path, bytes, note))
        while (items.size > MAX) items.removeFirst()
    }

    @Synchronized
    fun tail(n: Int): List<Entry> = items.toList().takeLast(n.coerceAtLeast(1))

    @Synchronized
    fun clear() = items.clear()

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * 给模型看的流水。**首行必须写清结论** —— 模型经常只读第一行。
     */
    fun render(n: Int = 40): String {
        val list = tail(n)
        if (list.isEmpty()) {
            return "这段时间**没有任何写入 / 重载记录**。\n" +
                "如果你看到页面自己刷新了，那不是文件被改 —— 可能是页面自身的逻辑" +
                "（定时器 / 状态机 / 音频策略）在动，去读代码找原因。"
        }
        val sb = StringBuilder()
        sb.append("最近 ${list.size} 条文件改动 / 重载记录（旧 → 新）：\n")
        sb.append("⚠️ 这些**全部**是本 App 自己做的 —— 没有外部进程、没有别的程序。\n")
        sb.append("   写文件的只可能是三个来源，看 source 列就能分清：\n")
        sb.append("     · game_write / game_patch / game_create / game_reload → **主线**（你自己）\n")
        sb.append("     · 子任务写入 / 子任务补丁 / 子任务重载 → **你派出去的子任务**（同一条 AI 的另一条执行线）\n")
        sb.append("     · 用户编辑 → 用户在 App 里手改保存\n")
        sb.append("   auto-reload 是**结果**不是原因：它是上面某一次写入触发的自动热重载。\n\n")
        if (list.any { it.source.startsWith("子任务") }) {
            sb.append("🔴 这份流水里有**子任务**的写入 —— 如果你看到「预览自己刷新 / 跳回主菜单」，\n")
            sb.append("   就是它干的：子任务在改文件 → 触发重载 → 把主线的预览冲掉了。\n")
            sb.append("   这不是「有外部进程在动你的文件」，是你自己的子任务。\n\n")
        }
        for (e in list) {
            sb.append(fmt.format(Date(e.at)))
                .append("  ")
                .append(e.source.padEnd(14))
                .append(e.path)
            if (e.bytes > 0) sb.append("  ").append(e.bytes).append("B")
            if (e.note.isNotBlank()) sb.append("  · ").append(e.note)
            sb.append('\n')
        }
        return sb.toString()
    }
}
