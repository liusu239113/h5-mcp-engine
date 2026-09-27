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
            vision = visionFor(p, m)
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
            vision = visionFor(p, model)
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