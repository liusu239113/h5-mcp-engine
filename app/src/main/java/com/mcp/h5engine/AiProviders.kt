package com.mcp.h5engine

/** 三种主流协议：OpenAI 兼容 / Anthropic / Gemini */
enum class Protocol { OPENAI, ANTHROPIC, GEMINI }

data class ModelSpec(
    val name: String,
    val label: String,
    /** 能不能看图 —— 决定截图是否作为图片回灌给模型 */
    val vision: Boolean = false
)

data class Provider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val protocol: Protocol = Protocol.OPENAI,
    val keyHint: String = "sk-...",
    val models: List<ModelSpec>
)

data class ProviderConfig(
    val provider: Provider,
    val apiKey: String,
    val model: String,
    val temperature: Double,
    val maxSteps: Int,
    /** 当前模型是否支持视觉（支持则截图直接喂给模型） */
    val vision: Boolean
)

/**
 * 国内外主流大模型预设。
 * 全部走各家官方端点，用户只需在设置里填自己的 API Key。
 */
object AiProviders {

    val ALL: List<Provider> = listOf(

        /* ==================== 国内 ==================== */

        Provider("deepseek", "DeepSeek 深度求索", "https://api.deepseek.com/v1",
            models = listOf(
                ModelSpec("deepseek-chat", "DeepSeek V3"),
                ModelSpec("deepseek-reasoner", "DeepSeek R1（推理）"))),

        Provider("dashscope", "阿里通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            models = listOf(
                ModelSpec("qwen-max", "Qwen Max"),
                ModelSpec("qwen3-coder-plus", "Qwen3 Coder（写代码强）"),
                ModelSpec("qwen-plus", "Qwen Plus"),
                ModelSpec("qwen-vl-max", "Qwen VL Max（看图）", vision = true))),

        Provider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
            keyHint = "xxxx.xxxx",
            models = listOf(
                ModelSpec("glm-4.5", "GLM-4.5"),
                ModelSpec("glm-4.5v", "GLM-4.5V（看图）", vision = true),
                ModelSpec("glm-4-flash", "GLM-4 Flash（免费）"))),

        Provider("moonshot", "月之暗面 Kimi", "https://api.moonshot.cn/v1",
            models = listOf(
                ModelSpec("kimi-k2-0905-preview", "Kimi K2"),
                ModelSpec("kimi-latest", "Kimi Latest（看图）", vision = true),
                ModelSpec("moonshot-v1-128k", "Moonshot 128K"))),

        Provider("ark", "火山方舟 豆包", "https://ark.cn-beijing.volces.com/api/v3",
            keyHint = "火山 AK",
            models = listOf(
                ModelSpec("doubao-seed-1-6-250615", "Doubao Seed 1.6", vision = true),
                ModelSpec("doubao-1-5-vision-pro-32k", "Doubao Vision Pro（看图）", vision = true))),

        Provider("hunyuan", "腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1",
            models = listOf(
                ModelSpec("hunyuan-turbos-latest", "混元 TurboS"),
                ModelSpec("hunyuan-t1-latest", "混元 T1（推理）"))),

        Provider("qianfan", "百度文心", "https://qianfan.baidubce.com/v2",
            keyHint = "bce-v3/...",
            models = listOf(
                ModelSpec("ernie-4.5-turbo-32k", "文心 4.5 Turbo"),
                ModelSpec("ernie-x1-turbo-32k", "文心 X1 Turbo"))),

        Provider("minimax", "MiniMax", "https://api.minimax.chat/v1",
            models = listOf(
                ModelSpec("MiniMax-Text-01", "MiniMax Text 01"),
                ModelSpec("abab6.5s-chat", "abab6.5s"))),

        Provider("spark", "讯飞星火", "https://spark-api-open.xf-yun.com/v1",
            keyHint = "APIKey:APISecret",
            models = listOf(
                ModelSpec("4.0Ultra", "星火 4.0 Ultra"),
                ModelSpec("generalv3.5", "星火 3.5"))),

        Provider("stepfun", "阶跃星辰", "https://api.stepfun.com/v1",
            models = listOf(
                ModelSpec("step-2-16k", "Step-2"),
                ModelSpec("step-1v-32k", "Step-1V（看图）", vision = true))),

        Provider("yi", "零一万物 Yi", "https://api.lingyiwanwu.com/v1",
            models = listOf(
                ModelSpec("yi-lightning", "Yi Lightning"),
                ModelSpec("yi-vision-v2", "Yi Vision V2（看图）", vision = true))),

        Provider("baichuan", "百川智能", "https://api.baichuan-ai.com/v1",
            models = listOf(ModelSpec("Baichuan4-Turbo", "百川 4 Turbo"))),

        Provider("sensenova", "商汤日日新", "https://api.sensenova.cn/compatible-mode/v1",
            models = listOf(ModelSpec("SenseNova-V6-Pro", "日日新 V6 Pro", vision = true))),

        Provider("siliconflow", "硅基流动（聚合）", "https://api.siliconflow.cn/v1",
            models = listOf(
                ModelSpec("deepseek-ai/DeepSeek-V3", "DeepSeek V3"),
                ModelSpec("Qwen/Qwen3-Coder-480B-A35B-Instruct", "Qwen3 Coder 480B"),
                ModelSpec("Qwen/Qwen2.5-VL-72B-Instruct", "Qwen2.5 VL（看图）", vision = true))),

        /* ==================== 国外 ==================== */

        Provider("openai", "OpenAI", "https://api.openai.com/v1",
            models = listOf(
                ModelSpec("gpt-4o", "GPT-4o", vision = true),
                ModelSpec("gpt-4.1", "GPT-4.1", vision = true),
                ModelSpec("gpt-4o-mini", "GPT-4o mini", vision = true),
                ModelSpec("o4-mini", "o4-mini", vision = true))),

        Provider("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1",
            protocol = Protocol.ANTHROPIC, keyHint = "sk-ant-...",
            models = listOf(
                ModelSpec("claude-sonnet-4-5", "Claude Sonnet 4.5", vision = true),
                ModelSpec("claude-opus-4-1", "Claude Opus 4.1", vision = true),
                ModelSpec("claude-3-5-haiku-latest", "Claude 3.5 Haiku", vision = true))),

        Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta",
            protocol = Protocol.GEMINI, keyHint = "AIza...",
            models = listOf(
                ModelSpec("gemini-2.5-pro", "Gemini 2.5 Pro", vision = true),
                ModelSpec("gemini-2.5-flash", "Gemini 2.5 Flash", vision = true))),

        Provider("xai", "xAI Grok", "https://api.x.ai/v1", keyHint = "xai-...",
            models = listOf(
                ModelSpec("grok-4", "Grok 4", vision = true),
                ModelSpec("grok-3-mini", "Grok 3 mini"))),

        Provider("mistral", "Mistral", "https://api.mistral.ai/v1",
            models = listOf(ModelSpec("mistral-large-latest", "Mistral Large", vision = true))),

        Provider("groq", "Groq（极快）", "https://api.groq.com/openai/v1", keyHint = "gsk_...",
            models = listOf(
                ModelSpec("llama-3.3-70b-versatile", "Llama 3.3 70B"),
                ModelSpec("qwen/qwen3-32b", "Qwen3 32B"))),

        Provider("openrouter", "OpenRouter（聚合）", "https://openrouter.ai/api/v1",
            keyHint = "sk-or-...",
            models = listOf(
                ModelSpec("anthropic/claude-sonnet-4.5", "Claude Sonnet 4.5", vision = true),
                ModelSpec("google/gemini-2.5-pro", "Gemini 2.5 Pro", vision = true),
                ModelSpec("deepseek/deepseek-chat", "DeepSeek V3"))),

        Provider("together", "Together AI", "https://api.together.xyz/v1",
            models = listOf(ModelSpec("Qwen/Qwen2.5-72B-Instruct-Turbo", "Qwen2.5 72B"))),

        /* ==================== 本机 ==================== */

        Provider("ollama", "本机 Ollama", "http://127.0.0.1:11434/v1",
            keyHint = "随便填（如 ollama）",
            models = listOf(
                ModelSpec("qwen2.5-coder:7b", "qwen2.5-coder 7B"),
                ModelSpec("qwen2.5vl:7b", "qwen2.5-vl（看图）", vision = true))),

        Provider("lmstudio", "本机 LM Studio", "http://127.0.0.1:1234/v1",
            keyHint = "随便填",
            models = listOf(ModelSpec("local-model", "本地模型")))
    )

    fun byId(id: String): Provider = ALL.firstOrNull { it.id == id } ?: ALL.first()

    fun supportsVision(p: Provider, model: String): Boolean =
        p.models.firstOrNull { it.name == model }?.vision ?: false

    fun labelOf(p: Provider, model: String): String =
        p.models.firstOrNull { it.name == model }?.label ?: model
}
