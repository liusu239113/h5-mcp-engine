package com.mcp.h5engine

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具结果缓存 —— 同一轮里重复的读操作直接命中，不再重跑。
 *
 * ## 为什么要它
 *
 * 弱模型很爱「反复读同一份东西」：同一个文件读三遍、同一个目录列两遍、
 * 状态查了又问。每次都要走一遍真实 IO（有的还要起进程、走网络），
 * 一轮下来白等好几秒。
 *
 * ## 只缓存「只读」工具
 *
 * 写操作**绝不能**缓存 —— 同一个 `game_write` 调两次，第二次必须真的执行。
 * 所以只有 [READ_ONLY] 里明确列出的名字才进缓存，其余一律直通。
 *
 * ## 失效策略
 *
 * 一旦有写操作发生（[invalidateAll]），整轮缓存清空。
 * 因为写完之后，之前缓存的「文件内容 / 目录列表」全都可能过期了。
 *
 * ## 作用域 = 一轮
 *
 * 用户要的就是「同一轮里重复读直接命中」，所以 [beginTurn] 会清空上一轮的。
 * 跨轮缓存风险大（用户在别处改了文件我们不知道），不做。
 */
object ToolCache {

    /**
     * 可以缓存的工具名（**只读**）。
     *
     * ⚠️ 加名字前先问一句：同一个参数调两次，结果一定一样吗？
     * 不一样的一律不能加（比如 shell_run —— 它可能跑 `date`、`ps`）。
     */
    private val READ_ONLY: Set<String> = setOf(
        "engine_status",   // 当前游戏 / 屏幕尺寸 / 列表
        "game_list",       // 工程里有哪些游戏
        "game_read",       // 读文件 —— 最常被重复读的就是它
        "code_search",     // 搜代码
        "game_libs",       // 内置框架清单（静态）
        "lib_usage",       // 同上
        "workflow_list",   // 工作流清单
        "service_logs",    // 服务日志
        "ad_guide",        // 广告文档（静态）
        "game_validate",   // 跑一遍查错（纯读）
    )

    private data class Key(val name: String, val argsHash: String)

    private val map = ConcurrentHashMap<Key, EngineTools.ToolResult>()

    /** 统计：命中 / 未命中，跑完一轮可以报给用户看省了多少 */
    @Volatile private var hits = 0
    @Volatile private var misses = 0

    /** 一轮开始：清空（用户要的是「同一轮内」命中） */
    fun beginTurn() {
        map.clear()
        hits = 0
        misses = 0
    }

    /** 有写操作发生 → 之前缓存的读结果全可能过期，清掉 */
    fun invalidateAll() = map.clear()

    fun hitCount(): Int = hits
    fun missCount(): Int = misses

    /** 这个工具能不能缓存 */
    fun cacheable(name: String): Boolean = name in READ_ONLY

    /**
     * 这个工具是不是「只读、可并发」的。
     *
     * 用途：同一轮里模型一次吐好几个只读调用时，可以并行跑（见 AgentRunner）。
     *
     * 判据比 [cacheable] 更严一点 —— 并发要求**没有副作用**，
     * 而 game_validate 会起子进程跑一遍，虽然只读但开销大，放进来容易把机器压满，
     * 所以并发白名单是缓存白名单的子集。
     */
    private val PARALLEL_SAFE: Set<String> = setOf(
        "engine_status", "game_list", "game_read", "code_search",
        "game_libs", "lib_usage", "workflow_list", "service_logs",
    )

    fun parallelSafe(name: String): Boolean = name in PARALLEL_SAFE

    /**
     * 取缓存。命中返回结果，未命中返回 null。
     *
     * @param argsJson 原始参数串 —— 直接哈希它（不用解析，省事且不会因为键序不同漏命中）
     */
    fun get(name: String, argsJson: String): EngineTools.ToolResult? {
        if (!cacheable(name)) return null
        val v = map[Key(name, sha(argsJson))]
        if (v != null) hits++ else misses++
        return v
    }

    /** 存缓存（只读工具才存） */
    fun put(name: String, argsJson: String, result: EngineTools.ToolResult) {
        if (!cacheable(name)) return
        map[Key(name, sha(argsJson))] = result
    }

    private fun sha(s: String): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }.getOrDefault(s.take(64))
}
