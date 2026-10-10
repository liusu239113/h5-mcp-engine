package com.mcp.h5engine

/** 三种主流协议：OpenAI 兼容 / Anthropic / Gemini */
enum class Protocol { OPENAI, ANTHROPIC, GEMINI }

/** 一个模型。vision=true 表示它自带看图能力，默认就走多模态截图闭环 */
data class ModelSpec(
    val name: String,
    val label: String,
    val vision: Boolean = false
)

data class Provider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val protocol: Protocol = Protocol.OPENAI,
    val keyHint: String = "sk-...",
    /** 分组：国内 / 国外 / 本地 */
    val group: String = "国内",
    val models: List<ModelSpec>,
    /** Base URL 是否建议用户自己改（中转站、Agnes 之类） */
    val editableBaseUrl: Boolean = false
)

data class ProviderConfig(
    val provider: Provider,
    /** 已解析好的实际请求地址（可能是用户自定义的 Base URL） */
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val modelLabel: String,
    val temperature: Double,
    val maxSteps: Int,
    /** 当前模型是否走多模态：截图直接作为图片喂给模型 */
    val vision: Boolean,
    /**
     * 输出上限（max_tokens）。0 = 不发送，由服务商按其模型上限预扣。
     * 免费档 / TPM 小的厂商（Groq 免费 8000 TPM、智谱、部分中转）必须调小，
     * 否则一条「工具定义 + 历史」就能把单次请求顶过限额（413 / 429）。
     */
    val maxOutTokens: Int = 0,
    /** 历史上限：首条 system + 最近 N 条消息。越小请求越轻 */
    val historyLimit: Int = 40,

    /**
     * 模型上下文窗口（token）。**压缩按它来算，而不是按条数拍脑袋**。
     *
     * 为什么要这个：以前压缩是「按条数裁 + 超过固定字符数就折」——
     * 那个阈值跟模型真实能装多少没关系：小窗口模型早就爆了还没压，
     * 大窗口模型被压得过早、白丢上下文。
     *
     * 现在改成：估算当前历史 token 数，**超过窗口的 75% 才压**（留 25% 给回复 + 工具定义）。
     * 0 = 用默认值（[DEFAULT_CONTEXT_WINDOW]）。
     */
    val contextWindow: Int = 0,

    /**
     * 历史里最多保留几张截图。
     *
     * 图片是 base64 直接塞在请求体里的：一张 720px JPEG ≈ 60~150 KB，base64 后还要 ×1.37。
     * 历史里攒十张，就是**每一步**都上传 1~2 MB —— 手机上行带宽是瓶颈，光这一项
     * 就能让每步多等十几秒。AI 自检只需要看**最新**那张画面，旧的既没用又贵。
     */
    val keepImages: Int = 2,
    /**
     * 最近多少条「长」工具结果保留全文，更早的压成一行占位。
     *
     * 一条 16000 字符的工具结果 ≈ 6k token，而且会在**之后每一步**里被重复发送。
     * 十步前读的文件，模型基本不会再回头看，但每一轮都在为它付钱。
     * 只压「长」结果（>400 字）：像「已写入 x（120 行）」这种短回执原样保留，
     * 丢掉的全是大文件/搜索结果 —— 那些需要时重新读一次就行。
     */
    val keepToolResults: Int = 6,
    /**
     * 精简工具定义（按白名单砍掉跟做游戏无关的「平台作者向」工具）。
     * 默认关：砍工具 = 模型看不到它，属于能力损失，得用户自己决定。
     */
    val slimTools: Boolean = false,
    /**
     * 人格锁定：不管你从哪个角度问（你是什么模型 / 谁训练的 / 是不是 GPT），
     * 它都只承认自己是 [personaName]，不吐露底层模型和厂商。
     */
    val personaLock: Boolean = true,
    /** 人格名，默认就是应用名。 */
    val personaName: String = "Hexora",
    /** 是否携带工具定义。50+ 个工具的 JSON 是 token 大头，关掉最省（AI 就不能调工具） */
    val sendTools: Boolean = true,
    /** 遇 413 / TPM / 上下文超限时，自动精简请求并重试 */
    val autoSlim: Boolean = true
)

/**
 * 大模型预设（2026-09 版）。注意：厂商改名很快，设置页有「拉取最新模型列表」
 * 可以直接问厂商要当前可用模型，比任何硬编码预设都准。
 *
 * 约定：
 *  - 自带视觉的模型一律 vision = true，默认就开，不需要手动打开；
 *  - 设置里可「自动 / 强制开 / 强制关」视觉，用来应对改名或新出的模型；
 *  - Base URL 全部可改，方便走中转或自建代理。
 */
object AiProviders {

    val ALL: List<Provider> = listOf(

        /* ==================== 国内 ==================== */

        Provider(
            "deepseek", "DeepSeek 深度求索", "https://api.deepseek.com/v1",
            models = listOf(
                // V4.1 Flash 起原生多模态，名字就是 deepseek-flash
                ModelSpec("deepseek-flash", "DeepSeek V4.1 Flash（原生多模态·便宜）", vision = true),
                ModelSpec("deepseek-chat", "DeepSeek Chat / V4 Pro"),
                ModelSpec("deepseek-reasoner", "DeepSeek 思考版"),
                ModelSpec("deepseek-v4-flash-vision-exp", "DeepSeek V4 Flash Vision（旧名）", vision = true)
            )
        ),

        Provider(
            "dashscope", "阿里通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            models = listOf(
                ModelSpec("qwen3-max", "Qwen3 Max"),
                ModelSpec("qwen3-coder-plus", "Qwen3 Coder+（写代码强）"),
                ModelSpec("qwen3-vl-plus", "Qwen3 VL+（看图）", vision = true),
                ModelSpec("qwen3-omni-flash", "Qwen3 Omni Flash（看图·快）", vision = true),
                ModelSpec("qwen-plus", "Qwen Plus")
            )
        ),

        Provider(
            "zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
            keyHint = "xxxx.xxxx",
            models = listOf(
                ModelSpec("glm-4.6", "GLM-4.6"),
                ModelSpec("glm-4.6v", "GLM-4.6V（看图）", vision = true),
                ModelSpec("glm-4.5-air", "GLM-4.5 Air（轻量）"),
                ModelSpec("glm-4-flash", "GLM-4 Flash（免费）")
            )
        ),

        Provider(
            "moonshot", "月之暗面 Kimi", "https://api.moonshot.cn/v1",
            models = listOf(
                ModelSpec("kimi-k2-thinking", "Kimi K2 思考版"),
                ModelSpec("kimi-k2-turbo-preview", "Kimi K2 Turbo"),
                ModelSpec("kimi-latest", "Kimi Latest（看图）", vision = true),
                ModelSpec("moonshot-v1-128k", "Moonshot 128K")
            )
        ),

        Provider(
            "ark", "火山方舟 豆包", "https://ark.cn-beijing.volces.com/api/v3",
            keyHint = "方舟 Key（模型名建议填接入点 ID）",
            models = listOf(
                ModelSpec("doubao-seed-2-0-pro", "Doubao Seed 2.0 Pro（看图）", vision = true),
                ModelSpec("doubao-seed-2-0-flash", "Doubao Seed 2.0 Flash（看图）", vision = true),
                ModelSpec("doubao-seed-1-6", "Doubao Seed 1.6（看图）", vision = true)
            )
        ),

        Provider(
            "hunyuan", "腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1",
            models = listOf(
                ModelSpec("hunyuan-turbos-latest", "混元 TurboS"),
                ModelSpec("hunyuan-t1-latest", "混元 T1（推理）"),
                ModelSpec("hunyuan-vision-1.5", "混元视觉 1.5（看图）", vision = true)
            )
        ),

        Provider(
            "qianfan", "百度文心", "https://qianfan.baidubce.com/v2",
            keyHint = "bce-v3/...",
            models = listOf(
                ModelSpec("ernie-5.0-turbo", "文心 5.0 Turbo", vision = true),
                ModelSpec("ernie-4.5-turbo-vl", "文心 4.5 Turbo VL（看图）", vision = true),
                ModelSpec("ernie-x1-turbo", "文心 X1 Turbo（推理）")
            )
        ),

        Provider(
            "minimax", "MiniMax", "https://api.minimax.chat/v1",
            models = listOf(
                ModelSpec("MiniMax-M2", "MiniMax M2"),
                ModelSpec("MiniMax-VL-01", "MiniMax VL-01（看图）", vision = true)
            )
        ),

        Provider(
            "spark", "讯飞星火", "https://spark-api-open.xf-yun.com/v1",
            keyHint = "APIKey:APISecret",
            models = listOf(
                ModelSpec("4.0Ultra", "星火 4.0 Ultra"),
                ModelSpec("spark-x1.5", "星火 X1.5")
            )
        ),

        Provider(
            "stepfun", "阶跃星辰", "https://api.stepfun.com/v1",
            models = listOf(
                ModelSpec("step-3", "Step-3（看图）", vision = true),
                ModelSpec("step-2-mini", "Step-2 mini")
            )
        ),

        Provider(
            "yi", "零一万物 Yi", "https://api.lingyiwanwu.com/v1",
            models = listOf(
                ModelSpec("yi-lightning", "Yi Lightning"),
                ModelSpec("yi-vision-v2", "Yi Vision V2（看图）", vision = true)
            )
        ),

        Provider(
            "sensenova", "商汤日日新", "https://api.sensenova.cn/compatible-mode/v1",
            models = listOf(
                ModelSpec("SenseNova-V6.5-Pro", "日日新 V6.5 Pro（看图）", vision = true)
            )
        ),

        Provider(
            "agnes", "Agnes AI", "https://api.agnes.ai/v1",
            keyHint = "Agnes API Key",
            models = listOf(
                ModelSpec("agnes-2.0-pro", "Agnes 2.0 Pro（看图）", vision = true),
                ModelSpec("agnes-2.0-flash", "Agnes 2.0 Flash（看图·快）", vision = true),
                ModelSpec("agnes-coder", "Agnes Coder（写代码）")
            ),
            editableBaseUrl = true
        ),

        Provider(
            "siliconflow", "硅基流动（聚合）", "https://api.siliconflow.cn/v1",
            models = listOf(
                ModelSpec("deepseek-ai/DeepSeek-V3.2", "DeepSeek V3.2"),
                ModelSpec("moonshotai/Kimi-K2-Thinking", "Kimi K2 思考版"),
                ModelSpec("Qwen/Qwen3-Coder-480B-A35B-Instruct", "Qwen3 Coder 480B"),
                ModelSpec("Qwen/Qwen3-VL-235B-A22B-Instruct", "Qwen3 VL 235B（看图）", vision = true)
            ),
            editableBaseUrl = true
        ),

        /* ==================== 国外 ==================== */

        Provider(
            "openai", "OpenAI", "https://api.openai.com/v1",
            group = "国外",
            models = listOf(
                ModelSpec("gpt-6-astra", "GPT-6 Astra（最强·看图）", vision = true),
                ModelSpec("gpt-6-sol", "GPT-6 Sol（看图）", vision = true),
                ModelSpec("gpt-6-luna", "GPT-6 Luna（快·便宜·看图）", vision = true),
                ModelSpec("gpt-5.6-terra", "GPT-5.6 Terra（看图）", vision = true)
            )
        ),

        Provider(
            "anthropic", "Anthropic Claude", "https://api.anthropic.com/v1",
            protocol = Protocol.ANTHROPIC, keyHint = "sk-ant-...", group = "国外",
            models = listOf(
                ModelSpec("claude-opus-4-5", "Claude Opus 4.5（看图）", vision = true),
                ModelSpec("claude-sonnet-4-5", "Claude Sonnet 4.5（看图）", vision = true),
                ModelSpec("claude-haiku-4-5", "Claude Haiku 4.5（看图）", vision = true)
            )
        ),

        Provider(
            "gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta",
            protocol = Protocol.GEMINI, keyHint = "AIza...", group = "国外",
            models = listOf(
                ModelSpec("gemini-3-pro", "Gemini 3 Pro（看图）", vision = true),
                ModelSpec("gemini-3-flash", "Gemini 3 Flash（看图）", vision = true),
                ModelSpec("gemini-3-deep-think", "Gemini 3 Deep Think（看图）", vision = true)
            )
        ),

        Provider(
            "xai", "xAI Grok", "https://api.x.ai/v1", keyHint = "xai-...", group = "国外",
            models = listOf(
                ModelSpec("grok-5", "Grok 5（看图）", vision = true),
                ModelSpec("grok-4-1-fast", "Grok 4.1 Fast（看图）", vision = true)
            )
        ),

        Provider(
            "mistral", "Mistral", "https://api.mistral.ai/v1", group = "国外",
            models = listOf(
                ModelSpec("mistral-large-3", "Mistral Large 3（看图）", vision = true),
                ModelSpec("magistral-medium", "Magistral Medium（推理）")
            )
        ),

        Provider(
            "groq", "Groq（极快）", "https://api.groq.com/openai/v1",
            keyHint = "gsk_...", group = "国外",
            models = listOf(
                ModelSpec("openai/gpt-oss-120b", "GPT-OSS 120B"),
                ModelSpec("qwen/qwen3-32b", "Qwen3 32B")
            )
        ),

        Provider(
            "openrouter", "OpenRouter（聚合）", "https://openrouter.ai/api/v1",
            keyHint = "sk-or-...", group = "国外", editableBaseUrl = true,
            models = listOf(
                ModelSpec("anthropic/claude-sonnet-4.5", "Claude Sonnet 4.5（看图）", vision = true),
                ModelSpec("google/gemini-3-flash", "Gemini 3 Flash（看图）", vision = true),
                ModelSpec("openai/gpt-5.2", "GPT-5.2（看图）", vision = true),
                ModelSpec("deepseek/deepseek-chat-v3.2", "DeepSeek V3.2")
            )
        ),

        Provider(
            "together", "Together AI", "https://api.together.xyz/v1", group = "国外",
            models = listOf(
                ModelSpec("Qwen/Qwen3-235B-A22B-Instruct", "Qwen3 235B"),
                ModelSpec("meta-llama/Llama-4-Scout", "Llama 4 Scout（看图）", vision = true)
            )
        ),

        /* ==================== 本地 ==================== */

        Provider(
            "ollama", "本机 Ollama", "http://127.0.0.1:11434/v1",
            keyHint = "随便填（如 ollama）", group = "本地",
            models = listOf(
                ModelSpec("qwen3-coder:30b", "qwen3-coder 30B"),
                ModelSpec("qwen3-vl:8b", "qwen3-vl 8B（看图）", vision = true),
                ModelSpec("glm4:9b", "glm4 9B")
            )
        ),

        Provider(
            "lmstudio", "本机 LM Studio", "http://127.0.0.1:1234/v1",
            keyHint = "随便填", group = "本地",
            models = listOf(ModelSpec("local-model", "本地模型（模型名自行填写）"))
        ),

        Provider(
            "custom", "自定义 / 中转站", "https://your-proxy.example.com/v1",
            keyHint = "填你自己的中转 Key", group = "本地", editableBaseUrl = true,
            models = listOf(ModelSpec("custom-model", "自定义模型（模型名自行填写）"))
        )
    )

    fun byId(id: String): Provider = ALL.firstOrNull { it.id == id } ?: ALL.first()

    fun modelLabel(p: Provider, model: String): String =
        p.models.firstOrNull { it.name == model }?.label ?: model

    /** 预设里标注的视觉能力 */
    fun supportsVision(p: Provider, model: String): Boolean =
        p.models.firstOrNull { it.name == model }?.vision ?: false

    /** 用户手填的新模型：名字里带这些字眼就当作能看图 */
    fun guessVision(model: String): Boolean {
        val m = model.lowercase()
        val hints = listOf(
            "vl", "vision", "omni", "gpt-6", "gpt-5", "gpt-4", "gemini", "claude",
            "grok", "doubao", "seed", "glm-4.6v", "llama-4", "agnes-2",
            "deepseek-flash", "v4-flash", "astra", "sol", "luna"
        )
        return hints.any { m.contains(it) }
    }

    fun groupOrder(): List<String> = listOf("国内", "国外", "本地")

    /**
     * 各厂商「去哪儿申请 API Key」的地址。
     *
     * 用户的要求：配置的时候就得能直接点到官网，别让他自己去搜。
     * 放在这里而不是写死在设置页里 —— 以后厂商换页面（这类控制台改版很勤）只改这一处。
     * 地址是控制台里「创建 / 查看 API Key」的那一页，点开就能建，不用再找菜单。
     */
    private val APPLY_URLS: Map<String, String> = mapOf(
        // ---------- 国内 ----------
        "deepseek" to "https://platform.deepseek.com/api_keys",
        "dashscope" to "https://bailian.console.aliyun.com/?apiKey=1",
        "zhipu" to "https://open.bigmodel.cn/usercenter/apikeys",
        "moonshot" to "https://platform.moonshot.cn/console/api-keys",
        "ark" to "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey",
        "hunyuan" to "https://console.cloud.tencent.com/hunyuan/api-key",
        "qianfan" to "https://console.bce.baidu.com/iam/#/iam/apikey/list",
        "minimax" to "https://platform.minimaxi.com/user-center/basic-information/interface-key",
        "spark" to "https://console.xfyun.cn/services/cbm",
        "stepfun" to "https://platform.stepfun.com/interface-key",
        "yi" to "https://platform.lingyiwanwu.com/apikeys",
        "sensenova" to "https://platform.sensenova.cn/",
        "agnes" to "https://agnes.ai/",
        "siliconflow" to "https://cloud.siliconflow.cn/account/ak",
        // ---------- 国外 ----------
        "openai" to "https://platform.openai.com/api-keys",
        "anthropic" to "https://console.anthropic.com/settings/keys",
        "gemini" to "https://aistudio.google.com/app/apikey",
        "xai" to "https://console.x.ai/",
        "mistral" to "https://console.mistral.ai/api-keys",
        "groq" to "https://console.groq.com/keys",
        "openrouter" to "https://openrouter.ai/keys",
        "together" to "https://api.together.xyz/settings/api-keys",
        // ---------- 本地 / 自建 ----------
        "ollama" to "https://ollama.com/download",
        "lmstudio" to "https://lmstudio.ai/",
        "custom" to ""
    )

    /** 该厂商申请 API Key 的官网地址；没有（自定义 / 中转）就返回空串 */
    fun applyUrlOf(p: Provider): String = APPLY_URLS[p.id] ?: ""

    /** 申请页的简短说明，写进设置页那一行 */
    fun applyHintOf(p: Provider): String = when {
        p.group == "本地" && p.id == "ollama" -> "下载安装 Ollama，本地跑模型不需要 Key"
        p.group == "本地" && p.id == "lmstudio" -> "下载 LM Studio，本地跑模型不需要 Key"
        p.id == "custom" -> "中转站 / 自建代理：Key 找你的服务商要"
        p.id == "ark" -> "方舟控制台 → API Key 管理（模型名建议填接入点 ID）"
        p.id == "qianfan" -> "百度智能云 → 访问控制 → API Key（bce-v3/ 开头）"
        p.id == "spark" -> "讯飞开放平台 → 星火 → 服务管理（格式 APIKey:APISecret）"
        else -> "去官网控制台创建 API Key 后，粘贴到上面"
    }
}