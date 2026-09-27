package com.mcp.h5engine

import android.content.Context

/**
 * API Key / 模型 / 参数的本地存储。
 * 每家厂商的 Key 分开存，互不覆盖。
 *
 * 安全提示：这里用普通 SharedPreferences，仅存在本机应用私有目录。
 * 想更硬可以换成 androidx.security 的 EncryptedSharedPreferences（Keystore 加密）。
 * 千万不要把 Key 提交到 git。
 */
class AiConfigStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("ai_cfg", Context.MODE_PRIVATE)

    var providerId: String
        get() = sp.getString("provider", "deepseek") ?: "deepseek"
        set(v) { sp.edit().putString("provider", v).apply() }

    var temperature: Double
        get() = (sp.getString("temp", "0.4") ?: "0.4").toDoubleOrNull() ?: 0.4
        set(v) { sp.edit().putString("temp", v.toString()).apply() }

    /** 一次任务里最多让 AI 调多少次工具（防止跑飞） */
    var maxSteps: Int
        get() = sp.getInt("steps", 40)
        set(v) { sp.edit().putInt("steps", v.coerceIn(1, 200)).apply() }

    /** 模型不支持视觉时：截图存盘并把路径告诉它，而不是硬塞图片 */
    var visionFallback: Boolean
        get() = sp.getBoolean("vision_fallback", true)
        set(v) { sp.edit().putBoolean("vision_fallback", v).apply() }

    var skillId: String
        get() = sp.getString("skill", "general") ?: "general"
        set(v) { sp.edit().putString("skill", v).apply() }

    fun keyOf(id: String): String = sp.getString("key_$id", "") ?: ""
    fun setKey(id: String, key: String) {
        sp.edit().putString("key_$id", key.trim()).apply()
    }
    fun clearKey(id: String) {
        sp.edit().remove("key_$id").apply()
    }

    fun modelOf(id: String): String =
        sp.getString("model_$id", AiProviders.byId(id).models.first().name)
            ?: AiProviders.byId(id).models.first().name

    fun setModel(id: String, model: String) {
        sp.edit().putString("model_$id", model.trim()).apply()
    }

    fun active(): ProviderConfig {
        val p = AiProviders.byId(providerId)
        val m = modelOf(p.id)
        return ProviderConfig(
            provider = p,
            apiKey = keyOf(p.id),
            model = m,
            temperature = temperature,
            maxSteps = maxSteps,
            vision = AiProviders.supportsVision(p, m)
        )
    }

    companion object {
        /** UI 和日志里只显示这个，别打印明文 Key */
        fun mask(k: String): String = when {
            k.isBlank() -> "未填 Key"
            k.length <= 8 -> k.take(2) + "***"
            else -> k.take(5) + "…" + k.takeLast(4)
        }
    }
}