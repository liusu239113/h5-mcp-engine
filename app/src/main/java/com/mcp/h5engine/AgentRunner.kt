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
                if (cancelled) return

                val res = tools.call(tc.name, tc.argsJson)
                val images = if (cfg.vision) res.images else emptyList()
                var text = res.text
                if (res.images.isNotEmpty() && !cfg.vision && visionFallback) {
                    text += "\n" + saveShot(res.images.first())
                }

                history += ChatMsg("tool", text, images, toolCallId = tc.id)

                var head = res.text.lineSequence().firstOrNull() ?: ""
                if (head.length > 150) head = head.take(150) + "…"
                onEvent("TOOL:[${tc.name}] $head")

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

    private fun saveShot(img: ByteArray): String = try {
        shotDir.mkdirs()
        val f = File(shotDir, "shot_${System.currentTimeMillis()}.jpg")
        f.writeBytes(img)
        "（截图已保存到 ${f.absolutePath}；当前模型看不了图，换成带「看图」的模型它就能自己检查 UI）"
    } catch (t: Throwable) {
        "（截图保存失败：${t.message}）"
    }
}