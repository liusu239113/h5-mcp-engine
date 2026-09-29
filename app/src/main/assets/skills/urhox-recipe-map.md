# UrhoX / Maker 选型速查（我要做 X，该用什么？）

配套 `urhox-maker-handbook.md` 用。那份讲**规则和陷阱**，这份讲**该往哪走**。

用法：先在下面找到你要做的事，拿到「用什么」和「去哪查」，
再去读对应的专题文档，**不要凭印象写代码**。

---

## 一、常见需求 → 方案

| 我要做… | 用什么 | 去哪查 |
|---|---|---|
| 创建场景 / 节点 | `Scene()`、`scene:CreateChild("Name")` | `engine-docs/api/core.md` |
| 加 3D 模型 | `StaticModel` + `cache:GetResource("Model", …)` | `engine-docs/api/graphics.md` |
| 内置模型没有的形状（半球 / 圆台 / 楔形 / 斜面） | **`CustomGeometry` 程序化生成** | `engine-docs/recipes/procedural-geometry.md` |
| 加 2D 精灵 | `StaticSprite2D` | `engine-docs/api/index.md` |
| 3D 物理 | `RigidBody` / `CollisionShape` | `engine-docs/api/physics.md` |
| 3D 物理**碰撞事件**（地面检测 / 触发器 / 拾取 / 伤害区） | 用事件订阅，不要每帧遍历 | `examples/18-physics-collision-3d.lua` |
| 2D 物理（平台跳跃） | Box2D | `engine-docs/api/physics-2d.md` |
| 体素破坏 / 挖掘 / 载具 | 体素物理系统（独立于 Bullet） | `engine-docs/recipes/voxel-physics.md` |
| 键盘 / 鼠标输入 | `input:GetKeyDown()` 等 + 枚举 | `engine-docs/api/input.md`、`engine-docs/api/enums.md` |
| **游戏 UI / HUD / 菜单** | `urhox-libs/UI`（40+ 控件） | `engine-docs/recipes/ui.md` |
| 自定义图形 / 粒子 / 图表 | **raw NanoVG**（挂 `NanoVGRender`） | `examples/01-nanovg-standalone.lua` |
| 在界面里嵌 3D 场景预览 | 渲染到纹理 | `engine-docs/recipes/scene-to-nanovg.md` |
| 播放音效 / BGM | `Sound` / `SoundSource` | `engine-docs/api/audio.md` |
| 灯光 / 雾 / 天空盒 / 后效（Bloom 等） | LightGroup 预设 + Zone | `engine-docs/recipes/rendering.md` |
| 自定义 shader（溶解 / 流光 / UV 动画 / Toon / 描边） | 上层 **SurfaceShader** | `engine-docs/recipes/surface-shader.md` |
| 第三人称相机 | **`ThirdPersonCamera` 库** | `engine-docs/recipes/camera.md` |
| 角色动画状态机（走跑跳 / 攻击） | FSM + BlendSpace | `engine-docs/recipes/state-machine.md` |
| 动画后处理（武器 / 特效贴骨骼） | `Bone:GetFinalWorldTransform()`，注意时序 | `state-machine.md` → 「后处理骨骼挂点」 |
| JSON 编解码 | **cjson** | `engine-docs/recipes/json.md` |
| 本地存档 | `File` / `FileSystem`（相对路径） | `engine-docs/recipes/file-storage.md` |
| 云变量 / 排行榜（客户端） | `clientCloud` | `engine-docs/recipes/client-cloud-score.md` |
| 云变量 / 排行榜（服务端） | `serverCloud` | `engine-docs/recipes/server-cloud-score.md` |
| 联网多人（同步 / 远程事件） | 权威服务器 + Scene Replication | `engine-docs/recipes/network-game-guide.md` ⚠️必读 |
| 体素联网 | 服务端权威修改 + 客户端预热 | `engine-docs/recipes/voxel-networking.md` |
| 边玩边下 / 手动下资源 | DWP | `engine-docs/recipes/download-while-playing.md` |
| 资源预下载 / 裁剪包体 | 构建引用策略 | `engine-docs/recipes/preload-and-build-refs.md` |
| 多语言：文本 | 提取 → 翻译 → 构建替换 | `engine-docs/recipes/i18n-translation.md` |
| 多语言：图片 / 音频资源 | 文件名加 `@en` 后缀 | `engine-docs/recipes/i18n-resource.md` |
| 视频播放 | `VideoPlayer` / `VideoScreen3D` | `engine-docs/recipes/video.md` |
| Spine 骨骼动画 | `UI.Spine` 组件（3.8 / 4.x） | `engine-docs/recipes/ui.md` § Spine |
| 跑一段 Lua 做程序化生成 / 离线烘焙 | 无头模式 | `engine-docs/recipes/procedural-lua-headless.md` |

---

## 二、挑选脚手架（别从零写）

| 游戏类型 | 脚手架 |
|---|---|
| 2D 休闲（Flappy / Snake 这类） | `templates/scaffold-2d.lua` |
| 2D 平台跳跃（马里奥 / Celeste） | `templates/scaffold-2d-physics.lua` |
| 3D 场景展示 / 可视化（自由相机，无角色） | `templates/scaffold-3d-scene.lua` |
| 3D 角色游戏（Fall Guys / Roblox 风格） | `templates/scaffold-3d-character.lua` |
| 体素沙盒（可破坏 / 载具 / 水面） | `examples/27-voxel-town` |
| 云变量 / 排行榜 | 不需要脚手架，直接看 clientCloud / serverCloud 示例 |

---

## 三、代码放哪里

```
工程根/
├── scripts/        # 玩法代码（入口 Start() 在这里）
├── network/        # 多人：Client.lua / Server.lua / Standalone.lua
├── assets/         # 纹理、声音、模型
└── urhox-libs/     # 引擎工具库（只读，改这里不生效）
```

**多人还是单机，先读 `.project/settings.json`：**

```json
{ "@runtime": { "multiplayer": { "enabled": true } } }
```

| 这个字段 | 模式 | 玩法代码写到 |
|---|---|---|
| `true` | 多人 | `network/Client.lua` + `network/Server.lua` |
| `false` / 不存在 | 单机 | `network/Standalone.lua` |

⚠️ 一旦是 `true`，**之后每个新功能都要按多人做**，不是只有当前这个。

---

## 四、症状 → 解法（出问题先查这张表）

| 症状 | 原因 / 解法 |
|---|---|
| 画面全白 / 空白，什么都不显示 | WASM 多线程要 `crossOriginIsolated`；预览要用支持站点隔离的内核 |
| `attempt to call method 'GetInt'` | `eventData` 用法不对，见手册 §4 |
| `attempt to index a nil value` | 数组从 0 开始遍历了，见手册 §4 |
| `undefined-field`（LSP 报错） | 变量没标类型 / 做了自赋值，见手册 §4 |
| NanoVG 代码跑了但什么也不显示 | 没挂 **`NanoVGRender`** 事件，见手册 §5 |
| NanoVG 图形在、文字不在 | 忘了 `nvgCreateFont`，见手册 §5 |
| 按空格不跳 / 地面检测失效 | Box2D 的碰撞体必须挂在**同一个刚体节点**上，用 `center` 偏移 |
| 鼠标左键点不动 | 用了数字 `0` 而不是 `MOUSEB_LEFT`，见手册 §6 |
| `SetMode` 不生效 | 已禁用，见手册 §3 |
| UI 在不同分辨率下错位 | 用 `UIScaler` 定义设计分辨率，见手册 §3 |
| UI 子元素溢出容器 | Yoga 默认 `flexShrink = 0`，显式写 `flexShrink = 1` |
| 材质变成洋红 / 不显示 | Technique 路径猜错了，只用手册 §8 那三个 |
| 第三人称相机方向反了 | 用 `ThirdPersonCamera` 库，别自己算，见手册 §9 |
| 文件读写被拒 / 返回 nil | 用相对路径 `"save.json"`，见手册 §10 |
| `io` 库不存在 | 已被沙箱移除，用 `File` |
| 动画状态机不切状态 | 查 condition 表达式、参数是否每帧更新 |
| 武器 / 特效不贴手、慢一帧 | AimOffset / 后处理后必须用 `Bone:GetFinalWorldTransform()`，且注意时序 |
| 3D 预览上下颠倒 | 见 `recipes/scene-to-nanovg.md` → 「画面方向」 |
| 包体太大 / 下载慢 | 先看 `preload-and-build-refs.md`（裁剪），再看 `download-while-playing.md` |

---

## 五、开工前的固定动作

1. **先读代码再改**：`game_read` / `code_search` 看清现有结构，别猜文件名。
2. **一次改一小块**，改完立刻热重载看效果。
3. **改了画面相关的，必须截图看**；没看过画面不许说「已完成」。
4. **收尾前查 console 有没有报错**。
5. **面向手机**：竖屏为主、注意安全区、触摸目标 ≥ 44px、不要每帧重建对象。
6. **代码风格**：单文件可跑、零外部依赖、用 Canvas / CSS 动画，不引入大框架。
