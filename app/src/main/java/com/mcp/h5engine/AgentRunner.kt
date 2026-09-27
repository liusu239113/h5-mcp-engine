package com.mcp.h5engine

import java.io.File

/**
 * AI 代理循环：把「用户一句话」变成「多轮工具调用」。
 *
 * 事件协议（界面靠前缀分流，别改前缀）：
 *   STEP:n            第 n 轮开始
 *   THINK:xxx         模型的思维链（deepseek-reasoner 之类）
 *   AI:xxx            模型说出来的一段话
 *   TOOL:[名字] 摘要   一次工具调用
 *   RELOAD            文件被改过，预览该刷新了
 *   INFO:xxx          普通提示，进日志
 *   [失败] xxx         错误卡片
 */
class AgentRunner(
    private val cfg: ProviderConfig,
    private val skill: Skill,
    private val tools: EngineTools,
    private val visionFallback: Boolean,
    private val shotDir: File,
    private val onEvent: (String) -> Unit
) {

    @Volatile
    var cancelled = false
        private set

    private val client = AiClient(cfg)

    /** 停止：置位 + 立刻掐断在飞的 HTTP 请求（否则要等 240 秒读超时） */
    fun cancel() {
        cancelled = true
        runCatching { client.abort() }
    }

    /**
     * 必须从子线程调用。
     * userImages 是用户自己发的图（相册选图 / 当前画面截图），会一起作为多模态输入。
     */
    fun run(
        history: MutableList<ChatMsg>,
        userText: String,
        userImages: List<ByteArray> = emptyList()
    ) {
        val spec = tools.specs(skill.allowTools)
        val names = spec.map { it.getJSONObject("function").getString("name") }

        val sys = buildString {
            append(skill.system)
            append("\n\n可用工具：").append(names.joinToString(", "))
            append("\n\n启动时的引擎状态：\n")
            append(runCatching { tools.call("engine_status", "{}").text }.getOrDefault("(取状态失败)"))
            if (cfg.vision) {
                append("\n\n你已开启视觉：screenshot 工具会把画面作为图片直接给你，")
                append("请务必用它自己确认 UI 是否正常，不要凭空猜坐标。")
            } else {
                append("\n\n注意：当前模型没有启用视觉。screenshot 只会返回文字说明，")
                append("你无法自己看画面；若任务强依赖看 UI，请在回复里提醒用户到设置里开启视觉或换带「看图」的模型。")
            }
        }

        if (history.isNotEmpty() && history[0].role == "system") {
            history[0] = ChatMsg("system", sys)
        } else {
            history.add(0, ChatMsg("system", sys))
        }

        history += ChatMsg("user", userText, userImages)

        val limit = cfg.maxSteps.coerceAtMost(skill.maxSteps)
        var step = 0
        var wrote = false

        while (!cancelled && step < limit) {
            step++
            onEvent("STEP:$step")

            val reply = client.chat(history, spec)
            if (cancelled) return
            if (reply.error != null) {
                if (reply.error == "已取消") return
                onEvent("[失败] 请求失败：\n" + AiClient.friendly(reply.error))
                return
            }

            reply.reasoning?.let { onEvent("THINK:" + it) }

            history += ChatMsg("assistant", reply.text, toolCalls = reply.toolCalls)
            if (!reply.text.isNullOrBlank()) onEvent("AI: ${reply.text}")

            if (reply.toolCalls.isEmpty()) {
                if (wrote) onEvent("RELOAD")
                onEvent("INFO: 完成 · 共跑了 $step 轮")
                return
            }

            for (tc in reply.toolCalls) {
                if (cancelled) {
                    // 用户中途按了停止：**必须**给这个还没执行的调用补一条占位响应。
                    // 否则历史里会留下「assistant 带 tool_calls 却没有对应 tool 消息」的残缺记录，
                    // 而这条记录会被一直带下去 —— 之后每一条消息都会被服务商直接拒收：
                    //   HTTP 400 An assistant message with 'tool_calls' must be followed by tool messages
                    // （用户看到的现象就是「上一秒还能用，突然一直请求失败」）。
                    history += ChatMsg("tool", "（已取消，未执行）", emptyList(), toolCallId = tc.id)
                    continue
                }

                // 工具自己抛异常（参数不合法 / 文件不存在之类）同样要补响应，道理同上
                val res = runCatching { tools.call(tc.name, tc.argsJson) }
                    .getOrElse { t -> EngineTools.ToolResult("工具执行出错：${t.javaClass.simpleName}: ${t.message}") }
                val images = if (cfg.vision) res.images else emptyList()
                var text = res.text
                if (res.images.isNotEmpty() && !cfg.vision && visionFallback) {
                    text += "\n" + saveShot(res.images.first())
                }

                history += ChatMsg("tool", text, images, toolCallId = tc.id)

                var head = res.text.lineSequence().firstOrNull() ?: ""
                if (head.length > 150) head = head.take(150) + "…"
                onEvent("TOOL:[${tc.name}] $head")

                // Maker 会把生成的图/音「materialize」到项目里并返回本地路径：
                // 捞出来丢给 UI，用户就能在对话里直接看到缩略图、点一下预览/试听。
                if (tc.name.startsWith("mcp_")) {
                    assetsIn(res.text).forEach { onEvent("ASSET:$it") }
                    // 云能力没授权（PAT 缺失 / 登录失效）：通知 UI 主动把授权链接递给用户，
                    // 而不是让用户自己去设置页翻（那是上一版的做法，用户明确否掉了）。
                    if (looksLikeAuthMissing(res.text)) onEvent("AUTHREQ:${tc.name}")
                }

                if (tc.name == "game_write" || tc.name == "game_patch" ||
                    tc.name == "game_create" || tc.name == "game_launch"
                ) {
                    wrote = true
                }
            }

            // 写完就通知刷新预览，不用等整轮结束
            if (wrote) onEvent("RELOAD")
        }

        if (cancelled) {
            onEvent("INFO: 已停止")
        } else {
            onEvent("INFO: 到步数上限（$limit），可再发一条让它继续，或在设置里调大上限。")
        }
    }

    /**
     * MCP 返回这种味道的文本 = 云能力还没授权。
     * 判定刻意保守（只认 CLI 自己的原话），免得普通业务报错也触发授权流程。
     */
    private fun looksLikeAuthMissing(t: String): Boolean {
        if (t.isBlank()) return false
        return listOf(
            "PAT not found",
            "pat_required",
            "Maker login is required",
            "Maker CLI login",
            "taptap-maker login",
            "Maker Git 鉴权失败",
            "auth 缺失"
        ).any { t.contains(it) }
    }

    /**
     * 从工具返回的文本里捞出「真实存在的素材文件」。
     *
     * Maker 的 generate_image / text_to_music 等会把产物落到项目里并回传本地路径，
     * 但格式不一定规整（可能带引号、markdown 链接、括号说明），所以这里按后缀兜底匹配，
     * 并且**必须真的存在**才算数 —— 免得把文档里举例的假路径当成生成结果。
     */
    private fun assetsIn(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val re = Regex(
            "[^\\s\"'`（）()\\[\\]{}，。；;<>|*?]+?\\.(?:png|jpg|jpeg|webp|gif|bmp|mp3|wav|ogg|m4a|aac|flac|mp4)",
            RegexOption.IGNORE_CASE
        )
        val out = LinkedHashSet<String>()
        for (m in re.findAll(text)) {
            val p = m.value.trim().trimEnd('.', ',', ';')
            val f = java.io.File(p)
            if (f.isFile && f.length() > 0) out += f.absolutePath
        }
        return out.toList()
    }

    private fun saveShot(img: ByteArray): String = try {
        shotDir.mkdirs()
        val f = File(shotDir, "shot_${System.currentTimeMillis()}.jpg")
        f.writeBytes(img)
        "（截图已保存到 ${f.absolutePath}；当前模型看不了图，换成带「看图」的模型它就能自己检查 UI）"
    } catch (t: Throwable) {
        "（截图保存失败：${t.message}）"
    }
}