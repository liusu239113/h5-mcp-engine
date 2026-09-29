# UrhoX / Maker 引擎手册（写 Maker 工程前必读）

这份手册是给 AI 看的「保命规则」：下面每一条都是**踩过坑总结出来的**，
违反其中任意一条，代码要么直接报错，要么跑起来是错的（而且往往不报错，只是画面不对）。

适用场景：工程里出现 `scripts/main.lua`、`urhox-libs/`、`.project/`，或者你正在用
`maker_build_current_directory` / `mak_*` 这类工具 —— 那就是 UrhoX / Maker 工程，
**必须**先读完这份手册再动手。

---

## 0. 五条最容易犯的错（先看这个）

| # | 错误 | 后果 |
|---|---|---|
| 1 | 用 `graphics:SetMode()` 改分辨率 | **已禁用**，调用无效。见 §3 |
| 2 | 长度按「像素」写，比如把角色写成 100 高 | 单位是**米**，100 米高的角色会飞出屏幕。见 §1 |
| 3 | 数组从 0 开始遍历 | Lua 数组从 **1** 开始，`arr[0]` 是 `nil` → `attempt to index a nil value`。见 §4 |
| 4 | 用 raw NanoVG 做菜单 / HUD / 字幕 | 会和 UI 层抢渲染层级，闪烁 / 被盖住。UI 一律用 `urhox-libs/UI`。见 §7 |
| 5 | 猜材质的 Technique 路径 | 程序化材质**只有**那三个路径，猜错就是一片洋红或者不显示。见 §8 |

---

## 1. 世界尺度与坐标系

**长度单位是米（meter）。** 所有坐标、距离、模型尺寸都是米。

典型尺度，照这个量级写：

| 东西 | 数值 |
|---|---|
| 角色身高 | 1.5 ~ 2.0 |
| 移动速度 | 5.0 m/s |
| 跳跃初速度 | 7.0 m/s |
| 重力 | 一般用引擎默认，不要自己拍一个 9.8 |
| 一层的层高 | 3.0 |

**坐标系 = Y-up 左手系，和 Unity 完全一样**：

- X 向右（RIGHT），Y 向上（UP），Z 向前（FORWARD）
- Yaw（偏航）绕 **Y** 轴转 → 左右转头
- Pitch（俯仰）绕 **X** 轴转 → 抬头低头

```lua
Vector3.UP       -- (0, 1, 0)
Vector3.FORWARD  -- (0, 0, 1)
Vector3.RIGHT    -- (1, 0, 0)

node.position = Vector3(0, 5, 10)      -- 上方 5 米、前方 10 米
Quaternion(yaw, Vector3.UP)            -- 水平旋转
Quaternion(pitch, Vector3.RIGHT)       -- 垂直旋转
```

> 别把 Z 当成「向上」，也别把 Pitch 挂到 Y 轴上 —— 那是别的引擎的习惯。

---

## 2. 模型尺寸：绝不猜

用内置模型时，**必须**确认尺寸再用：

```lua
-- ✅ 方法一：动态量（推荐，永远不会错）
local model = node:CreateComponent("StaticModel")
model:SetModel(cache:GetResource("Model", "Models/Box.mdl"))
local size = model.boundingBox.size
node.position = Vector3(0, size.y / 2, 0)   -- 底面贴地

-- ❌ 方法二：拍脑袋
node.position = Vector3(0, 0.5, 0)          -- Box 对，Torus 就错了
```

常用内置模型：`Models/Box.mdl` 是 **1×1×1**。其余的一律用 `boundingBox` 量。

**内置模型没有的形状，用 `CustomGeometry` 程序化生成**，不要去网上找模型：
半球、圆台 / 截锥、楔形、斜面 —— 这些都用它现造。

```lua
local geom = node:CreateComponent("CustomGeometry")
geom:BeginGeometry(0, TRIANGLE_LIST)
geom:DefineVertex(Vector3(x, y, z))
geom:DefineNormal(Vector3(nx, ny, nz))
geom:DefineTexCoord(Vector2(u, v))
geom:Commit()
geom:SetMaterial(material)   -- 顶点是按 TRIANGLE_LIST 顺序三个一组三角化的
```

---

## 3. 分辨率：`SetMode()` 已禁用

**`graphics:SetMode()` 调了没用**，不要再写。拿屏幕信息用这三个：

```lua
local physW, physH = graphics:GetWidth(), graphics:GetHeight()  -- 物理分辨率
local dpr = graphics:GetDPR()                                   -- 设备像素比 1.0/2.0/3.0
local logicalW, logicalH = physW / dpr, physH / dpr             -- 系统逻辑分辨率
```

选哪种布局：

| 情况 | 用什么 |
|---|---|
| 有明确设计分辨率（1920×1080 这种） | 按设计分辨率布局 + 缩放 |
| 没明确（**默认走这条**） | 系统逻辑分辨率 + 响应式布局 |
| 想用物理分辨率 | **别用**，高 DPI 屏上 UI 会小到点不着 |

引擎提供 `UIScaler` 可以做设计分辨率缩放，优先用它而不是自己乘系数。

---

## 4. Lua 5.4 语法与陷阱

- **版本是 Lua 5.4**，支持位运算 `&` `|` `~` `<<` `>>`。
- **数组从 1 开始**。`for i = 1, n do`，不是 `for i = 0, n-1`。
  算出来的下标要兜底：`math.max(1, idx)`。
- `io` 库**已被沙箱移除**，读写文件用 `File`（见 §10）。

### eventData 的访问方式（tolua++ 绑定，两种都对）

```lua
---@param eventType string
---@param eventData UpdateEventData
function HandleUpdate(eventType, eventData)
    local dt = eventData["TimeStep"]:GetFloat()   -- ✅ 标准写法
    local dt = eventData:GetFloat("TimeStep")     -- ✅ 更高效，linter 也更准
end
```

字段名查 `.emmylua/Events.d.lua`，**不要猜**。

### `table.unpack` 在表构造器里的坑

```lua
local items = {1, 2, 3}

local t = { table.unpack(items), "extra" }   -- ❌ 结果 {1, "extra"}，只展开了第一个
local t = { "header", table.unpack(items) }  -- ✅ { "header", 1, 2, 3 }
```

**只有放在表构造器的最后一个位置才会完全展开。** 这点和 JS 的 `[...arr, x]` 不一样。

### 类型标注（LSP 会当错误报）

- 声明了但没赋值的变量**必须**标类型，否则访问成员报 `undefined-field`：

```lua
---@type Scene
local scene = nil
---@type Node
local node = nil
```

- 别对引擎对象或整数做 `x = x <运算> ...` 这种自赋值（静态分析会丢类型）；
  运算结果落新变量，或者直接写目标属性：`node.position = node.position + delta`。
- 想要 integer 就必须来源也是 integer；数组用 `V[]`，数字键 map 用 `table<number, V>`，
  **不要写 `table<integer, V>`**。

---

## 5. 事件与渲染事件

**NanoVG 绘制必须挂在 `NanoVGRender` 事件上**，写在 Update 里画不出来：

```lua
function Start()
    SubscribeToEvent("NanoVGRender", "HandleNanoVGRender")
end

function HandleNanoVGRender(eventType, eventData)
    nvgBeginFrame(vg, width, height, 1.0)
    -- 绘制...
    nvgEndFrame(vg)
end
```

**NanoVG 文字必须先建字体**，而且 `nvgCreateFont` **只在初始化调一次**：

```lua
-- Start() 里，只调一次
fontNormal = nvgCreateFont(vg, "sans", "Fonts/MiSans-Regular.ttf")

-- 渲染时每帧调用没问题
nvgFontFace(vg, "sans")
nvgFontSize(vg, 24)
nvgText(vg, 100, 100, "Hello")
```

> 每帧调 `nvgCreateFont` 会**显存泄漏**。文字不显示 = 十有八九忘了建字体。

给图片 / 纹理叠色染色，raw NanoVG 要用 **`nvgImagePatternTinted`**
（上游的 `nvgImagePattern` 不支持染色）。

---

## 6. 鼠标与输入

**枚举值一律用常量，不要用数字**（别的框架的手感在这里会翻车）：

```lua
-- ❌ 错：数字常量
if button == 0 then ... end

-- ✅ 对
if button == MOUSEB_LEFT then ... end
if input:GetKeyDown(KEY_SPACE) then ... end
if input:GetKeyPress(KEY_ESCAPE) then ... end
```

常用枚举：

| 类别 | 值 |
|---|---|
| 鼠标键 | `MOUSEB_LEFT` / `MOUSEB_MIDDLE` / `MOUSEB_RIGHT` |
| 键盘 | `KEY_SPACE` `KEY_ESCAPE` `KEY_RETURN` `KEY_A`…`KEY_Z` |
| 鼠标模式 | `MM_ABSOLUTE` / `MM_RELATIVE` / `MM_FREE` |
| 刚体 | `BT_STATIC` / `BT_DYNAMIC` / `BT_KINEMATIC` |

**鼠标模式**——引擎默认显示光标：

| 游戏类型 | 设置 |
|---|---|
| 菜单 / RTS / 俯视 | `MM_ABSOLUTE`（默认，不用动） |
| FPS / TPS / 飞行 | `input.mouseMode = MM_RELATIVE`（自动隐藏并锁定光标） |

全部枚举查 `engine-docs/api/enums.md`。

---

## 7. UI：用库，不要手搓

引擎有**两套** UI，原生那套**已废弃**：

| 系统 | 状态 | 什么时候用 |
|---|---|---|
| `urhox-libs/UI` | ✅ **推荐** | 菜单、HUD、按钮、字幕、任何文字界面 |
| 原生 UIElement | ⛔ 废弃 | 只有维护老代码时才碰 |
| raw NanoVG | ⚠️ 仅限 | **自定义图形**：粒子、图表、特殊效果、纯 NanoVG 的 2D 游戏 |

判断口诀：**「UI / HUD / 字幕」→ UI 组件库；「画图形」→ raw NanoVG。**
不确定就先翻 `urhox-libs/UI` 的控件列表，没有对应控件再上 NanoVG。

```lua
local UI = require("urhox-libs/UI")

UI.Init({ theme = "default-dark", scale = UI.Scale.DEFAULT })

UI.SetRoot(UI.Panel {
    width = "100%", height = "100%",
    justifyContent = "center", alignItems = "center",
    children = {
        UI.Label { text = "开始游戏", fontSize = 24 },
        UI.Button { text = "Start", variant = "primary",
                    onClick = function(self) startGame() end },
    }
})
```

### 事后更新控件的两种写法

- **模式 A（少量动态元素，推荐）**：留住 `local` 引用直接调方法
  `local btn = UI.Button{...}; btn:SetDisabled(true)`
- **模式 B（HUD 多元素 / 跨函数）**：给控件 `id`，用 `parent:FindById("id")` 找。
  ⚠️ `FindById` 返回的是 `Widget|nil`，调子类方法前**必须**收窄类型，否则 LSP 报错：
  `local hp = parent:FindById("hp") --[[@as Label?]]`

### Yoga 布局

UI 用 Flexbox。**Yoga 默认 `flexShrink = 0`** —— 子元素超出容器不会自动缩，
要它缩就显式写 `flexShrink = 1`。

### UI 风格

引擎带多套预置主题（科幻 / 格斗 / 像素等风格包）。**想做有风格的 UI 时先查有没有
对应的风格 skill，不要自己编一套配色。**

---

## 8. 材质：程序化材质只用三个 Technique

**不要猜 Technique 路径。** 纯色 / 无贴图的材质只有这几个合法值：

```lua
"Techniques/PBR/PBRNoTexture.xml"        -- 不透明 PBR
"Techniques/PBR/PBRNoTextureAlpha.xml"   -- 透明 PBR
"Techniques/NoTextureUnlit.xml"          -- 无光照
```

内置 Technique 做不到的效果（溶解、流光、UV 动画、顶点形变、水面波纹、卡通描边、
自定义 `light()`），**改写上层 SurfaceShader**，不要手写底层 `.glsl` / Technique XML。

---

## 9. 相机

- **正交相机**：`camera.orthoSize` 是**视野全高度**，但引擎内部用 `orthoSize * 0.5`
  当半高度。手动把屏幕坐标换算到视图空间时要乘 0.5：

```lua
local viewX = ndcX * aspect * orthoSize * 0.5
local viewY = ndcY * orthoSize * 0.5
```

  典型场景：俯视 / 等距视角的「缩放到鼠标位置」。

- **第三人称相机必须用 `ThirdPersonCamera` 库**，不要自己算相机位置（符号很容易搞反）：

```lua
require "urhox-libs.Camera.ThirdPersonCamera"

local tpCamera_ = ThirdPersonCamera.Create(scene_, {
    modes = { normal = { distance = 5.0, offset = Vector3(0, 1.7, 0), fov = 45.0 } },
})
renderer:SetViewport(0, Viewport:new(scene_, tpCamera_:GetCamera()))

-- PostUpdate 里更新，yaw / pitch 直接传进去，库内部处理位置
tpCamera_:Update(timeStep, characterNode, yaw, pitch)
```

---

## 10. 资源路径与文件读写

`scripts/` 和 `assets/` 都已经被配成**资源根目录**，引用时**从下一级开始，不写目录名**：

```lua
-- 文件在 assets/Textures/player.png
cache:GetResource("Texture2D", "Textures/player.png")   -- ✅
cache:GetResource("Texture2D", "assets/Textures/player.png")  -- ❌ 多写了前缀
```

依赖引用：

```lua
require "urhox-libs.Platform.InputManager"   -- ✅ 引擎工具库
require "Utils.Helper"                       -- ✅ 自己的模块 scripts/Utils/Helper.lua
```

**本地存档**用 `File`（`io` 库已被移除），路径用相对路径：

```lua
-- ✅ "save.json" 或 "saves/slot1.json"
-- ❌ 绝对路径会被沙箱拒绝 / 返回 nil
```

---

## 11. 释放对象

所有 `Object` 子类（`Node` / `Component` / `File` / `VideoPlayer` / `Sound` …）
都有 `Dispose()`，用来**立刻**释放，别拖到 GC：

```lua
node:Dispose()
```

频繁创建销毁的东西（子弹、特效）尤其要显式 `Dispose()`，不然就是内存一路涨。

---

## 12. 动手前的检查清单

1. 工程是什么类型？有 `scripts/main.lua` 或 `urhox-libs/` → Maker / UrhoX 工程，
   入口是 `scripts/main.lua` 里的 `Start()`。
2. **联网还是单机？** 必须先读 `.project/settings.json` 的 `@runtime.multiplayer.enabled`：
   - `true` → 多人模式，代码要写进 `network/Client.lua` / `network/Server.lua`
   - `false` 或不存在 → 单机，写 `network/Standalone.lua`
   这个开关**同时决定发布后的运行模式**，一旦是 `true`，后面每个功能都要按多人来做。
3. 有现成脚手架就用脚手架，不要从零写：
   - 2D 休闲 → `scaffold-2d.lua`
   - 2D 平台跳跃（物理） → `scaffold-2d-physics.lua`
   - 3D 场景展示（自由相机） → `scaffold-3d-scene.lua`
   - 3D 角色（跑跳类） → `scaffold-3d-character.lua`
4. 物理 / 碰撞**先在注释里画出碰撞体示意图**（形状、尺寸、检测目的、边界情况），
   设计不清楚就别写代码。
5. 单文件超过 **1500 行必须拆模块**。

---

## 13. 相关技能包

同目录下还有：

- `urhox-recipe-map.md` —— 「我要做 X，该用什么 / 怎么做」的速查表
- `ui-design-spec.md` —— UI 设计规范（配色 / 字号 / 动效）
- `pixel-art-generator.md` —— 像素美术生成

**遇到具体问题先去查这几份，比翻文档快。**
