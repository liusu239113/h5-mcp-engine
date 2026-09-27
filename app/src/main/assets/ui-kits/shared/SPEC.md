# Hexora UI 风格包 · 使用规范（写游戏 UI 之前必读）

## 一、铁律（违反 = 返工）

1. **UI 用 DOM + CSS，游戏世界才用 canvas。** 按钮、面板、血条、背包、弹窗全部用
   `.hx-*` 组件；canvas 里只画场景、角色、特效。理由：换肤只换 CSS，canvas 画的 UI 换不了。
2. **禁止任何浏览器原生默认样式**：不许出现裸 `<button>`（那个灰色方块）、
   不许用 `alert()` / `confirm()` / `prompt()` 当弹窗、不许让文字用默认 `sans-serif` 且不设字号。
3. **禁止在游戏代码里写死颜色 / 圆角 / 字体**。要什么颜色就用对应的 `var(--hx-*)`。
   确实需要一个主题里没有的颜色（比如某道具固定红色），用 `.hx-badge--danger` 这类语义类，或
   在项目 `ui/theme.css` 末尾追加一个自己的变量，**不要**直接写 `#ff0000`。
4. **引用方式**（文件都在项目内，用相对路径）：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <link rel="stylesheet" href="ui/theme.css">
  <link rel="stylesheet" href="ui/components.css">
</head>
<body class="hx-root">
  <div class="hx-col">
    <h1 class="hx-title hx-title--accent">江湖行</h1>
    <div class="hx-panel">
      <div class="hx-row hx-row--between">
        <span class="hx-sub">气血</span><span class="hx-badge">380 / 500</span>
      </div>
      <div class="hx-bar"><i style="width:76%"></i></div>
      <div class="hx-dialog__foot">
        <button class="hx-btn hx-btn--primary">开始闯荡</button>
        <button class="hx-btn hx-btn--ghost">设置</button>
      </div>
    </div>
  </div>
</body>
</html>
```

## 二、组件清单（照抄类名，别自己发明）

| 用途 | 类名 |
|---|---|
| 页面根（必须） | `hx-root` |
| 竖排 / 横排容器 | `hx-col` / `hx-row`、`hx-row--between` |
| 面板（带四角装饰与阴影） | `hx-panel`、`hx-panel--flat` |
| 标题 / 副文字 | `hx-title`、`hx-title--accent`、`hx-sub`、`hx-hr` |
| 按钮 | `hx-btn`、`hx-btn--primary`、`hx-btn--ghost`、`hx-btn--danger`、`hx-btn--wide` |
| 数值条 | `hx-bar`（内部 `<i style="width:..%">`）、`hx-bar--exp` |
| 背包 / 格子 | `hx-grid`、`hx-slot`、`hx-slot--on`、`hx-slot__count` |
| 徽章 / 页签 | `hx-badge`、`hx-badge--dim`、`hx-tabs`、`hx-tab`、`hx-tab--on` |
| 弹窗 | `hx-mask` > `hx-dialog`（内含 `hx-dialog__foot`） |
| 提示条 | `hx-toast` |
| 虚拟摇杆 | `hx-joystick` > `hx-joystick__knob` |

命中不了的需求，**先往 `ui/components.css` 里加一个 `.hx-*` 类**（用现有变量），再在页面里用。
这样以后再换风格，新组件也自动跟着变。

## 三、可用设计令牌（theme.css 里的 var）

颜色 `--hx-bg` `--hx-panel` `--hx-panel-2` `--hx-border` `--hx-text` `--hx-text-dim`
`--hx-accent` `--hx-accent-2` `--hx-on-accent` `--hx-danger` `--hx-ok`
形状 `--hx-radius` `--hx-radius-sm` `--hx-border-w`
字型 `--hx-font` `--hx-font-title` `--hx-title-ls` `--hx-btn-ls`
其它 `--hx-shadow` `--hx-shadow-btn` `--hx-pad` `--hx-gap` `--hx-transition`

## 四、质量下限（做过游戏的人管这叫"能玩"）

- 有开始界面 → 游玩 → 结算 / 重开，**不是一进来就一堆裸方块**。
- 关键操作有反馈：按下有动效、加分有飘字、失败有提示；有音效（用 Maker 的
  `text_to_sound_effect` / `text_to_music` 生成，放 `assets/audio/`）。
- 进度用 `localStorage` 存，刷新不丢。
- 适配横竖屏与安全区（`hx-root` 已处理 safe-area，别自己再算一遍）。
- 控制台零报错 —— 否则会出现「AI 看到报错去改游戏逻辑」的连锁问题。
- 禁止 `TODO`、禁止占位方块字符（`口口`）、禁止把 demo 当交付。
