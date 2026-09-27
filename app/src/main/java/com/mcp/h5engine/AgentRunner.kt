package com.mcp.h5engine

import java.io.File

/**
 * AI 代理循环：把「用户一句话」变成「多轮工具调用」。
 *
 * 关键点是多模态回灌：
 *   screenshot 工具返回的图片会被塞进下一条 tool 消息里，
 *   带视觉的模型就能自己看到画面，从而形成 写代码 → 看画面 → 改 的闭环。
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

    fun cancel() {
        cancelled = true
    }

    /**
     * 必须从子线程调用。
     * userImages 是用户自己发的图（相册选图 / 当前游戏截图），会一起作为多模态输入。
     */
    fun run(
        history: MutableList<ChatMsg>,
        userText: String,
        userImages: List<ByteArray> = emptyList()
    ) {
        val client = AiClient(cfg)
        val spec = tools.specs(skill.allowTools)
        val names = spec.map { it.getJSONObject("function").getString("name") }

        val sys = buildString {
            append(skill.system)
            append("\n\n可用工具：").append(names.joinToString(", "))
            append("\n\n启动时的引擎状态：\n")
            append(runCatching { tools.call("engine_status", "{}").text }.getOrDefault("(取状态失败)"))
            if (!cfg.vision) {
                append("\n\n注意：当前模型没有启用视觉。screenshot 只会返回文字说明，")
                append("你无法自己看画面；若任务强依赖看 UI，请在回复里提醒用户换成带「看图」的模型。")
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

        while (!cancelled && step < limit) {
            step++
            onEvent("⋯ 第 $step 轮 · ${cfg.provider.label}/${cfg.model}")

            val reply = client.chat(history, spec)
            if (cancelled) return
            if (reply.error != null) {
                onEvent("✗ 请求失败：${reply.error}")
                return
            }

            history += ChatMsg("assistant", reply.text, toolCalls = reply.toolCalls)
            if (!reply.text.isNullOrBlank()) onEvent("AI: ${reply.text}")

            if (reply.toolCalls.isEmpty()) {
                onEvent("✓ 结束（共 $step 轮）")
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
                onEvent("  [${tc.name}] $head")
            }
        }

        if (cancelled) {
            onEvent("■ 已停止")
        } else {
            onEvent("⚠ 到步数上限（$limit），先停下。可以再发一条消息让它继续，或在设置里调大上限。")
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