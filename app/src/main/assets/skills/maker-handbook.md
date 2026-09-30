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

## 3. 运行与验证（这是 Maker 侧最缺的能力）

### 本机 App 内验证

| 工具 | 干什么 |
|---|---|
| `game_validate` | 重载 → 跑一会儿 → 收报错 + 读页面状态。**改完代码先跑它** |
| `game_shot` | 抓当前画面（不切用户屏幕） |
| `game_shot_motion` | 连抓多帧拼网格，判**运动**（旋转方向 / 动画 / 物理轨迹） |
| `maker_build_current_directory` | 真构建一次。**构建成功 = 这个工程被钉成 Maker** |
| `maker_project` | 绑定 / 查看 Maker 工程 |
| `console_logs` | 看游戏里的 log / warn / error |

### 构建

改完 Maker 代码**必须真构建一次**才知道有没有问题 ——
Lua 是运行时报错的语言，语法检查抓不到大部分问题。

```
maker_build_current_directory
```

构建成功之后预览会自动刷新（App 会自动做，不用你再催）。

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

- [ ] `game_validate` 跑过，没有真实报错
- [ ] `maker_build_current_directory` 构建成功
- [ ] `game_shot` 看过画面（不是白屏 / 错位）
- [ ] 涉及运动的（旋转 / 动画 / 物理）用 `game_shot_motion` 看过
- [ ] 多人工程：确认代码写进了 `network/` 对应文件，不是 `Standalone.lua`
- [ ] 单位是米、数组从 1 开始、NanoVG 挂了 `NanoVGRender`（这三条最容易忘）
