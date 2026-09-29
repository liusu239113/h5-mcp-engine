package com.mcp.h5engine

import android.content.Context
import java.io.File

/**
 * AI 代理循环：把「用户一句话」变成「多轮工具调用」。
 *
 * 事件协议（界面靠前缀分流，别改前缀）：
 *   STEP:n            第 n 轮开始
 *   THINK:xxx         模型的思维链（deepseek-reasoner 之类）
 *   AI:xxx            模型说出来的一段话
 *   TOOLRUN:[名字] 文件 一次工具调用开始（文件可空，见 toolTarget）
 *   TOOL:[名字] 摘要   一次工具调用成功
 *   TOOLFAIL:[名字] 摘要 一次工具调用失败
 *   RELOAD            文件被改过，预览该刷新了
 *   INFO:xxx          普通提示，进日志
 *   [失败] xxx         错误卡片
 */
class AgentRunner(
    private val appCtx: Context,
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
        userImages: List<ByteArray> = emptyList(),
        /** true = **接着上一轮继续**（错误卡片上的「再试一次」）：不新增用户消息，轮次从 startStep 往下数 */
        resume: Boolean = false,
        /** 续跑时的起始轮次；用于让轮次接着数、不「清零」 */
        startStep: Int = 0
    ) {
        // 工具定义是一轮任务里每一步都要重发的大块头（内置工具 + MCP 几十个），
        // 而且模型每次调用工具都要重来一遍。slimTools 打开时按白名单砍掉
        // 跟「做游戏」完全无关的平台作者向工具。
        // ⚠️ 砍工具 = 模型看不到就调不了，属于**能力损失**，所以默认关，由用户在设置里自己开。
        val allow = when {
            !cfg.slimTools -> skill.allowTools
            skill.allowTools == null -> tools.names().filterNot { it in EngineTools.SLIM_DROP }.toSet()
            else -> skill.allowTools.filterNot { it in EngineTools.SLIM_DROP }.toSet()
        }
        val spec = tools.specs(allow)
        val names = spec.map { it.getJSONObject("function").getString("name") }

        val sys = buildString {
            append(skill.system)
            // C1 长期记忆：把与本轮输入最相关的几条注入系统提示
            append(MemoryStore.promptBlock(appCtx, userText))
            // 交付纪律：用户反复踩到的坑，直接写死在系统提示里
            append(
                """

                【交付纪律 —— 必须遵守】
                1. 已经产出的文件（音效 / 配音 / 图片 / 视频）就是「已完成」，**不要**和「某条链路失败」混在一句话里说，
                   那会让用户以为白干了。要分开写：「已完成」列实物（文件名 / 时长 / 大小），「没走通」单独一条说明。
                2. 交付媒体文件时，App 会自动在对话里插一张能点的播放卡，你只要说清「是什么 / 多长 / 在哪」即可。
                   **不要**只丢一个文件路径当交付，**更不要**让用户自己切页、去点你临时注入的调试按钮才听得到 ——
                   你没有切页能力（对话 / 预览 / 发布是用户的原生界面，不许动）。
                3. 临时注入到页面的调试按钮 / 钩子属于一次性手段：用完自己撤掉，且**不要**把它当成交付入口告诉用户。
                4. 先给结论与实物，再讲过程与失败项；不要长篇解释。
                """
            )
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

            // ===== 工具归属纪律（用户反复被这两套东西搞混，这里硬性写死）=====
            append(
                """

【工具归属：Maker 和 TapTap 开放平台是两套完全不同的东西，别混】
- maker_ 开头 = TapTap Maker（本机通道）：素材生成（生图 / 音乐 / 音效 / 配音 / 视频 / 3D）、
  Maker 项目查询与绑定。**不需要任何 OAuth 授权**，凭证是本机的 pat.json。
  · 用户问「我的 Maker 项目 / 应用都有什么」→ 用 maker_list_apps；
  · 要绑定或新建 Maker 项目 → maker_ensure_project；
  · 生图/音乐/音效等素材工具也在这个通道里。
- mcp_ 开头 = TapTap 小游戏开放平台（H5 通道）：游戏上传上架、应用信息、开发者数据、排行榜、
  社区、广告位配置。**需要 OAuth 授权**，跟 Maker 一点关系都没有。
- 铁律：用户说的 Maker / 生图 / 素材 / 音效，一律只用 maker_ 工具；
  绝不要用 mcp_list_developers_and_apps / mcp_get_current_app_info / mcp_complete_oauth_authorization
  之类的开放平台接口去回答 Maker 的问题；
  更不要把这些接口的 OAuth 授权链接当作「Maker 授权」发给用户 —— 用户会当成你在乱搞。
- 只有当用户明确在做 H5 游戏上架 / 开放平台数据这类事时，才用 mcp_ 工具。
- Maker 相关失败时，不要立刻推给用户点链接：先确认是不是该用 maker_list_apps 之类的本地工具，
  以及 Maker 子进程是否在跑（工具会返回明确原因）。
- 用户要「换号 / 换个 TapTap 账号授权 / 退出当前 Maker 登录」→ 用 maker_auth action=switch
  （先清掉旧凭证、再出一个新授权链接）；只想退出用 action=logout；
  用户自己已经在 maker.taptap.cn/pat-tokens 建好 token → 用 action=token 把它粘进来。
  **不要**回答「换不了号」，也不要把用户支使去设置页。""".trimIndent()
            )

            // ===== 工程类型（H5 / Maker），两类处理方式完全不同 =====
            append(
                """
【工程类型：H5 和 Maker（UrhoX）完全是两种东西，处理方式不同】
- H5 工程：目录里有 index.html，WebView 直接跑 → 预览页能看，用 game_shot / screenshot 验证。
- Maker（UrhoX）工程：真构建过一次就定类型（App 会写 .hexora-kind，也可点预览页标题手动切），是原生引擎项目。
  预览已经内置：App 的预览页用 GeckoView 打开 maker.taptap.cn 控制台（那就是真机效果），所以「看效果」= 看那个预览页；
  不要用 WebView 直接开它，也不要拿 screenshot / game_shot 去“看游戏画面”—— 那截到的只会是对话页，对 Maker 工程没意义。

对 Maker 工程，「看效果 / 验证游戏」只有一条正确路径：
- 构建已在每轮改完代码后由 App **自动触发**，你不用每次手动再调一遍；只有自动构建没走通、或用户明确说「现在就构建」时，才手动调 maker_build_current_directory；
- **别只丢一个链接**：构建成功后 App 会自动把预览页切过去，汇报时要说清「已切到预览页，直接看就是真机效果」，链接只是备选（他没登录 / 要分享时用）；
- 用户说“提交”“推送”“构建”“预览”“跑一下”“看看效果”“验证游戏效果”时，都走这个工具；
- 用户说“验证代码”“跑测试”“lint”“检查实现”时，不要当成构建。

对 Maker 工程，「拉代码 / 同步 / 初始化」只有一条正确路径：
- **直接调工具 maker_project**（action=status 看状态，action=init 拉取/初始化，action=devkit 更新 dev-kit）。
- 用户说“拉取工程 / 初始化工程 / 拉取代码 / 把 Maker 项目弄到本地 / 同步代码 / 装开发文档”时 → 调 maker_project，
  **不要**跟用户说“我没有拉代码的能力”，也不要把这项活儿推给设置页按钮；
  **更不要**跑去讲 MCP / OAuth / 开放平台授权（那是另一套东西，用户会以为你在乱搞）——就是直接调 maker_project；
- 它内部就是 App 自带的 git（musl/aarch64）+ taptap-maker init，会装 AI dev-kit
  （CLAUDE.md / examples / templates / urhox-libs）并初始化工程骨架；
- 这是慢操作，调之前先告诉用户“正在拉取，可能要几十秒”；
- **改 Maker 工程代码前，先读工程里的 CLAUDE.md**（它是官方开发指南入口），
  需要实现范例看 examples/，需要引擎 API / 能力名看 urhox-libs/。
""".trimIndent()
            )
            // ===== UI 风格 / 设计文档 / 交付标准 =====
            // 用户明确要求：引擎要预制好看的 UI（不许原生丑样式）、不许只出简陋 demo，
            // 并且要养成「先写设计文档 → 按文档执行」的习惯。这里硬性写死。
            append(
                """

【UI 与交付：先挑风格、先写文档、别交 demo】
- 写任何界面之前先调 maker_ui_list_kits，按题材挑一套（武侠/仙侠/江湖→ink，像素/怀旧/8bit→pixel16，
  休闲/三消/儿童→cartoon，卡牌/乙女/文字冒险→anime，动漫/漫画/少年热血→comic，
  童话/猜谜/治愈→paper，勇者/剑与魔法/放置编队→night，爬塔/构筑/科幻→neon，
  经营/模拟管理/数据面板→biz，历史/朝堂/权谋/人生模拟→scroll），再调 maker_ui_apply_kit 落地到项目 ui/ 目录。
  · anime（动漫玻璃）= 深色底 + 毛玻璃 + 冷光，适合卡牌 / 乙女 / 文字冒险。
  · comic（动漫绘本）= 纸白底 + 墨线描边 + 硬投影（无模糊）+ 草木绿配暖金，适合动漫 / 漫画 /
    绘本 / 少年热血 / 校园搞笑。
  · paper（手绘童话）= 纸黄底 + 橙棕手绘描线 + 靛蓝点缀，适合童话 / 猜谜 / 看图解谜 / 治愈休闲。
  · night（暗夜勇者）= 暗紫夜底 + 青绿描边 + 暖金奖励，适合 RPG / 勇者 / 剑与魔法 / 放置编队。
  · neon（星塔霓虹）= 星夜紫底 + 霓虹青绿/蓝 + 发光描边，适合 Roguelite / 爬塔 / 卡牌构筑 / 科幻。
  · biz（经营报表）= 浅灰底 + 白卡片 + 深蓝主色 + 多色语义指标，适合经营 / 模拟管理 / 数据面板。
  · scroll（古卷暖褐）= 暗褐卷底 + 暗金描线 + 玉绿朱红点缀，适合历史 / 朝堂 / 权谋 / 人生模拟。
  · 落地后项目里会多一份 ui/HOWTO.md（该风格的写法手册），**写页面前先读它**，照抄里面的骨架。
- 页面引用 ui/theme.css 与 ui/components.css（相对路径），body 加 class="hx-root"，
  组件类名与用法照项目里的 ui/SPEC.md 抄；颜色/圆角/字体一律 var(--hx-*)。
  **严禁浏览器原生默认样式**：不许裸 <button> 当按钮、不许用 alert/confirm 当弹窗。
  界面用 DOM + CSS，canvas 只画场景 / 角色 / 特效。
- 做或大改一个游戏：第一步先写 <项目>/DESIGN.md（一句话玩法、核心循环、画面与 UI 风格含选定 kit、
  关卡与数值、素材清单、文件结构、里程碑、验收清单），**没有设计文档就不要动手写游戏代码**；
  每完成一个里程碑回文档改成 - [x]。
- 交付标准：开始 → 游玩 → 结算 / 重开的闭环、关键操作有反馈与音效、localStorage 存档、
  适配安全区、控制台零报错、无 TODO、无占位方块。宁可少做两个功能，也别交一个残缺的壳。""".trimIndent()
            )

            // ===== 代码检索：先搜再改 =====
            append(
                """
【改代码前先 code_search，别靠猜】
- 要改某段逻辑，先用 code_search 搜关键词 / 函数名（返回「文件:行号: 内容」），拿到准确位置再动手；
- 比一个个 game_read 翻文件省大量 token，也不容易漏掉别处的引用点；
- 搜不到再扩大范围（不传 path 搜全工程，或换个更短的关键词）。
""".trimIndent()
            )

            // ===== 工具重试纪律 =====
            // 用户原话：「ai 老是这样，也不重试」。本地 MCP 子进程刚重启的一两秒里，
            // 工具清单可能还没注册完，模型一撞上「未知工具」就放弃 —— 这里是最后一道提示词保险。
            append(
                """

【工具报「未知工具 / 调用失败」时：先重试，别放弃】
- 「未知的 MCP 工具」几乎都不是「这个能力不存在」，而是本地服务刚重启、工具清单还没注册完。
- 处理办法：等 5 秒左右，用**完全相同的工具名**再调一次（最多 3 次），通常第二次就通了。
  不要改工具名、不要换写法、不要绕道自己手写替代实现，也**不要**跟用户说「我没有这个能力」。
- 生图 / 生音乐拿不到时，先调 maker_list_apps（本地工具，一定会回）探一下通道，再重试目标工具。
- 连续 3 次仍失败才如实汇报，并写明：工具名、报错原文、已重试次数；并说明「通道会被 App 在后台自动重连，过一会儿再叫我试一次」。""".trimIndent()
            )

            // ===== 图片素材：透明底 =====
            // 生图工具自带 transparent=true，生成即透明 —— 这是主路径，不花钱、不要 Key。
            // maker_remove_bg 只是「存量白底图补救 / 想另存一份」的兜底。
            append(
                """

【透明底：生图时就传 transparent=true，别等生成完再抠】
- **图标 / logo / 角色 / 道具 / UI 元件这类素材默认就要透明底**：调 generate_image /
  batch_generate_images 时**显式传 `transparent=true`**（这是**工具参数**，和 prompt 里写
  「透明背景」是两码事）——生成出来直接是透明 PNG，**不花积分、不用任何 Key**。
- 场景 / 大地图 / Tile 这类**不要**透明：传 `transparent=false`。
- ⚠️ 只在 prompt 里写「透明背景」、却没传 `transparent=true`，结果就是一张白底图 —— 这是最常见的坑。
- 别手写 canvas 色键 / mix-blend-mode 去凑透明底（抠不干净、边缘毛边）。
- maker_remove_bg 只在**补救**时用：① 项目里已有白底素材要救；② 需要另存一份透明图。
  它要先在 设置 → 图片工具（抠图） 配一次 Key（https://www.koukoutu.com/user/dev，
  1 积分/张、png 再 +1）；返回「没配 Key」就如实告诉用户去哪配，别反复重试白烧额度。""".trimIndent()
            )
        }

        if (history.isNotEmpty() && history[0].role == "system") {
            history[0] = ChatMsg("system", sys)
        } else {
            history.add(0, ChatMsg("system", sys))
        }

        // resume = 接着上一轮跑：**不**再往历史里塞一条用户消息。
        // 塞了的话历史里就会有两句一模一样的用户请求，模型容易把活重做一遍；
        // 轮次又会从 1 重新数 —— 用户看到的就是「点了重试，前面全白干」。
        if (!resume) history += ChatMsg("user", userText, userImages)

        val limit = cfg.maxSteps.coerceAtMost(skill.maxSteps)
        var step = if (resume) startStep.coerceAtLeast(0) else 0
        var wrote = false

        // 续跑但步数预算已经用满：明说一句。否则用户点了「再试一次」之后毫无反应，
        // 只会以为按钮坏了。
        if (resume && step >= limit) {
            onEvent("INFO: 已经用满 $limit 轮上限了 —— 直接再发一句话，它就能接着往下做")
            return
        }

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
                onEvent("INFO: 完成 · 共跑了 $step 轮 · " + TokenStats.summary())
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
                // 把「动的是哪个文件」一起报给界面：对话里那行就能显示成「修改 main.js」，
                // 而不是光秃秃一个 game_write —— 用户要看得见 AI 正在改哪份文件。
                onEvent("TOOLRUN:[${tc.name}]" + toolTarget(tc.name, tc.argsJson))
                val res = runCatching { tools.call(tc.name, tc.argsJson) }
                    .getOrElse { t -> EngineTools.ToolResult("工具执行出错：${t.javaClass.simpleName}: ${t.message}") }
                val images = if (cfg.vision) res.images else emptyList()
                var text = res.text
                if (res.images.isNotEmpty() && !cfg.vision && visionFallback) {
                    text += "\n" + saveShot(res.images.first())
                }
                // 【结果治理】单条工具结果太大（读了个大文件 / 全量日志）会把历史瞬间撑爆：
                // 下一轮请求体积超限 → 降级重试 → 还是超 → 报错。这里先截断，
                // 完整内容仍然在对话里的工具卡片（点击可看/复制），模型需要细节可以再分段读。
                if (text.length > 16000) {
                    text = text.take(16000) + "\n…（结果过长已截断到 16k；完整内容见对话里的工具卡片）"
                }

                history += ChatMsg("tool", text, images, toolCallId = tc.id)

                var head = res.text.lineSequence().firstOrNull() ?: ""
                if (head.length > 150) head = head.take(150) + "…"
                // 判失败：工具返回首行带这些味道 = 失败了，UI 卡片显示红点（Operit 那种）。
                val fail = head.startsWith("错误") || head.contains("失败") ||
                    head.contains("工具执行出错") || head.contains("未授权") || head.contains("超时")
                // NUL 分隔：head（进卡片摘要/思考面板）+ full（详情弹窗用全文），
                // 用 \u0000 当分隔符是因为工具文本里绝不可能出现它，不会串味。
                val full = res.text.replace("\u0000", "").take(4000)
                onEvent((if (fail) "TOOLFAIL:" else "TOOL:") + "[${tc.name}] " + head + "\u0000" + full)

                // Maker 会把生成的图/音「materialize」到项目里并返回本地路径：
                // 捞出来丢给 UI，用户就能在对话里直接看到缩略图、点一下预览/试听。
                if (tc.name.startsWith("mcp_") || tc.name.startsWith("maker_")) {
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
     * 从工具参数里抠出「这次动的是哪个文件」，给界面那行显示用（可空）。
     *
     * 只认本地读写类工具（game_*）的 path 参数：这几个是唯一能确定
     * 「确实在碰某个文件」的。MCP / 云工具的 path 语义各家不同，硬猜会显示错文件名，
     * 那比不显示更糟 —— 所以宁可不显示，退回工具名。
     */
    private fun toolTarget(name: String, argsJson: String): String {
        if (!name.startsWith("game_")) return ""
        return runCatching {
            val o = org.json.JSONObject(argsJson)
            // path 为准；game_create 没有 path，退一步用它的工程 id（显示成「新建 my_game」）
            val p = o.optString("path", "").ifBlank { o.optString("id", "") }.trim()
            // 只留文件名：路径整串太长，对话里那一行放不下（完整路径点开详情能看到）
            if (p.isBlank()) "" else p.substringAfterLast('/')
        }.getOrDefault("")
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