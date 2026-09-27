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
class SessionStore(ctx: Context) {

    private val f = File(ctx.filesDir, "sessions.json")

    fun load(): MutableList<ChatSession> {
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<ChatSession>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val msgs = mutableListOf<ChatMsg>()
                val ma = o.optJSONArray("msgs") ?: JSONArray()
                for (j in 0 until ma.length()) {
                    val m = ma.optJSONObject(j) ?: continue
                    msgs += ChatMsg(m.optString("role", "user"), m.optString("text"))
                }
                out += ChatSession(
                    o.optString("id", "s$i"),
                    o.optString("title", "新对话"),
                    msgs,
                    o.optLong("updated", 0L)
                )
            }
            out
        }.getOrDefault(mutableListOf())
    }

    fun save(list: List<ChatSession>) {
        runCatching {
            val arr = JSONArray()
            for (s in list) {
                val ma = JSONArray()
                for (m in s.msgs.takeLast(300)) {
                    if (m.text.isNullOrBlank()) continue
                    ma.put(JSONObject().put("role", m.role).put("text", m.text))
                }
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("updated", s.updated)
                        .put("msgs", ma)
                )
            }
            f.writeText(arr.toString())
        }
    }
}