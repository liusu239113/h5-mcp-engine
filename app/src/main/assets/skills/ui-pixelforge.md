# UI 风格 · PixelForge（像素 / 复古 / 8-bit 街机）

**什么时候用**：用户说「像素 / 复古 / 8-bit / 街机 / 红白机 / 怀旧」，
或者游戏本身就是像素画风，UI 也要跟着像素化。

**H5 和 Maker 都能用**：
· H5 → 下面那套 CSS 变量直接用
· Maker（UrhoX）→ 色值同样有效，配 `urhox-libs/UI` 的组件样式

⚠️ 原版文档里的 `UI.Theme.*` 是 Maker 的 Lua API，H5 工程里没有，**别照抄**。

---

## 设计 DNA（6 条）

1. **圆角 = 0。永远。** 这是像素风的第一识别点 ——
   组件代码里**不该出现 `border-radius`**（唯一例外：日历翻页那种小按钮用 2px）。
2. **2px 内描边** —— 每个可交互元素都有一条 2px 的**内嵌**描边，颜色比填充色深一档。
3. **硬投影（零模糊）** —— 不同组件用不同偏移，营造"块状"的像素立体感：
   · 按钮 3px + 左上角 1px 高光斜角
   · 菜单/下拉/toast 3px
   · 卡片 4px；模态框 4px（更黑）
   · 抽屉是横向 4px
4. **深黑底** —— `#0F0F23` 打底、`#1B1B3A` 做面板。
   对比度拉高，像素边缘才会"跳"出来。
5. **高饱和强调色** —— 青绿 `#21BDAE`、紫 `#6C5CE7`、红 `#FF4757`。饱和、大胆。
6. **像素字体** —— 正文用像素字体；数字/代码用等宽像素体。

---

## 色板

```css
:root {
  --primary: #21BDAE;        --primary-hover: #3DD0C1;   --primary-pressed: #19A899;
  --secondary: #6C5CE7;      --secondary-hover: #8577ED; --secondary-pressed: #5A4BD6;

  --background: #0F0F23;     /* 深黑底 */
  --surface: #1B1B3A;        /* 面板 */
  --surface-hover: #252550;  /* 抬升 */
  --disabled: #2A2A4A;

  --text: #F0F0F0;
  --text-secondary: #A0A0C0;
  --text-disabled: #505070;

  --border: #3A3A6A;
  --border-focus: #21BDAE;
  --overlay: rgba(0,0,0,.71);

  --success: #50C878;  --warning: #FFD93D;  --error: #FF4757;  --info: #45AAF2;

  /* 硬投影专用色 */
  --shadow-ink: rgba(10,10,26,.80);

  --sp-xs:4px; --sp-sm:8px; --sp-md:12px; --sp-lg:16px; --sp-xl:24px; --sp-xxl:32px;
  --fs-h1:24px; --fs-h2:20px; --fs-h3:18px; --fs-body:14px; --fs-comp:13px; --fs-small:12px;
}
```

---

## 骨架

```css
body {
  margin: 0; height: 100vh; overflow: hidden;
  background: var(--background);
  color: var(--text);
  /* 像素字体优先；没有就退回等宽 —— 等宽比无衬线更像像素 */
  font-family: "Fusion Pixel", "Zpix", ui-monospace, "SFMono-Regular", monospace;
  /* 关键：关掉抗锯齿，像素图才不会被糊掉 */
  image-rendering: pixelated;
  -webkit-font-smoothing: none;
  -webkit-user-select: none; user-select: none;
  -webkit-tap-highlight-color: transparent;
}

/* 卡片：直角 + 4px 硬投影（规则 1/3） */
.card {
  background: var(--surface);
  border: 2px solid var(--border);        /* 规则 2：内描边感 */
  border-radius: 0;                       /* 规则 1：绝不能圆 */
  padding: var(--sp-lg);
  box-shadow: 4px 4px 0 var(--shadow-ink);
}

/* 按钮：3px 投影 + 左上 1px 高光斜角（像素立体感的来源） */
.btn {
  border: 0; border-radius: 0;
  padding: var(--sp-md) var(--sp-xl);
  font-family: inherit; font-size: var(--fs-h3);
  color: #06231E;
  background: var(--primary);
  box-shadow:
    3px 3px 0 var(--shadow-ink),                    /* 右下投影 */
    inset -2px -2px 0 var(--primary-pressed),       /* 右下内描边（深一档） */
    inset  1px  1px 0 rgba(255,255,255,.19);        /* 左上高光 */
  transition: none;                                /* 像素风不要过渡动画 */
}
.btn:active {
  transform: translate(3px, 3px);
  box-shadow: 0 0 0 var(--shadow-ink), inset -2px -2px 0 var(--primary-pressed);
}
.btn--secondary { background: var(--secondary); color: #fff; box-shadow: 3px 3px 0 var(--shadow-ink), inset -2px -2px 0 var(--secondary-pressed); }
.btn--danger    { background: var(--error);     color: #fff; box-shadow: 3px 3px 0 var(--shadow-ink), inset -2px -2px 0 #B8323F; }

h1 { font-size: var(--fs-h1); margin: 0 0 var(--sp-md); letter-spacing: 0; }
h2 { font-size: var(--fs-h2); margin: 0 0 var(--sp-sm); }
p  { font-size: var(--fs-body); color: var(--text-secondary); margin: 0 0 var(--sp-sm); }
```

**如果游戏是像素画**，canvas 也要关插值，否则放大就糊：

```js
ctx.imageSmoothingEnabled = false;
```

---

## 常见做坏的地方

| 症状 | 原因 |
|---|---|
| 不像像素风 | 出现了圆角 —— 必须全是直角 |
| 边缘糊、不锐利 | canvas 没关 `imageSmoothingEnabled`，或字体有抗锯齿 |
| 立体感不对 | 用了模糊阴影 —— 像素风必须**零模糊**、纯偏移 |
| 按钮没"厚度" | 少了内描边或高光斜角 |
| 整体发灰 | 底色不够深 —— 要用 `#0F0F23` 这种深黑，对比才够 |

---

## 验证

调 `game_shot` 看一眼：**是不是全直角**、阴影**是不是零模糊的硬块**、
**像素边缘锐不锐**（有没有被插值糊掉）。这三条对了就立住了。
