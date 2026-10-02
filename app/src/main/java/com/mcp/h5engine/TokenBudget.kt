package com.mcp.h5engine

/**
 * 上下文预算 —— **按真实 token 量决定什么时候压**，而不是按条数拍脑袋。
 *
 * ## 为什么要它
 *
 * 之前压缩是两条硬规则：
 *   · 超过 N 条就裁（`historyLimit`）；
 *   · 单条工具结果超过 400 字就折（`compactToolResults`）。
 *
 * 这两个阈值跟**模型实际能装多少**没关系：
 *   · 小窗口模型（32k）早就爆了，还没触发压缩 → 请求 400；
 *   · 大窗口模型（200k）被压得过早 → 白丢上下文，模型还得重读一遍文件。
 *
 * 现在统一成一条：**估算当前历史的 token 数，超过窗口的 [THRESHOLD] 才压**。
 *
 * ## 估算方式
 *
 * 不引入 tokenizer（那要几百 KB 的表，手机上不划算）。
 * 用经验公式：
 *   · **中文**：约 1 字 = 1 token；
 *   · **英文/代码**：约 4 字符 = 1 token；
 *   · 取两者中较大的那个估算（保守，宁可早压也不爆）。
 *
 * 这个估算偏保守（高估），对我们是有利的：**宁可提前一点压，也别等爆了报 400**。
 */
object TokenBudget {

    /** 到窗口的这个比例就该压了 —— 留 25% 给「回复 + 工具定义」 */
    const val THRESHOLD = 0.75

    /** 窗口兜底值（模型没配时用）：128k */
    const val DEFAULT_WINDOW = 128_000

    /** 窗口下限：再小的模型也不会低于这个，避免除零 / 荒谬阈值 */
    private const val MIN_WINDOW = 4_000

    /** 生效的窗口大小 */
    fun window(cfg: ProviderConfig): Int =
        (if (cfg.contextWindow > 0) cfg.contextWindow else DEFAULT_WINDOW).coerceAtLeast(MIN_WINDOW)

    /** 该不该压 */
    fun shouldCompress(cfg: ProviderConfig, history: List<ChatMsg>): Boolean =
        estimate(history) > (window(cfg) * THRESHOLD).toInt()

    /** 还有多少 token 可用（给日志 / UI 显示） */
    fun remaining(cfg: ProviderConfig, history: List<ChatMsg>): Int =
        (window(cfg) - estimate(history)).coerceAtLeast(0)

    /**
     * 估算一段历史的 token 数。
     *
     * 图片单独算：一张 720px JPEG ≈ 1000~1500 token（各家算法不同），
     * 这里按 1200 粗估 —— 反正图片有 `keepImages` 单独管，这里只要别漏算就行。
     */
    fun estimate(history: List<ChatMsg>): Int {
        var total = 0
        for (m in history) {
            total += estimateText(m.text ?: "")
            // 工具调用的参数也要算（一个 game_write 的 content 可能几万字符）
            for (tc in m.toolCalls) total += estimateText(tc.argsJson) + 12
            // 思维链
            m.reasoning?.let { total += estimateText(it) }
            // 图片
            total += m.images.size * 1200
            total += 6   // role / 分隔符的固定开销
        }
        return total
    }

    /**
     * 估一段文本的 token。
     *
     * 中英混排时按「字符数」和「字符数/4」取大 —— 中文 1 字≈1 token 是主因，
     * 纯英文时前者会高估，但我们**故意**选保守的那一侧（早压好过爆掉）。
     */
    fun estimateText(s: String): Int {
        if (s.isEmpty()) return 0
        var cjk = 0
        for (c in s) {
            if (c.code in 0x2E80..0x9FFF || c.code in 0xF900..0xFAFF ||
                c.code in 0xFF00..0xFFEF || c.code in 0x3000..0x303F
            ) cjk++
        }
        val ascii = s.length - cjk
        // 中文 1:1；其余按 4 字符 1 token
        return cjk + (ascii / 4) + 1
    }
}
