package com.mcp.h5engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 智能体体系 —— 对齐 OpenCode 的 agent / permission 模型。
 *
 * 改造前的状态：只有一个「默认助手」+ 三档全局权限（只读 / 工作区 / 完全访问）。
 * 用户要的是 OpenCode 那套「不同的活交给不同的智能体」：
 *   · 构建（默认）：动手干活，全套工具；
 *   · 计划：只做调查、只出方案，写文件 / 跑命令 / 操作设备一律拒绝；
 *   · 自定义：比如「只管文案」「只跑测试」，各带模型 / 追加提示词 / 权限。
 *
 * 权限沿用 OpenCode 的规则表语义：`{action, resource, effect}`，
 * 按顺序匹配、**最后命中的生效**；没有任何命中时落回全局三档权限。
 */
data class PermRule(
    /** 工具名；支持通配（`*` 任意、`shell_*` 前缀匹配） */
    val action: String,
    /** 预留：文件路径等资源；目前恒为 "*" */
    val resource: String = "*",
    /** allow / ask / deny */
    val effect: String = "ask"
) {
    fun toJson(): JSONObject = JSONObject().put("a", action).put("r", resource).put("e", effect)

    companion object {
        fun from(j: JSONObject): PermRule = PermRule(
            action = j.optString("a", "*").ifBlank { "*" },
            resource = j.optString("r", "*").ifBlank { "*" },
            effect = j.optString("e", "ask").let { if (it in EFFECTS) it else "ask" }
        )

        val EFFECTS = listOf("allow", "ask", "deny")
    }
}

/**
 * 一个智能体。
 *
 * [mode]：primary = 出现在切换列表里；subagent = 只供内部调用（预留）。
 * [model] 空串 = 跟随全局模型；非空时只覆盖「模型名」，厂商 / Key 仍用当前配置的。
 * [tools] 空列表 = 不额外限制（用技能的全量工具）；非空 = 与技能白名单取交集。
 * [rules] 权限规则表，语义见 [PermissionEngine]。
 */
data class AgentSpec(
    val id: String,
    val name: String,
    val description: String = "",
    val mode: String = "primary",
    val hidden: Boolean = false,
    val model: String = "",
    val system: String = "",
    val tools: List<String> = emptyList(),
    val rules: List<PermRule> = emptyList(),
    /** 内置智能体不可编辑 / 不可删（构建 / 计划） */
    val builtin: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("desc", description)
        put("mode", mode)
        put("hidden", hidden)
        put("model", model)
        put("system", system)
        put("tools", JSONArray(tools as Collection<String>))
        put("rules", JSONArray(rules.map { it.toJson() } as Collection<JSONObject>))
    }

    companion object {
        fun from(j: JSONObject): AgentSpec = AgentSpec(
            id = j.optString("id", "").ifBlank { "custom-" + System.currentTimeMillis() },
            name = j.optString("name", "智能体").ifBlank { "智能体" },
            description = j.optString("desc", ""),
            mode = if (j.optString("mode", "primary") == "subagent") "subagent" else "primary",
            hidden = j.optBoolean("hidden", false),
            model = j.optString("model", ""),
            system = j.optString("system", ""),
            tools = j.optJSONArray("tools")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i, "").takeIf { it.isNotBlank() }
                }
            } ?: emptyList(),
            rules = j.optJSONArray("rules")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { PermRule.from(it) }
                }
            } ?: emptyList()
        )
    }
}

/** OpenCode 式权限判定：通配匹配 + 最后命中生效 */
object PermissionEngine {

    /** 通配支持：`*`；`前缀*`（前缀匹配）；`*后缀`（后缀匹配） */
    fun matches(pattern: String, value: String): Boolean {
        if (pattern == "*") return true
        if (pattern.endsWith("*")) return value.startsWith(pattern.dropLast(1))
        if (pattern.startsWith("*")) return value.endsWith(pattern.drop(1))
        return pattern == value
    }

    /**
     * 规则表求值。[resource] 传 "$"（暂不使用）时与 "*" 等价。
     * @return allow / ask / deny；null = 一条都没命中（交给全局权限模式）
     */
    fun decide(action: String, resource: String, rules: List<PermRule>): String? {
        var hit: String? = null
        for (r in rules) {
            if (matches(r.action, action) && (r.resource == "*" || matches(r.resource, resource))) {
                hit = r.effect
            }
        }
        return hit
    }
}

/**
 * 内置智能体 + 自定义智能体的存取。
 *
 * 内置的「构建 / 计划」不存在 SharedPreferences 里、也不可删 ——
 * 即使用户把存储写坏了，至少有一个能干活的和一个只读的兜底。
 */
object AgentStore {

    /**
     * 写 / 执行 / 设备操作类工具清单。
     * 计划模式默认全部拒绝；自建智能体勾「只读」时一键套用同一条清单。
     */
    val DESTRUCTIVE: List<String> = listOf(
        "game_create", "game_write", "game_patch",
        "file_op", "workspace",
        "workflow_save", "workflow_run",
        "memory_save", "memory_forget",
        "shell_run", "git_op", "localserver", "toolpkg",
        "js_eval", "js_sandbox",
        "game_launch", "game_reload", "game_playtest",
        "tap", "swipe", "type_text",
        "maker_auth", "maker_project"
    )

    val BUILD = AgentSpec(
        id = "build",
        name = "构建",
        description = "默认智能体：读写文件、跑命令、交付，全套工具。",
        builtin = true
    )

    val PLAN = AgentSpec(
        id = "plan",
        name = "计划",
        description = "只读：只做调查、只出方案。改文件 / 跑命令 / 操作设备会被直接拒绝。",
        builtin = true,
        rules = DESTRUCTIVE.map { PermRule(it, "*", "deny") }
    )

    fun defaults(): List<AgentSpec> = listOf(BUILD, PLAN)

    /** 内置 + 自定义（自定义里与内置同 id 的会被丢弃，防止用户改坏兜底） */
    fun all(ctx: Context): List<AgentSpec> {
        val builtin = defaults()
        val ids = builtin.map { it.id }.toSet()
        val custom = runCatching {
            val raw = AiConfigStore(ctx).agentsJson
            if (raw.isBlank()) emptyList()
            else {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { AgentSpec.from(it) } }
            }
        }.getOrDefault(emptyList()).filter { it.id !in ids }
        return builtin + custom
    }

    fun saveCustom(ctx: Context, list: List<AgentSpec>) {
        val builtin = defaults().map { it.id }.toSet()
        val arr = JSONArray()
        list.filter { it.id !in builtin }.forEach { arr.put(it.toJson()) }
        AiConfigStore(ctx).agentsJson = arr.toString()
    }

    fun upsert(ctx: Context, a: AgentSpec) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == a.id }
        if (i >= 0) list[i] = a else list += a
        saveCustom(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        saveCustom(ctx, all(ctx).filterNot { it.id == id })
        if (AiConfigStore(ctx).agentId == id) setCurrent(ctx, BUILD.id)
    }

    fun current(ctx: Context): AgentSpec {
        val id = AiConfigStore(ctx).agentId
        return all(ctx).firstOrNull { it.id == id } ?: BUILD
    }

    fun setCurrent(ctx: Context, id: String) {
        AiConfigStore(ctx).agentId = id
    }

    fun newId(ctx: Context): String {
        val ids = all(ctx).map { it.id }.toSet()
        var n = 1
        while (("custom-" + n) in ids) n++
        return "custom-" + n
    }

    /**
     * 注入系统提示的整块：自定义追加设定 + 计划模式的只读约束。
     *
     * 计划模式的提醒**必须硬写**：光靠权限拒绝虽然安全，但模型会反复重试被拒的工具、
     * 白白烧轮数（用户看到的就是「它在原地打转」）。先把边界说清楚，拒绝才是兜底。
     */
    fun promptBlock(a: AgentSpec): String {
        val sb = StringBuilder()
        if (a.system.isNotBlank()) {
            sb.append("\n\n【本智能体的追加设定】\n").append(a.system.trim())
        }
        if (a.id == "plan") {
            sb.append(
                """

【计划模式 · 只读 —— 优先于「收尾纪律」里一切让你动手的部分】
你现在是「计划」智能体：只做调查和方案，不改任何东西。
· 写文件 / 删文件 / 跑命令 / 操作设备 / 云端写接口的工具调用会被系统**直接拒绝** ——
  别试，试了也是白试，只会浪费轮数。
· 用户让你「直接改」时不要改：告诉他切到「构建」智能体再执行。
· 仍然可以：读文件、搜代码、截图看画面 —— 方案必须建立在事实上，每条建议都要有依据。
· 交付物 = 一份能直接照着执行的方案：改哪些文件、每处怎么改、有什么风险、
  哪些点需要用户拍板。用 finish 交出来，把「未验证」如实写清楚。
"""
            )
        }
        return sb.toString()
    }
}
