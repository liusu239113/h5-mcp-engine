package com.mcp.h5engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * SAF 工作区 —— **不需要 Shizuku、不需要任何特殊权限**就能读写用户指定的目录。
 *
 * ## 为什么要有这条路（用户点出来的）
 *
 * 之前只做了 Shizuku 一条路，但用户指出 Operit **不开 Shizuku 也能读写文件**。
 * 它走的就是 SAF（Storage Access Framework）：让用户**授权一个目录**，
 * 之后 App 对这个目录就有完整的读写权 —— 系统层面认可的，不需要提权。
 *
 * ## 两条路的分工
 *
 * | 路 | 需要什么 | 能碰哪 |
 * |---|---|---|
 * | SAF 工作区（本文件） | 用户点一下「选目录」 | **只有授权的那棵子树**，但读写完整 |
 * | Shizuku | 装 + 启动 + 授权 Shizuku | 几乎全盘（含 Android/data），但要额外装东西 |
 *
 * 日常「读我的项目 / 改我的文件」用 SAF 就够了，而且零门槛。
 * Shizuku 留给「要看别的 App 私有目录」这种 SAF 够不着的场景。
 *
 * ## 能力
 *
 * · [list] 列目录（名 / 是否目录 / 大小）
 * · [readText] 读文本
 * · [writeText] 写文本（不存在就创建）
 * · [mkdir] 建目录
 * · [delete] 删（目录递归）
 *
 * ⚠️ 所有操作都走 `DocumentsContract`，**不能**当普通 File 用 ——
 * SAF 的 URI 不是文件系统路径。
 */
object SafWorkspace {

    /** 根 URI（用户授权的那棵子树的入口）；没设置返回 null */
    fun root(ctx: Context): Uri? {
        val s = AiConfigStore(ctx).workspaceUri
        if (s.isBlank()) return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    fun isSet(ctx: Context): Boolean = root(ctx) != null

    /** 拿一个好看的显示名（给 UI 用） */
    fun displayName(ctx: Context, uri: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifBlank { "（根目录）" }
    }.getOrDefault("（未知）")

    /**
     * 列目录。
     *
     * @param rel 相对授权根的子路径，如 "h5游戏项目/大运卡车"；空串 = 根
     * @return 每一项 (名字, 是否目录, 大小)；读不了返回 null
     */
    fun list(ctx: Context, rel: String = ""): List<Triple<String, Boolean, Long>>? {
        val rootUri = root(ctx) ?: return null
        return runCatching {
            val childrenUri = if (rel.isBlank()) {
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    rootUri, DocumentsContract.getTreeDocumentId(rootUri)
                )
            } else {
                val dir = find(ctx, rel) ?: return null
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    rootUri, DocumentsContract.getDocumentId(dir)
                )
            }
            val out = mutableListOf<Triple<String, Boolean, Long>>()
            ctx.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val mime = c.getString(1) ?: ""
                    val size = if (c.isNull(2)) 0L else c.getLong(2)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    out += Triple(name, isDir, size)
                }
            }
            out.sortedWith(compareByDescending<Triple<String, Boolean, Long>> { it.second }
                .thenBy { it.first.lowercase() })
        }.getOrNull()
    }

    /** 按相对路径找节点 URI（逐级下钻，因为 SAF 不给路径查询） */
    fun find(ctx: Context, rel: String): Uri? {
        val rootUri = root(ctx) ?: return null
        if (rel.isBlank()) return rootUri
        var curId = DocumentsContract.getTreeDocumentId(rootUri)
        for (seg in rel.split('/').filter { it.isNotBlank() }) {
            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, curId)
            var found: String? = null
            runCatching {
                ctx.contentResolver.query(
                    childUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(1) == seg) { found = c.getString(0); break }
                    }
                }
            }
            curId = found ?: return null
        }
        return DocumentsContract.buildDocumentUriUsingTree(rootUri, curId)
    }

    /** 读文本（最多 [maxBytes]） */
    fun readText(ctx: Context, rel: String, maxBytes: Int = 200_000): String? {
        val uri = find(ctx, rel) ?: return null
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(maxBytes)
                var off = 0
                while (off < maxBytes) {
                    val n = ins.read(buf, off, maxBytes - off)
                    if (n <= 0) break
                    off += n
                }
                String(buf, 0, off, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /**
     * 写文本（不存在就在 [parentRel] 下创建）。
     *
     * SAF 的 `openOutputStream` 支持 "wt" 模式（截断重写），否则会追加。
     */
    fun writeText(ctx: Context, rel: String, content: String): Boolean {
        val rootUri = root(ctx) ?: return false
        return runCatching {
            val name = rel.substringAfterLast('/')
            val parentRel = rel.substringBeforeLast('/', "")
            val existing = find(ctx, rel)
            val target = existing ?: run {
                val parent = find(ctx, parentRel) ?: rootUri
                DocumentsContract.createDocument(
                    ctx.contentResolver, parent, "text/plain", name
                ) ?: return false
            }
            ctx.contentResolver.openOutputStream(target, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            true
        }.getOrDefault(false)
    }

    /** 建目录 */
    fun mkdir(ctx: Context, rel: String): Boolean {
        val rootUri = root(ctx) ?: return false
        return runCatching {
            val name = rel.substringAfterLast('/')
            val parentRel = rel.substringBeforeLast('/', "")
            val parent = find(ctx, parentRel) ?: rootUri
            DocumentsContract.createDocument(
                ctx.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name
            ) != null
        }.getOrDefault(false)
    }

    /** 删除（目录递归删） */
    fun delete(ctx: Context, rel: String): Boolean {
        val uri = find(ctx, rel) ?: return false
        return runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
            .getOrDefault(false)
    }

    /**
     * 把 SAF 工作区里的文件**拷进**引擎的工程目录。
     *
     * 用途：用户把项目放在 /sdcard/A代码库 下，想在引擎里跑 ——
     * 引擎只能跑自己沙箱里的东西，所以得先搬进来。
     *
     * @param rel        SAF 里的相对路径（可以是目录）
     * @param destParent 目标父目录
     * @return 成功拷贝的文件数
     */
    fun copyInto(ctx: Context, rel: String, destParent: File): Int {
        val uri = find(ctx, rel) ?: return 0
        return copyNode(ctx, uri, destParent)
    }

    private fun copyNode(ctx: Context, uri: Uri, destParent: File): Int {
        val name = runCatching { queryName(ctx, uri) }.getOrNull() ?: return 0
        val isDir = runCatching { queryIsDir(ctx, uri) }.getOrDefault(false)
        return if (isDir) {
            val sub = File(destParent, name).apply { mkdirs() }
            var n = 0
            runCatching {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    uri, DocumentsContract.getDocumentId(uri)
                )
                ctx.contentResolver.query(
                    children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(
                            uri, c.getString(0)
                        )
                        n += copyNode(ctx, childUri, sub)
                    }
                }
            }
            n
        } else {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    File(destParent, name).outputStream().use { ins.copyTo(it, 1 shl 16) }
                }
                1
            }.getOrDefault(0)
        }
    }

    private fun queryName(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun queryIsDir(ctx: Context, uri: Uri): Boolean =
        ctx.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null
        )?.use {
            it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
        } ?: false
}
