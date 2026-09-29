package com.mcp.h5engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 工作流引擎（C2）。
 * 存 filesDir/workflow/workflows.json（私有目录，不进工程、不会被 extract 清）。
 * 一个工作流 = 一串节点，按 next 串起来顺序执行；节点类型：
 *   tool   —— 调一个引擎工具（params 即工具参数）
 *   llm    —— 让模型说一段（params.prompt 作输入，由 exec 落地）
 *   delay  —— 等待 params.ms 毫秒
 *   branch —— 由 exec 决定走向（这里按顺序节点处理）
 * 触发器：manual（用户 / AI 手动）、schedule（配合定时唤醒）、event（预留）。
 */
object WorkflowStore {

    data class Node(
        val id: String,
        val type: String,
        val title: String,
        val params: JSONObject,
        val next: String?
    )

    data class Flow(
        val id: String,
        val name: String,
        val trigger: String,
        val enabled: Boolean,
        val nodes: List<Node>
    )

    private fun file(ctx: Context): File =
        File(File(ctx.filesDir, "workflow"), "workflows.json").apply { parentFile?.mkdirs() }

    @Volatile
    private var cache: MutableList<Flow>? = null

    @Synchronized
    private fun load(ctx: Context): MutableList<Flow> {
        cache?.let { return it }
        val list = mutableListOf<Flow>()
        runCatching {
            val f = file(ctx)
            if (f.isFile) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val ns = mutableListOf<Node>()
                    val na = o.optJSONArray("nodes") ?: JSONArray()
                    for (j in 0 until na.length()) {
                        val n = na.optJSONObject(j) ?: continue
                        ns += Node(
                            n.optString("id", "n$j"),
                            n.optString("type", "tool"),
                            n.optString("title", "步骤$j"),
                            n.optJSONObject("params") ?: JSONObject(),
                            n.optString("next").ifBlank { null }
                        )
                    }
                    list += Flow(
                        o.optString("id"),
                        o.optString("name", "未命名"),
                        o.optString("trigger", "manual"),
                        o.optBoolean("enabled", true),
                        ns
                    )
                }
            }
        }
        cache = list
        return list
    }

    @Synchronized
    private fun persist(ctx: Context) {
        runCatching {
            val arr = JSONArray()
            load(ctx).forEach { f ->
                val o = JSONObject()
                    .put("id", f.id).put("name", f.name)
                    .put("trigger", f.trigger).put("enabled", f.enabled)
                val na = JSONArray()
                f.nodes.forEach { n ->
                    na.put(
                        JSONObject()
                            .put("id", n.id).put("type", n.type).put("title", n.title)
                            .put("params", n.params).put("next", n.next ?: "")
                    )
                }
                o.put("nodes", na)
                arr.put(o)
            }
            file(ctx).writeText(arr.toString())
        }
    }

    fun list(ctx: Context): List<Flow> = load(ctx).toList()

    fun find(ctx: Context, id: String): Flow? = load(ctx).firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    @Synchronized
    fun upsert(ctx: Context, flow: Flow): Flow {
        val l = load(ctx)
        val i = l.indexOfFirst { it.id == flow.id }
        if (i >= 0) l[i] = flow else l += flow
        persist(ctx)
        return flow
    }

    @Synchronized
    fun remove(ctx: Context, id: String): Boolean {
        val ok = load(ctx).removeAll { it.id == id }
        if (ok) persist(ctx)
        return ok
    }

    /**
     * 顺序执行。exec 由调用方注入：根据节点返回输出文本。
     * 最多跑 64 步，防环。
     */
    fun run(ctx: Context, id: String, exec: (Node) -> String): String {
        val f = find(ctx, id) ?: return "工作流不存在: $id"
        if (!f.enabled) return "工作流已停用: ${f.name}"
        if (f.nodes.isEmpty()) return "工作流 [${f.name}] 没有步骤"
        val sb = StringBuilder("▶ 工作流 [${f.name}]（${f.nodes.size} 步）\n")
        var cur: Node = f.nodes.first()
        var guard = 0
        while (guard++ < 64) {
            val out = runCatching { exec(cur) }.getOrElse { "ERROR: ${it.message}" }
            sb.append("· ").append(cur.title).append('(').append(cur.type).append(") → ")
                .append(out.take(200)).append('\n')
            val nxt = cur.next ?: break
            cur = f.nodes.firstOrNull { it.id == nxt } ?: break
        }
        return sb.toString().trim()
    }
}
