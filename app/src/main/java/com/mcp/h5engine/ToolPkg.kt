package com.mcp.h5engine

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 插件体系（D1）。
 *
 * 每个插件 = 一个目录 filesDir/toolpkg/<id>/：
 *   manifest.json  { id, name, version, entry, description }
 *   入口脚本        entry 指向（默认 main.js）
 * 插件运行走 C3 的 JsSandbox（Rhino）：无网络 / 无文件 IO，只有显式注入的 args / id。
 * 市场：从远端索引 JSON 拉插件清单（只返回文本，装不装由调用方 / AI 决定）。
 */
object ToolPkg {

    data class Pkg(
        val id: String,
        val name: String,
        val version: String,
        val entry: String,
        val desc: String,
        val dir: File
    )

    const val DEFAULT_INDEX =
        "https://raw.githubusercontent.com/liusu239113/h5-mcp-engine/main/toolpkg-index.json"

    private fun root(ctx: Context): File = File(ctx.filesDir, "toolpkg").apply { mkdirs() }

    fun dir(ctx: Context, id: String): File = File(root(ctx), id)

    fun list(ctx: Context): List<Pkg> {
        val arr = mutableListOf<Pkg>()
        root(ctx).listFiles()?.filter { it.isDirectory }?.forEach { d ->
            val mf = File(d, "manifest.json")
            if (mf.isFile) {
                runCatching {
                    val o = JSONObject(mf.readText())
                    arr += Pkg(
                        o.optString("id", d.name),
                        o.optString("name", d.name),
                        o.optString("version", "0"),
                        o.optString("entry", "main.js"),
                        o.optString("description", ""),
                        d
                    )
                }
            }
        }
        return arr.sortedBy { it.name }
    }

    fun find(ctx: Context, id: String): Pkg? = list(ctx).firstOrNull { it.id == id }

    fun install(ctx: Context, id: String, manifestJson: String, entryCode: String): Pkg {
        val d = dir(ctx, id)
        d.mkdirs()
        File(d, "manifest.json").writeText(manifestJson)
        val entry = runCatching { JSONObject(manifestJson).optString("entry", "main.js") }
            .getOrDefault("main.js").ifBlank { "main.js" }
        File(d, entry).writeText(entryCode)
        return find(ctx, id) ?: Pkg(id, id, "0", entry, "", d)
    }

    fun remove(ctx: Context, id: String): Boolean {
        val d = dir(ctx, id)
        if (!d.exists()) return false
        return d.deleteRecursively()
    }

    /** 运行插件入口脚本。暴露 pkg.args / pkg.id 给沙箱。 */
    fun run(ctx: Context, id: String, args: String, timeoutMs: Long = 3000): String {
        val p = find(ctx, id) ?: return "插件不存在: $id"
        val f = File(p.dir, p.entry)
        if (!f.isFile) return "插件入口缺失: ${p.entry}"
        val code = runCatching { f.readText() }.getOrElse { return "读取插件失败: ${it.message}" }
        return JsSandbox.eval(code, mapOf("args" to args, "id" to p.id), timeoutMs)
    }

    /** 拉远端插件市场索引（原样返回 JSON 文本，交给 AI 判断）。 */
    fun market(indexUrl: String, timeoutMs: Long = 8000): String {
        val u = indexUrl.ifBlank { DEFAULT_INDEX }
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val body = client.newCall(Request.Builder().url(u).build()).execute().use { r ->
            if (!r.isSuccessful) return "市场索引拉取失败: HTTP ${r.code}"
            r.body?.string() ?: ""
        }
        return body.ifBlank { "市场索引为空" }
    }
}