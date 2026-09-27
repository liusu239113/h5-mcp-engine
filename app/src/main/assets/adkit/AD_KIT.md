# 广告接入技能（激励视频）

> 来源：对 `H5_942712_extracted_20260925`（方舟·足球线 H5 成品）的广告实现做了完整拆包分析，
> 把它的接口契约与全部加固纪律提炼成可复用资产：`adkit.js`（随 App 打包）+ 本说明 + AI 技能「广告接入」。
> 并已与 TapTap **官方文档**逐条核对（见下表）。

---

## 零、官方文档核对结果

官方文档：

- 广告总览：`developer.taptap.cn/minigameapidoc/dev/tutorial/open-capabilities/ad/ad/`
- 激励视频广告：`developer.taptap.cn/minigameapidoc/dev/tutorial/open-capabilities/ad/rewarded-video-ad/`
- 变现/开通指南（MCP 版）：`developer.taptap.cn/minigameapidoc/quick-start/mcp-guide/ad-integration-guide/`

官方原文 vs 拆包实现，**核心契约完全一致**：

| 官方口径 | 拆包实现 | 结论 |
|---|---|---|
| `tap.createRewardedVideoAd({ adUnitId })` | 同一接口 | ✅ 一致 |
| 「激励视频广告组件是**单例组件**，多次调用返回同一实例」 | 单例复用 `_tapAd` | ✅ 一致（旧版每流程 create 是 bug） |
| 「组件创建后**自动拉取素材**」，成功走 `onLoad()` | 已按官方补上 `onLoad` → `tapReady` | ✅ 本轮补齐 |
| 失败走 `onError(err)` | 包装成 `reason=REQUEST_FAILED` | ✅ 一致 |
| `show()` 未就绪时返回 rejected，建议 `load().then(show)` | `show().catch(() => load().then(show))` | ✅ 一致 |
| `onClose(res)`：**只有 `res.isEnded === true` 才发奖** | 唯一发奖判据 | ✅ 一致 |
| 「开发者不能主动隐藏或关闭广告」 | 从不主动关闭，只等回调 | ✅ 一致 |
| 「看完/关闭后素材清空，自动加载下一份」 | 不做本地缓存，每次流程走平台 | ✅ 一致 |

官方还明确了两件我们文档里先前没写死的事：

1. **广告类型有四种**：Banner / 激励视频 / 插屏 / 原生模板。本文档与技能聚焦**激励视频**（收益最高、玩家不反感，新手先接这个）。
2. **通道归属不同**：
   - **TapTap 制造 / H5 小游戏 / 星火** → 在 **TapTap 开发者中心**「商店 → 小游戏广告 → 申请开通」走官方广告能力；
   - **Tap 小游戏（在 TapTap App 内运行）与 APK 游戏** → 官方要求接入 **Dirichlet 广告联盟**（`ssp.dirichlet.cn`，用 TapTap 开发者账号登录媒体平台，新建媒体时「应用类型 = 小游戏」）。
   - 无论哪条通道，**游戏侧代码是同一套 `tap.createRewardedVideoAd` 契约**——所以 `adkit.js` 不用改。

官方频率红线（照抄进技能）：激励视频必须**玩家自己点**；插屏间隔 **≥ 2 分钟**；Banner 不得遮挡核心玩法；广告加载失败**跳过**而不是卡住游戏。

---

## 一、TapTap 广告到底怎么接（拆包实测结论）

那份 H5 是**双通道**设计，不是只用一家：

| 通道 | 探测方式 | mode | 拉起方式 |
|---|---|---|---|
| 热点资讯底座 | `window.ColorboxAI.vatask.completeRewardVideo` 是函数 | `app` | `completeRewardVideo()`，**恒零参数** |
| TapTap 小游戏容器 | `window.tap.createRewardedVideoAd` 是函数 | `tapapp` | `tap.createRewardedVideoAd({ adUnitId })` |
| 容器在但能力没注入 | `window.ColorboxAI` 存在但 `vatask` 不可用 | `broken` | 不发奖，提示升级 App |
| 纯网页 | 以上都不成立 | `blocked` | 不发奖，提示「请在 App 内打开」 |

判定顺序写死在 `mode()` 里，**任何一条「不可用」分支都不允许降级成假发奖**——这是它们踩过的大坑：
早期版本「本地来源点击即发奖」，结果桌面测试页的广告按钮全变假按钮，解锁还被写进存档。

### TapTap 侧的关键 API 契约

```js
const ad = tap.createRewardedVideoAd({ adUnitId: '由后台/配置槽下发的广告位 ID' });

ad.onClose(res => { if (res.isEnded) 发奖(); else 不发奖提示"未完整观看"; }); // ★ 完播唯一定义
ad.onError(err => 失败出口(err.errMsg / err.errorMessage));
ad.show().catch(() => ad.load().then(() => ad.show()).catch(失败));        // 未就绪 → load 后重试一次
ad.load();                                                                 // 启动预热，只拉素材不弹界面
```

三个容易写错的点（那份项目都专门修过）：

1. **组件要单例复用**：官方口径是「只创建一次」。旧实现每个流程都 `create` 一次，
   加上超时路径不摘监听器 → 组件和监听器不断累积。
2. **回调只挂一次**：`onClose`/`onError` 在创建时挂好，每个流程只写「当前 resolver 槽」，
   迟到事件落在空槽就丢弃，绝不串到下一个流程。
3. **广告位 ID 不是写死在游戏里的**：它由平台配置层给出
   （`Platform.adsBridge()` → `taptap_config.js` 配置槽；空 = 未配置 → 诚实报 `NO_ADUNIT`）。
   纯 Web 环境（TapTap WebGL 试玩的内嵌浏览器）根本不注入 `tap` 桥，所以探测不到就老老实实 blocked。

### 广告位 ID 的来源层级（本项目实现）

`AdKit.setAdUnitId(id)` > `window.TAP_AD_UNIT_ID` > `<meta name="tap-ad-unit" content="...">`。
发布到 TapTap 时，把后台申请的广告位 ID 配进最外层那一层即可，游戏代码零改动。

---

## 二、那个项目最值钱的 8 条纪律（adkit.js 已全部内置）

1. **只有用户点击能触发**广告；文件加载期零副作用（不在加载/渲染/轮询里调平台）。
2. **单飞**：同一时间只允许一条广告流程，重复点击 → 提示并忽略，不重复拉起。
3. **成功唯一定义**：`code === 200 && data.rewarded === true`（或 TapTap 的 `isEnded === true`）。
4. **失败绝不补发奖励**——补发就是白嫖。
5. **看门狗 90s**：底座 Promise 永不 settle（原生回调丢失 / 广告中切后台 / 网络挂起）时复位，
   否则 `busy` 永久为真，之后点任何广告位都只弹「广告正在播放」，玩家只能刷新页面。
6. **流程纪元 flowSeq**：迟到结果丢弃，防止一次广告发两份奖励。
7. **配额只在 `onReward` 里消耗**：广告失败不扣次数，玩家可重试。
8. **每条出口都有玩家可读提示**，绝不静默；平台新增的失败原因用平台 `message` 原文兜底。

额外两条工程习惯，建议照抄：

- **埋点三段**：入口点击 / 完播发奖 / 失败（带 reason），否则线上失败分布完全不可见。
- **广告打开前主动存档**：看广告期间被杀进程是丢档高发点，先存档再拉广告。

---

## 三、在本工作台里怎么用

1. 让 AI 用技能 **「广告接入（TapTap / 激励视频）」**，它会读 `_shared/adkit.js`（已由 App 释放到游戏根目录）。
2. AI 把 `adkit.js` 内容**复制进游戏工程**（发布要自包含，不能依赖外部地址）。
3. 在游戏入口写闸门，广告位按下面模板接：

```js
// 例：每日 1 次「看视频立即痊愈」
btn.onclick = function () {
  if (AdKit.dailyLeft(State, 'heal', 1) <= 0) { UIkit.toast('今天的次数已用完', 'red'); return; }
  AdKit.show('heal', function () {          // 只有真完播才进来
    AdKit.consumeDaily(State, 'heal');      // 配额在此消耗
    State.data.injuryDays = 0;
    State.save();
    UIkit.toast('伤病痊愈！', 'green');
  });
};
```

4. **预览页能验的**：按钮反馈、配额闸门、提示文案、画面布局。
   **预览页验不了的**：真实视频。自家 App 没有广告 SDK，所以 `mode()` 一定是 `blocked`（诚实提示），
   要看真广告必须放进 TapTap 小游戏容器。调试想跑通发奖链路，本地加 `?admock=1`（仅本地来源生效）。

---

## 四、上线前要准备的东西（按官方流程）

1. **选通道**：
   - TapTap 制造 / H5 小游戏 → 开发者中心「商店 → 小游戏广告 → 申请开通」（需完成开发者认证 + 收款信息）；
   - Tap 小游戏 / APK 游戏 → 登录 **Dirichlet 媒体管理平台**（`ssp.dirichlet.cn`，用 TapTap 开发者账号），新建媒体时**应用类型选「小游戏」**。
2. **创建广告位**，拿到 **广告单元 ID（adUnitId）**（官方原文：「在广告平台创建广告位，获取广告单元 ID」）。
3. **配置进小游戏**：official 接入教程里是 `createRewardedVideoAd({ adUnitId: 'xxxx' })`；本项目用三级来源
   `AdKit.setAdUnitId(id)` > `window.TAP_AD_UNIT_ID` > `<meta name="tap-ad-unit">`，方便一处配置、全游戏生效。
4. **配测试工具**：官方明确建议「配置测试工具，保障测试广告位的展示效果」，不要用正式流量试错。
5. **真机在容器内跑完整链路**：拉起 → 完播 → 发奖 → 埋点，四环节都要有记录。
6. **发布包把 `adkit.js` 顶部的 `MOCK_ENABLED` 改成 `false`**（构建期消除调试后门）。
7. 收益：每月 10 号更新上月广告收益，最低 100 元提现，约 7 个工作日到账（见官方变现指南）。

---

## 五、典型广告位设计参考（那份成品的实际配置）

| 场景 | placement | 配额 | 说明 |
|---|---|---|---|
| 看视频立即痊愈 | `heal` | 每日 1 次 | 顶栏红徽章 + 金框按钮，次数用完只留徽章不给按钮（不骗点击） |
| 赛季末刷新转会报价 | `transfer` | 每赛季 2 次 | 成功后弹「又有几家俱乐部打来电话…」 |
| 属性点 / 技能点 | `attr_season` / `sp_season` | 20 / 5 每赛季 | 大额单次反馈保留爽感，总量收紧防一季推平 |
| 天赋升级 / 自选下家 | `talent_up` / `pick_club` | 每赛季 1 次 | 稀有决策点，失败要重弹卡片把出路留给玩家（用 `onFail`） |
| 败局重赛 | `intl_retry` | — | 模态卡场景，必须传 `onFail`，否则失败后玩家僵死 |
| 隐藏出身解锁 | `origin_family` | — | 一次性解锁类 |

设计口径：**单次奖励要「爽」（大额、即时可见），总量要「省」（配额收紧）**——
爽感放在单价上，别让它变成一季推平数值。

---

## 六、验收清单（接完广告逐条打勾）

- [ ] 加载页面不弹广告、不报错（加载期零副作用）
- [ ] 连点按钮只拉起一条流程，其余提示「广告正在播放」
- [ ] 未完整观看 → 提示未获得奖励，且**配额没被扣**
- [ ] 完播 → 发奖一次，刷新页面不重复发
- [ ] 广告中途切后台再回来 → 90s 内不会永久卡住
- [ ] 平台不可用（纯网页）→ 明确提示去 App 内打开，不假发奖
- [ ] 全部出口都有中文提示，没有「点了没反应」
- [ ] console 里能看到 `[adkit] <placement> rewarded / fail <reason>`
- [ ] 发布包 `MOCK_ENABLED === false`
- [ ] 真机容器内真看过一次完整视频并发奖成功
