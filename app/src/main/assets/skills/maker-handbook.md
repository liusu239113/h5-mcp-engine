# Maker / UrhoX 工程手册（Lua 引擎，不是 H5）

**先判断你手上是哪种工程** —— 两者写法完全不同，别串：

| 信号 | 类型 | 看哪份 |
|---|---|---|
| `scripts/main.lua` 存在 | **Maker / UrhoX** | 这份 + `urhox-maker-handbook.md` |
| `urhox-libs/` 目录存在 | **Maker / UrhoX** | 同上 |
| 只有 `index.html`（且不是占位页） | **H5** | `h5-handbook.md` |

不确定就读 `engine_status`，或者看 `scripts/` 下有没有 `main.lua`。

---

## 1. 工程结构

```
工程根/
├── scripts/
│   ├── main.lua          ← 入口，必须有 Start()
│   └── network/          ← 多人：Client.lua / Server.lua / Standalone.lua
├── assets/               ← 纹理 / 声音 / 模型
├── urhox-libs/           ← 引擎工具库（只读）
├── .project/settings.json
└── .hexora-kind          ← 可选，手写 h5 / maker 强制指定工程类型
```

**入口必须是 `scripts/main.lua` 里的 `function Start()`**，否则起不来。

---

## 2. 多人还是单机 —— 先查这个

```bash
# 读 .project/settings.json 的 @runtime.multiplayer.enabled
```

| 这个字段 | 模式 | 玩法代码写到 |
|---|---|---|
| `true` | 多人 | `scripts/network/Client.lua` + `Server.lua` |
| `false` / 不存在 | 单机 | `scripts/network/Standalone.lua` |

⚠️ **一旦是 `true`，之后每个新功能都要按多人做**，不是只有当前这个。
多人必读 `engine-docs/recipes/network-game-guide.md`（权威服务器、Scene Replication、远程事件）。

---

## 3. 运行与验证（⚠️ 先看这一节，别用错工具）

### 🔴 最容易踩的坑：Maker 用不了 `game_shot` / `game_validate`

**不要**在 Maker 工程上调 `game_shot` / `game_shot_motion` / `game_validate` ——
它们抓的是**系统 WebView** 里的页面，而 Maker 工程跑在**预览页的 GeckoView**（Firefox 内核）里，
WebView 里根本没有它。你会抓到一片空白 / 对话页，然后误判成「游戏白屏了」。

App 也认这一点：Maker 工程**不会**被强制截图自检（那个闸门只在 H5 工程生效）。

### Maker 侧的验证闭环（只有这三步，照做）

| 步骤 | 工具 / 动作 | 得到什么 |
|---|---|---|
| ① **构建** | `maker_build_current_directory` | **唯一权威的通过/失败信号**。Lua 是运行时报错的语言，语法检查抓不到大部分问题，只有真构建才知道 |
| ② **读构建输出** | 构建回执本身 | 报错行、堆栈、失败原因都在里面 —— **认真读完，别只看第一行** |
| ③ **让人看画面** | 构建成功后 App 会自动把预览页切到 GeckoView 控制台 | 预览页 = **真机效果**（就是用户看到的那一屏） |

### 怎么把「画面」交给用户确认（不要自己瞎猜）

Maker 工程**你无法自己看图**。所以正确做法是：

1. 构建通过；
2. 在回复里说清「**已切到预览页，直接看就是真机效果**」；
3. 把这一轮改了什么、**该重点看哪里**列出来（例如「看主菜单的三个按钮间距」）；
4. 让用户回一句「对 / 不对」—— 他看一眼比你猜十轮都快。

⚠️ **绝对不要**：把 `game_shot` 的空白图当成「游戏白屏」来汇报；也不要
「我截图确认过了」——你在 Maker 侧截不到图，说这句话就是撒谎。

### 逻辑自检（能做的部分）

构建通过只代表**能跑起来**，不代表**逻辑对**。逻辑只能靠读代码 + 让用户试玩：

- 改完先自己 `game_read` 读一遍关键路径：状态机分支、边界（0 / 空 / 连点）、
  资源路径（`assets/...`）、多人工程有没有写进 `network/`；
- 单位（米）、数组下标（从 1）、NanoVG 事件（`NanoVGRender`）这三条最容易写错，
  逐条对着代码核一遍；
- 然后**明确请用户试玩**并给出「该看什么」，而不是自己宣布完成。

---

## 4. 引擎硬规则（违反必炸）

这些在 `urhox-maker-handbook.md` 里有完整版，这里是最高频的：

| 规则 | 说明 |
|---|---|
| **长度单位是米** | 角色 1.5~2.0，不是像素。写 100 的话角色会飞出屏幕 |
| **Y-up 左手系**（同 Unity） | Y 上、X 右、Z 前 |
| **Lua 数组从 1 开始** | `arr[0]` 是 nil → `attempt to index a nil value` |
| **`graphics:SetMode()` 已禁用** | 用 `GetWidth()` / `GetHeight()` / `GetDPR()` |
| **NanoVG 必须挂 `NanoVGRender` 事件** | 写在 Update 里画不出来 |
| **`nvgCreateFont` 只在初始化调一次** | 每帧调会显存泄漏；文字不显示多半是忘了建字体 |
| **UI 用 `urhox-libs/UI`** | 原生 UIElement 已废弃；raw NanoVG 只用来画图形 |
| **程序化材质只有三个 Technique** | `PBRNoTexture` / `PBRNoTextureAlpha` / `NoTextureUnlit` |
| **第三人称相机用 `ThirdPersonCamera` 库** | 别自己算位置，符号很容易反 |
| **鼠标键用 `MOUSEB_LEFT`** | 不要用数字 0 |
| **`io` 库已移除** | 读写文件用 `File` |

---

## 5. 素材从哪来

Maker 侧的素材工具（**不需要 OAuth 授权**，本机通道）：

| 工具 | 干什么 |
|---|---|
| `maker_list_apps` | 列我的 Maker 项目 |
| `maker_ensure_project` | 绑定 / 新建项目 |
| `maker_ui_list_kits` + `maker_ui_apply_kit` | 预制 UI 风格包（**一键换肤**）|
| `maker_remove_bg` | 抠图 / 去背景（走抠抠图，要配 Key，**只在补救时用**）|
| 生图 / 音乐 / 音效 / 配音 / 视频 / 3D | 云素材生成 |

**透明底素材**：生图时显式传 `transparent=true`，**不花积分、不用 Key**。
只在 prompt 里写「透明背景」四个字是没用的 —— 那只是给模型的描述，不会输出 alpha 通道。

---

## 6. UI 风格（Maker 侧怎么用那三套）

`ui-astroon` / `ui-brawlforge` / `ui-pixelforge` 里的**色值和设计规则两端通用**。

Maker 侧落地方式：
- 优先用 `urhox-libs/UI` 组件 + 把色值传进去（组件的 `theme` / 样式参数）
- 也可以用 `maker_ui_apply_kit` 套预制的 UI 风格包
- **不要**去写 `UI.Theme.Color(...)` —— 那是另一套 API，会报错

---

## 7. 自检清单

改完 Maker 代码，交付前：

- [ ] `maker_build_current_directory` **构建成功**（这是唯一权威信号）
- [ ] **认真读完构建输出**，没有报错行 / 堆栈
- [ ] 关键路径自己 `game_read` 读过：状态机分支、边界（0 / 空 / 连点）、资源路径
- [ ] 多人工程：确认代码写进了 `network/` 对应文件，不是 `Standalone.lua`
- [ ] 单位是米、数组从 1 开始、NanoVG 挂了 `NanoVGRender`（这三条最容易忘）
- [ ] 回复里写清「已切到预览页，重点看 XX」，并**请用户确认**画面
- [ ] ❌ **没有**用 `game_shot` / `game_shot_motion` / `game_validate`（Maker 侧它们抓不到东西）
- [ ] ❌ **没有**写「我截图确认过了」（你在 Maker 侧截不到图）
