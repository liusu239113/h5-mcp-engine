package com.mcp.h5engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 长期记忆（C1）。
 * 存 filesDir/memory/memory.json（私有目录，不进工程、不会被 extract 清）。
 * 每轮把与用户输入最相关的几条注入系统提示；AI 也能 memory_save / memory_search。
 * 检索：分词命中计分 + 新近度加权，离线零依赖。
 */
object MemoryStore {

    data class Item(val id: String, val text: String, val tags: List<String>, val at: Long)

    private fun file(ctx: Context): File =
        File(File(ctx.filesDir, "memory"), "memory.json").apply { parentFile?.mkdirs() }

    @Volatile
    private var cache: MutableList<Item>? = null

    @Synchronized
    private fun load(ctx: Context): MutableList<Item> {
        cache?.let { return it }
        val list = mutableListOf<Item>()
        runCatching {
            val f = file(ctx)
            if (f.isFile) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val tags = o.optJSONArray("tags")?.let { ta ->
                        (0 until ta.length()).map { ta.optString(it) }.filter { it.isNotBlank() }
                    } ?: emptyList()
                    list += Item(o.optString("id"), o.optString("text"), tags, o.optLong("at"))
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
            load(ctx).forEach { it0 ->
                arr.put(
                    JSONObject()
                        .put("id", it0.id).put("text", it0.text)
                        .put("tags", JSONArray(it0.tags)).put("at", it0.at)
                )
            }
            file(ctx).writeText(arr.toString())
        }
    }

    /** 存一条。text 相同（忽略大小写）算更新，不重复堆。上限 500 条。 */
    @Synchronized
    fun save(ctx: Context, text: String, tags: List<String> = emptyList()): Item? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val list = load(ctx)
        val same = list.indexOfFirst { it.text.equals(t, ignoreCase = true) }
        val item = Item(
            if (same >= 0) list[same].id else UUID.randomUUID().toString().take(8),
            t,
            tags.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            System.currentTimeMillis()
        )
        if (same >= 0) list[same] = item else list += item
        while (list.size > 500) list.removeAt(0)
        persist(ctx)
        return item
    }

    @Synchronized
    fun delete(ctx: Context, id: String): Boolean {
        val ok = load(ctx).removeAll { it.id == id }
        if (ok) persist(ctx)
        return ok
    }

    @Synchronized
    fun clear(ctx: Context): Int {
        val n = load(ctx).size
        load(ctx).clear()
        persist(ctx)
        return n
    }

    fun all(ctx: Context): List<Item> = load(ctx).toList()

    /** 中英混排分词：英文/数字按串，汉字按单字 */
    private fun tokens(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in s.lowercase()) {
            if (c.isLetterOrDigit() && c.code < 128) {
                sb.append(c)
            } else {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                if (c.code in 0x4e00..0x9fff) out += c.toString()
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** 相关性检索：命中次数 + 新近度加权 */
    fun search(ctx: Context, query: String, limit: Int = 8): List<Item> {
        val list = load(ctx)
        if (list.isEmpty()) return emptyList()
        val q = tokens(query).distinct()
        if (q.isEmpty()) return list.takeLast(limit).reversed()
        val now = System.currentTimeMillis()
        return list.map { it0 ->
            val hay = (it0.text + " " + it0.tags.joinToString(" ")).lowercase()
            var score = 0.0
            for (t in q) if (hay.contains(t)) score += 1.0
            if (score > 0) score += (1.0 / (1.0 + (now - it0.at) / 86_400_000.0)) * 0.3
            it0 to score
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }
    }

    /** 拼成注入系统提示的一段 */
    fun promptBlock(ctx: Context, query: String, limit: Int = 8): String {
        val hits = search(ctx, query, limit)
        if (hits.isEmpty()) return ""
        return buildString {
            append("\n\n【长期记忆（用户以前说过、跨会话有效，直接当事实用，不用再问）】\n")
            hits.forEach { append("- ").append(it.text) }
        }
    }
}