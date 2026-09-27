package com.mcp.h5engine

import android.content.Context

/**
 * API Key / 模型 / 参数 / 外观 的本地存储。
 * 每家厂商的 Key 分开存，互不覆盖。
 *
 * 安全提示：这里用普通 SharedPreferences，仅存在本机应用私有目录。
 * 想更硬可以换成 androidx.security 的 EncryptedSharedPreferences。
 * 千万不要把 Key 提交到 git。
 */
class AiConfigStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("ai_cfg", Context.MODE_PRIVATE)

    // ==================== 外观 ====================

    /** light / dark / auto */
    var themeMode: String
        get() = sp.getString("theme", "light") ?: "light"
        set(v) {
            sp.edit().putString("theme", v).apply()
        }

    // ==================== 项目状态（跨重启 / 跨覆盖安装保留） ====================

    /**
     * 上次打开的项目 id。
     * 必须落盘：APK 覆盖安装会强杀进程，冷启动若不恢复就会退回硬编码的 "demo"，
     * 用户看到的现象是「更新一次，我的项目就变回 demo 了」。
     */
    var lastGame: String
        get() = sp.getString("last_game", "demo") ?: "demo"
        set(v) {
            sp.edit().putString("last_game", v).apply()
        }

    /** 上次的项目根目录绝对路径，用于权限判定抖动时沿用原目录，避免项目「凭空消失」 */
    var lastRoot: String
        get() = sp.getString("last_root", "") ?: ""
        set(v) {
            sp.edit().putString("last_root", v).apply()
        }

    // ==================== 模型 ====================

    var providerId: String
        get() = sp.getString("provider", "deepseek") ?: "deepseek"
        set(v) {
            sp.edit().putString("provider", v).apply()
        }

    var temperature: Double
        get() = (sp.getString("temp", "0.4") ?: "0.4").toDoubleOrNull() ?: 0.4
        set(v) {
            sp.edit().putString("temp", v.toString()).apply()
        }

    /** 一次任务里最多让 AI 调多少次工具（防止跑飞） */
    var maxSteps: Int
        // 400 起步：做一款完整游戏、反复截图自检时，40 轮根本不够
        // （早期版本默认 40，这里把老装机里已经存下的 40 顺势迁移到 400）
        get() = sp.getInt("steps", 400).let { if (it == 40) 400 else it }
        set(v) {
            sp.edit().putInt("steps", v.coerceIn(1, 2000)).apply()
        }

    /** 模型不支持视觉时：截图存盘并把路径告诉它，而不是硬塞图片 */
    var visionFallback: Boolean
        get() = sp.getBoolean("vision_fallback", true)
        set(v) {
            sp.edit().putBoolean("vision_fallback", v).apply()
        }

    /** 视觉策略：auto（按模型自带能力）/ on（强制开）/ off（强制关） */
    var visionMode: String
        get() = sp.getString("vision_mode", "auto") ?: "auto"
        set(v) {
            sp.edit().putString("vision_mode", v).apply()
        }

    var skillId: String
        get() = sp.getString("skill", "general") ?: "general"
        set(v) {
            sp.edit().putString("skill", v).apply()
        }

    // ==================== 请求体积自适应（适配各家的 TPM / 上下文上限） ====================

    /** 输出上限 max_tokens；0 = 不发送（交服务商决定）。免费档/TPM 小的模型要调小 */
    var maxOutTokens: Int
        get() = sp.getInt("max_out", 0)
        set(v) {
            sp.edit().putInt("max_out", v.coerceIn(0, 65536)).apply()
        }

    /** 历史最多保留多少条消息（system 除外）。工具往返很占 token，默认 40 */
    var historyLimit: Int
        get() = sp.getInt("hist_limit", 40)
        set(v) {
            sp.edit().putInt("hist_limit", v.coerceIn(4, 500)).apply()
        }

    /** 是否把工具定义发给模型（50+ 个工具 JSON 是 token 大头） */
    var sendTools: Boolean
        get() = sp.getBoolean("send_tools", true)
        set(v) {
            sp.edit().putBoolean("send_tools", v).apply()
        }

    /** 遇到 413 / TPM / 上下文超限时自动精简重试 */
    var autoSlim: Boolean
        get() = sp.getBoolean("auto_slim", true)
        set(v) {
            sp.edit().putBoolean("auto_slim", v).apply()
        }

    // ==================== 各家独立配置 ====================

    fun keyOf(id: String): String = sp.getString("key_$id", "") ?: ""

    fun setKey(id: String, key: String) {
        sp.edit().putString("key_$id", key.trim()).apply()
    }

    fun clearKey(id: String) {
        sp.edit().remove("key_$id").apply()
    }

    /** 用户自定义的 Base URL（空串表示用预设） */
    fun baseUrlOf(id: String): String = sp.getString("base_$id", "") ?: ""

    fun setBaseUrl(id: String, url: String) {
        sp.edit().putString("base_$id", url.trim()).apply()
    }

    /** 自定义 Base URL，没有就用预设 */
    fun effectiveBaseUrl(p: Provider): String =
        baseUrlOf(p.id).ifBlank { p.baseUrl }

    fun modelOf(id: String): String {
        val p = AiProviders.byId(id)
        return sp.getString("model_$id", p.models.first().name) ?: p.models.first().name
    }

    fun setModel(id: String, model: String) {
        sp.edit().putString("model_$id", model.trim()).apply()
    }

    // ==================== 汇总 ====================

    /** 某个厂商 + 某个模型是否走多模态（用户策略优先） */
    fun visionFor(p: Provider, model: String): Boolean = when (visionMode) {
        "on" -> true
        "off" -> false
        else -> AiProviders.supportsVision(p, model) || AiProviders.guessVision(model)
    }

    fun active(): ProviderConfig {
        val p = AiProviders.byId(providerId)
        val m = modelOf(p.id)
        return ProviderConfig(
            provider = p,
            baseUrl = effectiveBaseUrl(p),
            apiKey = keyOf(p.id),
            model = m,
            modelLabel = AiProviders.modelLabel(p, m),
            temperature = temperature,
            maxSteps = maxSteps,
            vision = visionFor(p, m),
            maxOutTokens = maxOutTokens,
            historyLimit = historyLimit,
            sendTools = sendTools,
            autoSlim = autoSlim
        )
    }

    /** 给任意厂商 + 模型组一份配置（测试连通时用，不落盘） */
    fun candidate(p: Provider, model: String, key: String, url: String): ProviderConfig =
        ProviderConfig(
            provider = p,
            baseUrl = url.ifBlank { p.baseUrl },
            apiKey = key.trim(),
            model = model,
            modelLabel = AiProviders.modelLabel(p, model),
            temperature = temperature,
            maxSteps = maxSteps,
            vision = visionFor(p, model),
            maxOutTokens = maxOutTokens,
            historyLimit = historyLimit,
            sendTools = sendTools,
            autoSlim = autoSlim
        )

    companion object {
        /** UI 和日志里只显示这个，别打印明文 Key */
        fun mask(k: String): String = when {
            k.isBlank() -> "未填 Key"
            k.length <= 8 -> k.take(2) + "***"
            else -> k.take(5) + "…" + k.takeLast(4)
        }
    }
}