package com.mcp.h5engine

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一个会话 = 一条独立上下文。切会话就像 TapTap Maker 那样互不干扰 */
data class ChatSession(
    val id: String,
    var title: String,
    var msgs: MutableList<ChatMsg> = mutableListOf(),
    var updated: Long = System.currentTimeMillis()
)

/** 用户附件（文档）：rel 是它落在项目里的相对路径，text 是能提取出来的正文 */
data class DocAttach(
    val name: String,
    val rel: String,
    val text: String
)

/**
 * 通用附件：素材 / 文档 / 技能 / 代码，四类共用一套模型。
 * kind ∈ image / audio / video / doc / skill / code
 * rel = 落在工程根下的相对路径（_uploads/media/… 、_uploads/doc/… 、_skills/… ）
 * text = 能提出来的正文（纯文本/文档才有，二进制为空）
 */
data class Attach(
    val name: String,
    val rel: String,
    val kind: String,
    val text: String = ""
)

/**
 * 会话落盘：app 私有目录 sessions.json。
 * 只存文字（图片不落盘，否则文件会迅速膨胀到几百 MB）。
 */
class SessionStore(private val ctx: Context, val proj: String = "") {

    /** 一个项目一份会话库 —— 新开项目就是全新上下文，绝不和历史串味 */
    private val f = File(ctx.filesDir, "sessions_" + proj.ifBlank { "default" } + ".json")

    /** 旧版只有全局 sessions.json：首次按项目读取时接管过来，老历史不丢 */
    fun migrateLegacy() {
        val old = File(ctx.filesDir, "sessions.json")
        if (!f.exists() && old.exists()) runCatching { old.renameTo(f) }
    }

    fun load(): MutableList<ChatSession> {
        // 先读主文件；读不出来（写到一半被杀进程 / 文件残缺）就退到 .bak。
        // 绝不能因为一个坏 JSON 让用户「所有对话都没了」。
        return readFrom(f) ?: readFrom(File(f.parentFile, f.name + ".bak")) ?: mutableListOf()
    }

    /** 读一个会话文件；不存在或解析失败都返回 null（由 load 决定怎么兜底） */
    private fun readFrom(src: File): MutableList<ChatSession>? {
        if (!src.exists()) return null
        return runCatching {
            val arr = JSONArray(src.readText())
            val out = mutableListOf<ChatSession>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val msgs = mutableListOf<ChatMsg>()
                val ma = o.optJSONArray("msgs") ?: JSONArray()
                for (j in 0 until ma.length()) {
                    val m = ma.optJSONObject(j) ?: continue
                    // tool_calls / tool_call_id 必须一起回来：否则 assistant 的调用记录丢了、
                    // tool 消息成了孤儿，下一轮请求会被服务端 400 掉（整个会话再也发不出去）。
                    val tcs = mutableListOf<ToolCall>()
                    m.optJSONArray("toolCalls")?.let { ta ->
                        for (k in 0 until ta.length()) {
                            val t = ta.optJSONObject(k) ?: continue
                            val id = t.optString("id")
                            if (id.isBlank()) continue
                            tcs += ToolCall(id, t.optString("name"), t.optString("args", "{}"))
                        }
                    }
                    msgs += ChatMsg(
                        m.optString("role", "user"),
                        m.optString("text").ifBlank { null },
                        emptyList(),
                        tcs,
                        m.optString("toolCallId").ifBlank { null }
                    )
                }
                out += ChatSession(
                    o.optString("id", "s$i"),
                    o.optString("title", "新对话"),
                    msgs,
                    o.optLong("updated", 0L)
                )
            }
            // 最近用过的排前面：冷启动 / 切项目回来优先看到「上次那个」
            out.sortedByDescending { it.updated }.toMutableList()
        }.getOrNull()
    }

    fun save(list: List<ChatSession>) {
        runCatching {
            val arr = JSONArray()
            for (s in list) {
                val ma = JSONArray()
                for (m in s.msgs.takeLast(300)) {
                    // 只跳过「既没文字、也没工具调用」的空壳。
                    // 注意别把带 tool_calls 的 assistant 消息漏掉 —— 那正是上次 400 的元凶。
                    if (m.text.isNullOrBlank() && m.toolCalls.isEmpty()) continue
                    val jo = JSONObject().put("role", m.role).put("text", m.text ?: "")
                    m.toolCallId?.let { jo.put("toolCallId", it) }
                    if (m.toolCalls.isNotEmpty()) {
                        val tcs = JSONArray()
                        for (t in m.toolCalls) {
                            tcs.put(
                                JSONObject().put("id", t.id).put("name", t.name)
                                    .put("args", t.argsJson.take(4000))
                            )
                        }
                        jo.put("toolCalls", tcs)
                    }
                    ma.put(jo)
                }
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("updated", s.updated)
                        .put("msgs", ma)
                )
            }
            // 原子写：先写 .tmp 再 rename，并留一份 .bak。
            // 直接 writeText 的话，写到一半被系统杀进程 → JSON 残缺 →
            // 下次启动解析失败 → 用户看到「所有对话都没了」（真实发生过的数据丢失）。
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (f.exists()) runCatching {
                f.copyTo(File(f.parentFile, f.name + ".bak"), overwrite = true)
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }
}