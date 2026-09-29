package com.mcp.h5engine

/**
 * 全局 Token 统计。
 *
 * 为什么要它：用户用免费档/小模型时，最常问的就是「这一轮到底烧了多少 token、怎么突然就超限了」。
 * 服务商在每次响应里都会带 usage（prompt_tokens / completion_tokens），以前直接丢了；
 * 这里累加起来，跑完一轮直接显示，方便判断「是历史太长还是工具定义太大」。
 *
 * 累计口径：**从 App 启动起累计**（不随单轮清零），用户清空对话时归零。
 */
object TokenStats {

    @Volatile
    var inTokens: Long = 0L
        private set

    @Volatile
    var outTokens: Long = 0L
        private set

    /** 实际发出的请求次数（重试的分开算，能看出重试有多费） */
    @Volatile
    var calls: Int = 0
        private set

    fun add(input: Int, output: Int) {
        if (input > 0) inTokens += input
        if (output > 0) outTokens += output
        calls += 1
    }

    fun reset() {
        inTokens = 0L
        outTokens = 0L
        calls = 0
    }

    /** 一行摘要：给「思考面板」和结束行用 */
    fun summary(): String =
        "累计 ${inTokens + outTokens} tok（入 $inTokens / 出 $outTokens · $calls 次）"
}
