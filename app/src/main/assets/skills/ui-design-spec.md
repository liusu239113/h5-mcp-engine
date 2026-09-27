---
name: game-ui-design
description: |
  项目 UI 设计规范与排版系统。
  Use when: (1) 新增 UI 面板/弹窗/HUD 组件, (2) 修改现有 UI 布局或样式, (3) 设计新功能界面（符文、背包、商店等）, (4) 需要保持视觉一致性的任何 UI 工作。
  触发关键词：UI、界面、面板、弹窗、按钮、HUD、菜单、排版、布局、样式。
---

# 游戏 UI 设计规范

---

## ⚠️ 渲染模式选择（新项目必读）

**UrhoX 提供两种 UI 渲染方式，新项目开始前必须先询问用户选择哪种模式，确认后再进行开发。**

### 两种模式对比

| 维度 | NanoVG Raw（手动绘制） | Yoga UI 组件库（`urhox-libs/UI`） |
|------|----------------------|----------------------------------|
| **抽象层级** | 底层绘图 API（类似 HTML Canvas） | 高层组件系统（类似 React/Flutter） |
| **布局方式** | 手动计算坐标 | Yoga Flexbox 自动布局 |
| **事件处理** | 手动计算命中区域 | 内置 HitTest + 事件冒泡 |
| **代码量** | 大（手动绘制） | 少（声明式描述） |
| **内置控件** | 无，全部手绘 | 42+ 控件 + 7 高级组件 |
| **调试工具** | 无 | **F9 Inspector**（运行时检查 + 实时属性调整） |
| **分辨率适配** | 手动处理 DPR/scale | 框架自动处理（UI.Scale 预设） |
| **主题系统** | 无 | 内置 Material 风格主题 |
| **动画** | 手动实现缓动 | CSS-like Transition + Keyframe Animation |
| **手势** | 无 | 内置手势识别（拖拽、缩放、长按、滑动） |
| **滚动/虚拟列表** | 手动实现惯性+裁剪 | ScrollView + VirtualList（对象池） |
| **适用场景** | 纯 2D 游戏画面、自定义图形、粒子、图表 | 菜单、HUD、背包、聊天、排行榜等标准 UI |

### F9 Inspector（Yoga UI 独有）

Yoga UI 内置运行时 UI 检查器，按 **F9** 激活：
- 冻结游戏，悬停/点击选中控件
- Inspector 面板查看控件路径、源码位置、所有 props
- **实时拖拽调整** width/height/padding/fontSize/opacity 等数值
- 支持多选（Ctrl+点击）、脏控件选择（Ctrl+A）
- "复制给 AI" 导出控件信息辅助调试

### 选择建议

询问用户时，参考以下推荐：

| 场景 | 推荐模式 |
|------|---------|
| 标准游戏 UI（菜单、HUD、弹窗、背包） | **Yoga UI** |
| 纯 NanoVG 2D 游戏（整个画面都用 NanoVG 绘制） | **NanoVG Raw** |
| 已有 NanoVG Raw UI 代码的项目 | **保持 NanoVG Raw** |
| 自定义图形、数据可视化、特殊视觉效果 | **NanoVG Raw** |
| 不确定 | **推荐 Yoga UI**（开发效率高、有 F9 调试） |

### 混用还是单用？

确定主渲染模式后，还需询问用户是否需要混用：

| 方案 | 说明 | 适用场景 |
|------|------|---------|
| **单用 Yoga UI** | 全部 UI 用 Yoga 组件实现 | 标准游戏 UI，无特殊图形需求 |
| **单用 NanoVG Raw** | 全部 UI 手动绘制 | 纯 NanoVG 2D 游戏、已有 NanoVG 代码库 |
| **混用：Yoga UI 主 + NanoVG Raw 辅** | 常规 UI 用 Yoga 组件，特殊效果用 NanoVG | 需要自定义图表、粒子特效、技能圈等 Yoga 组件无法实现的效果 |

**混用时的注意事项**：
- Yoga UI 负责布局和交互（面板、按钮、列表等）
- NanoVG Raw 仅用于 Yoga 组件无法实现的自定义绘制（图表、特效、自定义图形）
- NanoVG 绘制内容通过 `NanoVGRender` 事件渲染，与 Yoga UI 独立
- 避免两套系统处理同一 UI 元素（如同一个按钮不要一半 Yoga 一半 NanoVG）

**询问流程**：
```
1. 询问：选择哪种渲染模式？（NanoVG Raw / Yoga UI）
2. 如果选择 Yoga UI → 追问：是否需要混用 NanoVG 做特殊效果？
3. 确认后开始开发
```

---

## 自定义字体导入

> **上传方式**：素材库不支持直接上传 `.ttf` / `.otf` 字体文件。
> 变通方法：将字体文件后缀加上 `.png`（如 `MyFont.ttf.png`），上传到素材库后，
> 告知 AI 删掉 `.png` 后缀，将该文件作为字体使用。
>
> **⚠️ 版权警告**：**必须使用免费商用字体**，严禁使用非商用授权字体。
> 常见免费商用字体举例：思源黑体、思源宋体、MiSans、阿里巴巴普惠体、
> 霞鹜文楷、LXGW WenKai 等。使用前请确认字体授权协议。

---

# 通用设计规范（两种模式共享）

以下颜色、品质色、字号等设计规范不依赖渲染模式，两种模式均适用。

> **注意**：以下数值从某个项目中提取作为示例。
> 用于其他项目时，应根据该项目实际 UI 配色和设计分辨率替换，保持 Skill 与项目视觉一致。

## 颜色体系

### 品质色（6 级）

| 品质 | 名称 | RGB |
|------|------|-----|
| 1 | 普通 | `181,181,181` |
| 2 | 优质 | `162,255,148` |
| 3 | 稀有 | `114,242,245` |
| 4 | 史诗 | `239,121,255` |
| 5 | 传说 | `255,237,0` |
| 6 | 至臻 | `255,0,0` |

### 主题色

| 用途 | RGB(A) | 说明 |
|------|--------|------|
| 面板深棕 | `113,74,58` | 标题栏背景、属性条、标签背景 |
| 标题深棕 | `117,79,62` | 面板标题文字填充色 |
| 属性文字棕红 | `88,46,45` | 属性名称、正文内容 |
| 属性条米黄 | `207,166,119` | 属性条背景、分隔线 |
| 描边深棕 | `68,45,25` | 文字描边、面板外边框 |
| 描边纯黑 | `0,0,0` | HUD 文字描边 |
| 面板内底米黄 | `245,228,200` | 面板内层背景填充 |
| 金币金黄 | `255,234,0` | 金币数值 |
| 法力青 | `112,230,255` | 法力条、技能发光 |
| 遮罩黑 | `0,0,0,160` | 弹窗背景遮罩 |

## 字号层级

| 层级 | 字号 | 用途 |
|------|------|------|
| 超大标题 | 120 | "暂停"等全屏标题 |
| 大标题 | 80 | 章节名 |
| 中标题 | 55-60 | 面板标题、按钮文字 |
| 正文 | 48-52 | 武器名、波次、品质文字 |
| 小正文 | 38-40 | 属性值、描述、速度按钮 |
| 小字 | 32-36 | 商店描述、标签 |

## 缓动函数

| 函数 | 用途 | 定义 |
|------|------|------|
| `easeOutCubic(t)` | 弹窗淡入、滑入 | `local f = t - 1; return f*f*f + 1` |
| `easeInCubic(t)` | 弹窗关闭 | `return t * t * t` |
| `easeInOutCubic(t)` | 标签页切换指示器 | S 曲线混合 |
| `easeOutBack(t)` | 弹性回弹入场 | overshoot = 1.70158 |
| `easeOutBounce(t)` | 落地弹跳 | 角色入场 |
| `lerp(a, b, t)` | 平滑插值 | `a + (b - a) * t` |

## HUD 入场时序

| 延迟 | 持续 | 缓动 | 元素 |
|------|------|------|------|
| 0s | 1s | easeOutBounce | 角色 |
| 0.7s | 0.5s | easeOutBack | 顶部 UI |
| 0.75s | 0.5s | easeOutBack | 底部背包+血条 |
| 0.85s | 0.45s | easeOutCubic | 左侧按钮 |
| 0.95s | 0.45s | easeOutCubic | 右侧按钮 |

---

# NanoVG Raw 模式专属

> 以下内容仅适用于选择了 **NanoVG Raw** 渲染模式的项目。
> 如果项目使用 Yoga UI，请跳到下方 **Yoga UI 模式专属** 部分。

## 基础参数

- **设计分辨率**：`1080x2400`（竖屏），坐标原点左上角
- **坐标系**：所有 UI 坐标基于设计分辨率，通过 `ScreenToDesign()` 转换输入
- **字体**：统一使用 `"sans"` face
- **渲染**：全部使用 NanoVG，在 `NanoVGRender` 事件中绘制

### 描边宽度参考

| 字号层级 | 描边宽度 |
|---------|---------|
| 超大标题 120 | 6 |
| 大标题 80 | 5 |
| 中标题 55-60 | 6 |
| 正文 48-52 | 5 |
| 小正文 38-40 | 3-4 |
| 小字 32-36 | 3 |

## core.DrawUtil 公共函数

所有 UI 模块的绘制工具统一由 `core.DrawUtil` 提供，**禁止在各模块中重复定义**：

```lua
local DrawUtil = require("core.DrawUtil")
```

### drawTextStroke — 描边文字

```lua
DrawUtil.drawTextStroke(vg, x, y, text, fontSize, align, fr, fg, fb, sw, opts)
```

16 方向环形描边 + 填充，`opts` 为可选表：

| opts 字段 | 类型 | 默认值 | 说明 |
|-----------|------|--------|------|
| `alpha` | number (0-1) | 1.0 | 整体透明度，≤0.01 时提前返回 |
| `italic` | boolean | false | 斜体（nvgSkewX 12°） |
| `strokeColor` | {r,g,b} | {0,0,0} | 自定义描边颜色（默认黑色） |

```lua
-- 基础调用
drawTextStroke(vg, x, y, text, 48, NVG_ALIGN_CENTER, 255, 255, 255, 5)

-- 棕色描边（最常用模式）
drawTextStroke(vg, x, y, "标题", 50, align, 255, 255, 255, 5,
    { strokeColor = { 0x59, 0x32, 0x19 } })

-- 斜体 + 半透明
drawTextStroke(vg, x, y, "+12% 暴击率", 38, align, 162, 255, 148, 3,
    { italic = true, alpha = 0.8 })
```

向后兼容包装：

```lua
DrawUtil.drawTextStrokeAlpha(vg, x, y, text, fontSize, align, fr, fg, fb, sw, alpha)
-- 等价于 drawTextStroke(..., { alpha = alpha })
```

### drawImageCentered — 中心坐标图片绘制

```lua
DrawUtil.drawImageCentered(vg, img, cx, cy, w, h, alpha)
```

以 `(cx, cy)` 为中心绘制图片，内部使用 `nvgImagePattern`。

### drawNineSlice — 九宫格绘制

```lua
DrawUtil.drawNineSlice(vg, img, dx, dy, dw, dh, iTop, iRight, iBottom, iLeft)
```

| 参数 | 说明 |
|------|------|
| `dx, dy, dw, dh` | 目标矩形（左上角坐标 + 宽高） |
| `iTop, iRight, iBottom, iLeft` | 内边距（CSS 顺序：上右下左） |

**实现细节**：
- 通过 `nvgImageSize` 获取源纹理尺寸
- 使用 `OV = 1` 像素重叠消除拼缝
- 渲染期间关闭抗锯齿 `nvgShapeAntiAlias(vg, false)` 防止接缝伪影
- 中间切片尺寸为零时回退为简单拉伸

```lua
local drawNineSlice = require("core.DrawUtil").drawNineSlice
drawNineSlice(vg, panelBg, px, py, pw, ph, 60, 60, 60, 60)  -- 面板背景
drawNineSlice(vg, btnImg, bx, by, bw, bh, 20, 20, 20, 20)   -- 按钮
```

### drawRoundedRectCentered — 中心坐标圆角矩形

```lua
DrawUtil.drawRoundedRectCentered(vg, cx, cy, w, h, r, rr, gg, bb, aa)
```

### hitTest — 中心坐标点击检测

```lua
DrawUtil.hitTest(px, py, cx, cy, w, h) → boolean
```

### easeOutBack — 缓动函数

```lua
DrawUtil.easeOutBack(t) → number   -- overshoot = 1.70158
```

## 斜体规范

- **实现方式**：`nvgSkewX`（水平切变 12°），**不使用 rotate**
- **常量**：`ITALIC_SKEW = -math.tan(math.rad(12))`（约 -0.2126）
- **原理**：以文字锚点为中心做 skew 变换，描边 + 填充在同一变换空间内，保证描边贴合
- **适用场景**：属性加成数值、词缀描述、Buff 提示等需要强调但不加粗的文本

手动斜体模式（当 drawTextStroke 不满足需求时）：

```lua
nvgSave(vg)
nvgTranslate(vg, x, y)
nvgSkewX(vg, -math.tan(math.rad(12)))
nvgTranslate(vg, -x, -y)
-- 绘制文字...
nvgRestore(vg)
```

## 弹窗标准模板

所有弹窗遵循统一结构：

```
1. 黑色遮罩 rgba(0,0,0,160)
2. 面板背景图（居中 x=540），通常用 drawNineSlice 绘制
3. 标题文字（面板顶部偏下）
4. 内容区域（列表/网格/表单）
5. 底部按钮组
```

### 开关动画

```lua
-- 打开：0.25秒
local t = easeOutCubic(elapsed / 0.25)
local scale = 0.85 + 0.15 * t    -- 从 85% 缩放到 100%
nvgGlobalAlpha(nvg, t)            -- 淡入

-- 关闭：反向
local t = 1 - easeOutCubic(elapsed / 0.25)
```

## 按钮规范

| 类型 | 典型尺寸 | 文字字号 | 描边 |
|------|----------|----------|------|
| 主按钮 | 290x195 | 55 | 6px 深棕 |
| 长按钮 | 386x195 | 55 | 6px 深棕 |
| 圆形按钮 | 98x98 或 134x134 | 40 | 5px |
| 关闭按钮 | 134x134 | - | - |

按钮浮动动画：振幅 8px，频率 3Hz。
入场动画：0.3s easeOutBack 缩放。

## 渐变与发光效果

### 渐变类型

| API | 用途 | 示例 |
|-----|------|------|
| `nvgLinearGradient` | 按钮背景渐变 | 蓝色上→深蓝下 |
| `nvgRadialGradient` | 点状发光（弹道头部、命中闪光） | 中心亮 → 边缘透明 |
| `nvgBoxGradient` | 矩形外发光（签到格子高亮） | 内侧亮 → 外侧透明 |

**nvgBoxGradient 外发光示例**：

```lua
local expand = GLOW_SIZE
nvgBeginPath(vg)
nvgRect(vg, cx - halfW - expand, cy - halfH - expand,
    w + expand * 2, h + expand * 2)
local bg = nvgBoxGradient(vg,
    cx - halfW, cy - halfH, w, h,
    4, GLOW_SIZE,
    nvgRGBA(glowR, glowG, glowB, GLOW_ALPHA),
    nvgRGBA(glowR, glowG, glowB, 0))
nvgFillPaint(vg, bg)
nvgFill(vg)
```

### 发光实现方式

| 方式 | 适用场景 |
|------|---------|
| 图片发光 + 正弦呼吸 | 按钮/光环的周期呼吸 |
| 图片发光 + 旋转 | 奖励/结算发光背景 |
| nvgBoxGradient | 矩形元素外发光 |
| nvgRadialGradient | 点状/圆形发光 |
| 多层描边衰减 | 连线/路径发光 |

**图片呼吸发光**：

```lua
local glowAlpha = 0.7 + 0.3 * math.sin(elapsed * 2.0)
drawImageCentered(vg, imgGlow, cx, cy, w, h, glowAlpha)
```

**多层描边发光**（连线/路径）：

```lua
for layer = GLOW_LAYERS, 1, -1 do
    local t = layer / GLOW_LAYERS
    local alpha = glowBaseAlpha * (1.0 - t)
    local w = LINE_WIDTH + LINE_GLOW_WIDTH * 2 * t
    nvgStrokeWidth(vg, w)
    nvgStrokeColor(vg, nvgRGBA(glowR, glowG, glowB, math.floor(alpha + 0.5)))
    nvgStroke(vg)
end
```

### drawImageRotated — 旋转图片绘制

```lua
local function drawImageRotated(vg, img, cx, cy, w, h, angle, alpha)
    nvgSave(vg)
    nvgTranslate(vg, cx, cy)
    nvgRotate(vg, angle)
    local paint = nvgImagePattern(vg, -w*0.5, -h*0.5, w, h, 0, img, alpha)
    nvgBeginPath(vg)
    nvgRect(vg, -w*0.5, -h*0.5, w, h)
    nvgFillPaint(vg, paint)
    nvgFill(vg)
    nvgRestore(vg)
end
```

## 裁剪与嵌套裁剪

### 基础裁剪 — nvgScissor

所有滚动列表、进度条均使用 `nvgScissor` 裁剪可见区域。

### 嵌套裁剪 — nvgIntersectScissor

当裁剪区域需要嵌套时（外层面板 + 内层滚动列表），使用 `nvgIntersectScissor` 取交集：

```lua
nvgSave(vg)
nvgIntersectScissor(vg, ox, oy, w, h)
-- 绘制内容...
nvgResetScissor(vg)
nvgRestore(vg)
```

## 图片资源管理

### ImageCache — 共享纹理缓存

`ui/ImageCache.lua` 提供共享 NanoVG 图片缓存，带 FIFO 淘汰（最大 64 张）：

```lua
local ImageCache = require("ui.ImageCache")
local handle = ImageCache.get(vg, "Textures/equip_sword.png")
-- 内部自动调用 nvgCreateImage / nvgDeleteImage 管理 VRAM
```

### 图片加载

- 全局 `images_` 表，由 `EnsureImages(nvg)` 初始化
- 所有 `nvgCreateImage` 调用使用 flag `0`（无特殊标志）
- 中心坐标绘制统一使用 `DrawUtil.drawImageCentered`

### 品质着色

```lua
local paint = nvgImagePattern(nvg, x, y, w, h, 0, img.handle, 1.0)
paint.innerColor = nvgRGBA(qualityR, qualityG, qualityB, alpha)
nvgFillPaint(nvg, paint)
```

## 常用组件模式

### 进度条（血条/经验条/法力条）

```lua
nvgSave(vg)
nvgScissor(vg, barX, barY, barW * percent, barH)
-- 绘制完整宽度的填充图/颜色（仅可见部分显示）
nvgRestore(vg)
```

血条附带残影效果：红色半透明层延迟跟随。

### 网格布局

```lua
local col = (i - 1) % cols
local row = math.floor((i - 1) / cols)
local x = gridLeft + col * cellSpacingX + cellSpacingX / 2
local y = gridTop + row * cellSpacingY + cellSpacingY / 2
```

典型配置：5 列，格子 124-180px，间距 12-20px。

### 滚动列表

```lua
-- 1. scissor 裁剪可见区域
nvgScissor(nvg, clipX, clipY, clipW, clipH)
-- 2. 所有内容 Y 坐标减去 scrollY
local drawY = baseY - scrollY
-- 3. 跳过屏幕外项目
if drawY + itemH < clipY or drawY > clipY + clipH then goto continue end
-- 4. 惯性滚动：0.92 衰减，dt*20 速度系数，0.5 阈值停止
```

### 属性双列布局

- 属性条背景：`448x54`，圆角 9，颜色 `207,166,119`
- 左列 X=285，右列 X=747
- 属性名：字号 38，棕红 `88,46,45`，居中
- 属性值：字号 38，白色，右对齐

### 阴影效果

偏移矩形阴影（如气泡框）：

```lua
nvgBeginPath(vg)
nvgRoundedRect(vg, bx + shadowOff, by + shadowOff, bubbleW, bubbleH, radius)
nvgFillColor(vg, nvgRGBA(0, 0, 0, math.floor(alpha * 0.18)))
nvgFill(vg)
```

## NanoVG Raw 检查清单

新增 NanoVG UI 时确认：

- [ ] 坐标基于设计分辨率
- [ ] 弹窗有黑色遮罩 + 缩放淡入动画
- [ ] 字号和描边符合层级规范
- [ ] 品质颜色使用 6 级标准色
- [ ] 描边文字使用 `DrawUtil.drawTextStroke`（不要重复定义）
- [ ] 面板/按钮背景使用 `DrawUtil.drawNineSlice`（不要重复定义）
- [ ] 图片使用 `DrawUtil.drawImageCentered`（中心坐标）
- [ ] 进度条使用 scissor 裁剪
- [ ] 滚动列表有惯性和裁剪
- [ ] 缓动函数从 DrawUtil 引用（不要本地重复定义）
- [ ] 嵌套裁剪使用 `nvgIntersectScissor`
- [ ] 自定义字体已确认免费商用授权

---

# Yoga UI 模式专属

> 以下内容仅适用于选择了 **Yoga UI** 渲染模式的项目。
> 如果项目使用 NanoVG Raw，请参考上方 **NanoVG Raw 模式专属** 部分。
>
> 完整文档：`engine-docs/recipes/ui.md`、`examples/14-ui-widgets-gallery.lua`

## 初始化

```lua
local UI = require("urhox-libs/UI")

UI.Init({
    fonts = {
        { family = "sans", weights = { normal = "Fonts/MiSans-Regular.ttf" } }
    },
    scale = UI.Scale.DEFAULT,
})

local root = UI.Panel {
    width = "100%", height = "100%",
    children = { ... }
}
UI.SetRoot(root)

-- 在 Stop() 中清理
function Stop()
    UI.Shutdown()
end
```

## Flexbox 布局

Yoga 使用 Flexbox 布局，与 CSS Flexbox 类似但有关键差异：
- `flexShrink` 默认 **0**（不收缩，CSS 默认 1）
- `flexDirection` 默认 **"column"**（CSS 默认 row）
- 盒模型始终 **border-box**

```lua
-- 水平行布局
UI.Panel {
    flexDirection = "row",
    justifyContent = "spaceBetween",
    alignItems = "center",
    gap = 10,
    children = { ... }
}

-- 网格布局（自动换行）
UI.Panel {
    flexDirection = "row",
    flexWrap = "wrap",
    gap = 8,
    children = {
        UI.Panel { width = "30%", height = 100 },
        UI.Panel { width = "30%", height = 100 },
        UI.Panel { width = "30%", height = 100 },
    }
}

-- 弹性填充
UI.Panel {
    flexDirection = "row",
    width = "100%",
    children = {
        UI.Panel { width = 200 },                      -- 固定宽度
        UI.Panel { flexGrow = 1, flexShrink = 1 },     -- 填充剩余空间
    }
}
```

> ⚠️ `flexShrink` 默认 0，子元素溢出容器时不会自动收缩，需手动设置 `flexShrink = 1`。

## 文本样式

```lua
-- 基础文本
UI.Label {
    text = "Hello World",
    fontSize = 24,
    fontColor = { 255, 255, 255, 255 },
    fontWeight = "bold",            -- "normal" / "bold"
    textAlign = "center",           -- left / center / right
}

-- 描边 + 阴影
UI.Label {
    text = "Outlined",
    fontSize = 32,
    fontColor = { 255, 255, 255, 255 },
    textStroke = { width = 2, color = { 0, 0, 0, 255 } },
    textShadow = { offsetX = 2, offsetY = 2, blur = 4, color = { 0, 0, 0, 128 } },
}

-- 高级样式
UI.Label {
    text = "Advanced",
    fontSize = 18,
    maxLines = 2,                   -- 超出省略
    lineHeight = 1.5,
    letterSpacing = 1.2,
    textDecoration = "underline",   -- underline / lineThrough / none
}
```

## 按钮

```lua
-- 预设变体
UI.Button { text = "确认", variant = "primary",   onClick = function(self) end }
UI.Button { text = "取消", variant = "secondary", onClick = function(self) end }
UI.Button { text = "删除", variant = "danger",    onClick = function(self) end }
UI.Button { text = "成功", variant = "success",   onClick = function(self) end }

-- 自定义样式（无 size prop，用 height/fontSize/padding 控制）
UI.Button {
    text = "小按钮",
    height = 32,
    fontSize = 12,
    paddingHorizontal = 8,
    onClick = function(self) end,
}

-- 自定义颜色
UI.Button {
    text = "自定义",
    backgroundColor = { 255, 128, 0, 255 },
    hoverBackgroundColor = { 255, 160, 50, 255 },
    pressedBackgroundColor = { 200, 100, 0, 255 },
    textColor = { 255, 255, 255, 255 },
    onClick = function(self) end,
}
```

默认属性：`borderRadius=8`、`paddingHorizontal=16`、`flexShrink=0`。

## 弹窗（Modal）

```lua
-- 基础弹窗
local modal = UI.Modal {
    title = "设置",
    size = "md",                -- sm / md / lg / xl / fullscreen
    closeOnOverlay = true,
    closeOnEscape = true,
    showCloseButton = true,
    onClose = function() end,
}
modal:AddContent(UI.Label { text = "内容" })
modal:SetFooter(UI.Button {
    text = "确定",
    variant = "primary",
    onClick = function() modal:Close() end,
})
modal:Open()

-- 快捷确认框
UI.Modal.Confirm({
    title = "删除确认",
    message = "确定要删除吗？",
    confirmText = "删除",
    cancelText = "取消",
    onConfirm = function() end,
})

-- 快捷提示框
UI.Modal.Alert({
    title = "提示",
    message = "操作完成",
})
```

内置动画：0→1 lerp（速度 8），缩放 0.9→1.0 + 遮罩淡入。

## 图片显示与九宫格

```lua
-- 基础图片
UI.Panel {
    width = 256, height = 256,
    backgroundImage = "Textures/player.png",
    backgroundFit = "contain",      -- fill / contain / cover
}

-- 九宫格（NinePatch）
UI.Panel {
    width = 300, height = 200,
    backgroundImage = "Textures/panel_border.png",
    backgroundSlice = { top = 10, right = 10, bottom = 10, left = 10 },
    padding = 16,
    children = {
        UI.Label { text = "九宫格面板内容" },
    }
}

-- 图片着色
UI.Panel {
    width = 128, height = 128,
    backgroundImage = "Textures/icon.png",
    backgroundFit = "contain",
    imageTint = { 115, 115, 115, 255 },
}
```

## 进度条

```lua
-- 基础进度条
UI.ProgressBar {
    value = 0.7,
    variant = "primary",            -- primary / success / warning / error
    showLabel = true,               -- 显示百分比文字
    transition = "value 0.3s easeOut",  -- 数值变化平滑过渡
}

-- 自定义颜色
UI.ProgressBar {
    value = 50,
    max = 100,
    fillColor = { 0, 200, 100, 255 },
    height = 12,
    borderRadius = 6,
}

-- 渐变填充
UI.ProgressBar {
    value = 0.8,
    fillGradient = {
        direction = "horizontal",
        from = { 0, 128, 255, 255 },
        to = { 0, 255, 128, 255 },
    },
}
```

## 滚动列表

```lua
-- 基础滚动视图
UI.ScrollView {
    scrollY = true,
    showScrollbar = true,
    bounces = true,
    flexGrow = 1,
    flexBasis = 0,              -- ⚠️ 与 flexGrow=1 配合时必须设置
    children = {
        UI.Label { text = "Item 1" },
        UI.Label { text = "Item 2" },
        -- ...
    }
}
```

> 大数据量（1000+ 项）使用 `UI.VirtualList`（对象池 + 视图回收），
> 参考 `examples/16-virtual-list-10k-items.lua`。

## 动画与过渡

```lua
-- CSS-like 属性过渡（属性变化时自动触发）
local box = UI.Panel {
    width = 100, height = 100,
    backgroundColor = { 100, 100, 255, 255 },
    opacity = 1.0,
    transition = "all 0.3s easeOut",
    -- 也可指定单个属性：transition = "opacity 0.2s easeInOut"
    -- 多个属性：transition = "opacity 0.2s, scale 0.3s easeOut"
}

-- 触发过渡（修改样式属性以触发 transition 动画）
box:SetStyle({ opacity = 0.5 })
box:SetStyle({ scale = 1.2 })
box:SetStyle({ backgroundColor = { 255, 0, 0, 255 } })

-- 关键帧动画（程序化控制）
box:Animate({
    keyframes = {
        { time = 0,   scale = 1.0, opacity = 1.0 },
        { time = 0.5, scale = 1.5, opacity = 0.5 },
        { time = 1,   scale = 1.0, opacity = 1.0 },
    },
    duration = 1.0,
    easing = "easeInOut",
    loop = true,
    direction = "alternate",
})
```

可动画属性：`opacity`、`scale`、`rotate`、`translateX`、`translateY`、`borderRadius`、`borderWidth`、`shadowBlur`、`backgroundColor`、`borderColor`、`fontColor`。

## 主题系统

```lua
-- 获取主题值
local primary = UI.Theme.Color("primary")       -- {r,g,b,a}
local spacing = UI.Theme.Spacing("md")           -- number
local radius  = UI.Theme.Radius("lg")            -- number

-- 颜色 keys: primary, secondary, success, warning, error,
--   background, surface, text, textSecondary, border, overlay
-- Spacing keys: xs, sm, md, lg, xl
-- Radius keys: none, sm, md, lg, xl, full

-- 自定义主题
local myTheme = UI.Theme.ExtendTheme(UI.Theme.GetTheme(), {
    colors = {
        primary = { 0, 128, 255, 255 },
        background = { 20, 20, 30, 255 },
    },
})
```

## 属性双列布局（Yoga UI 版）

```lua
UI.Panel {
    flexDirection = "row",
    width = "100%",
    padding = 8,
    gap = 12,
    children = {
        -- 左列
        UI.Panel {
            flexGrow = 1, flexShrink = 1,
            children = {
                UI.Label { text = "攻击力", fontSize = 38, fontColor = { 88, 46, 45, 255 } },
            }
        },
        -- 右列
        UI.Panel {
            flexGrow = 1, flexShrink = 1,
            children = {
                UI.Label { text = "1280", fontSize = 38, fontColor = { 255, 255, 255, 255 }, textAlign = "right" },
            }
        },
    }
}
```

## Yoga UI 检查清单

新增 Yoga UI 时确认：

- [ ] 已调用 `UI.Init()` 并设置字体和 scale
- [ ] 使用 Flexbox 布局（不手动计算坐标）
- [ ] 弹窗使用 `UI.Modal`（不手动绘制遮罩）
- [ ] 按钮使用 `UI.Button { variant }`（不手动绘制）
- [ ] 进度条使用 `UI.ProgressBar`
- [ ] 滚动列表使用 `UI.ScrollView`（大数据用 `UI.VirtualList`）
- [ ] `flexGrow = 1` 时同时设置 `flexBasis = 0`
- [ ] 子元素溢出时检查 `flexShrink = 1`
- [ ] 品质颜色使用 6 级标准色
- [ ] 动画使用 `transition` 或 `Animate()`（不手动计算缓动）
- [ ] 九宫格使用 `backgroundSlice`（无需设置 backgroundFit）
- [ ] 自定义字体已确认免费商用授权
- [ ] 调试时使用 F9 Inspector 检查布局
