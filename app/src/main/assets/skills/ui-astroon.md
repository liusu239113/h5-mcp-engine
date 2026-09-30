# UI 风格 · Astroon（宇宙 / 太空 / 霓虹渐变）

**什么时候用**：用户说「宇宙风 / 太空 / 星际 / 霓虹 / 赛博 / 科幻」，
或者你要做一款暗色、发光、有未来感的游戏 UI。

**已转成 H5/CSS** —— 原版是 Maker 的 Lua API（`UI.Theme.Color("primary")`），
这里全部换成 CSS 变量，直接能用。**别去写 `UI.Theme.*`，那是另一个引擎的 API。**

---

## 设计 DNA（8 条，照做就对了）

1. **背景必须用渐变** —— `#1A1140 → #2D1B69` 的宇宙紫渐变。
   **永远不要用纯色平铺**，这是这套风格最容易被做坏的地方。
2. **按钮 = 渐变填充 + 胶囊圆角 + 同色外发光**。**按钮不加边框**。
3. **圆角分级用**：`sm 6` / `md 10` / `lg 16` / `xl 20` / `pill 9999`，
   按组件角色选，不要全用一个尺寸。
4. **金色 = 主 CTA** —— 一屏**最多一个**金色按钮。绿色确认、蓝色次要、红色危险。
5. **文字三级**：标题纯白、正文 67% 白、弱化 25% 白。
6. **字体统一**：一套无衬线（Inter / 系统默认）走到底，别混字体。
7. **面板分层**：基础面板 `#2A1F5E`，抬升面板 `#3D2A8A`，
   配一点白色透明描边 + 深色阴影做分离。
8. **稀有度配色**：蓝（稀有）/ 紫（史诗）/ 金（传说），各带渐变底 + 发光。

---

## 色板（CSS 变量，直接用）

```css
:root {
  /* 主色 —— 金 */
  --primary: #FFD54F;
  --primary-hover: #FFE066;
  --primary-pressed: #F0A030;

  /* 次色 —— 蓝 */
  --secondary: #4A8BF5;
  --secondary-hover: #5B9CF6;
  --secondary-pressed: #3366CC;

  /* 背景 / 面板 */
  --background: #1A1140;      /* 页面底（配合渐变用） */
  --surface: #2A1F5E;         /* 卡片 / 面板 */
  --surface-hover: #3D2A8A;   /* 抬升 / hover */
  --disabled: #3D2A8A;

  /* 文字（三级） */
  --text: #FFFFFF;
  --text-secondary: rgba(255,255,255,.67);
  --text-disabled: rgba(255,255,255,.25);

  /* 描边 / 遮罩 */
  --border: rgba(255,255,255,.09);
  --border-focus: #4A8BF5;
  --overlay: rgba(0,0,0,.71);

  /* 语义色 */
  --success: #2ECC71;
  --warning: #FFD93D;
  --error: #FF4757;
  --info: #3DD6E8;

  /* 间距 */
  --sp-xs: 4px;  --sp-sm: 8px;  --sp-md: 12px;
  --sp-lg: 16px; --sp-xl: 24px; --sp-xxl: 32px;

  /* 圆角 */
  --r-sm: 6px; --r-md: 10px; --r-lg: 16px; --r-xl: 20px; --r-pill: 9999px;

  /* 字号 */
  --fs-h1: 28px; --fs-h2: 22px; --fs-h3: 18px;
  --fs-body: 14px; --fs-comp: 13px; --fs-small: 12px; --fs-cap: 10px;

  /* 宇宙渐变 */
  --cosmic: linear-gradient(160deg, #1A1140 0%, #2D1B69 100%);
}
```

---

## 骨架（照这个起手）

```html
<style>
  * { box-sizing: border-box; }
  body {
    margin: 0; height: 100vh; overflow: hidden;
    background: var(--cosmic);              /* ← 规则 1：渐变，不是纯色 */
    color: var(--text);
    font-family: Inter, system-ui, -apple-system, sans-serif;
    -webkit-user-select: none; user-select: none;
    -webkit-tap-highlight-color: transparent;
  }

  /* 面板：分层 + 白色透明描边（规则 7） */
  .panel {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--r-lg);
    padding: var(--sp-lg);
    box-shadow: 0 8px 24px rgba(0,0,0,.35);
  }
  .panel--raised { background: var(--surface-hover); }

  /* 按钮：渐变 + 胶囊 + 发光，无边框（规则 2） */
  .btn {
    border: 0; cursor: pointer;
    padding: var(--sp-md) var(--sp-xl);
    border-radius: var(--r-pill);
    font-size: var(--fs-h3); font-weight: 700;
    color: #1A1140;                          /* 金色底上用深色字，对比才够 */
    background: linear-gradient(180deg, var(--primary-hover), var(--primary));
    box-shadow: 0 0 20px rgba(255,213,79,.45);   /* 同色外发光 */
    transition: transform .08s, box-shadow .15s;
  }
  .btn:active { transform: scale(.96); box-shadow: 0 0 10px rgba(255,213,79,.35); }

  /* 一屏只留一个主按钮（规则 4）；次要的用蓝色 */
  .btn--secondary {
    color: #fff;
    background: linear-gradient(180deg, var(--secondary-hover), var(--secondary));
    box-shadow: 0 0 20px rgba(74,139,245,.40);
  }
  .btn--danger {
    color: #fff;
    background: linear-gradient(180deg, #FF6B7A, var(--error));
    box-shadow: 0 0 20px rgba(255,71,87,.40);
  }
  .btn--ghost {
    color: var(--text);
    background: transparent;
    border: 1px solid var(--border);
    box-shadow: none;
  }

  /* 文字三级（规则 5） */
  h1 { font-size: var(--fs-h1); margin: 0 0 var(--sp-md); }
  h2 { font-size: var(--fs-h2); margin: 0 0 var(--sp-sm); }
  p  { font-size: var(--fs-body); color: var(--text-secondary); margin: 0 0 var(--sp-sm); }
  .muted { color: var(--text-disabled); font-size: var(--fs-small); }
</style>
```

---

## 稀有度（规则 8）

```css
.rarity-rare      { background: linear-gradient(180deg,#4A8BF5,#2D5FB0); box-shadow: 0 0 14px rgba(74,139,245,.5); }
.rarity-epic      { background: linear-gradient(180deg,#A855F7,#6D28D9); box-shadow: 0 0 14px rgba(168,85,247,.5); }
.rarity-legendary { background: linear-gradient(180deg,#FFE066,#F0A030); box-shadow: 0 0 18px rgba(255,213,79,.6); }
```

---

## 常见做坏的地方

| 症状 | 原因 |
|---|---|
| 看着不像这套风格 | 背景用了纯色（必须渐变） |
| 按钮很平、没有未来感 | 忘了 `box-shadow` 外发光 |
| 一屏好几个金按钮 | 违反规则 4 —— 金色只有一个 |
| 字全糊在一起 | 没分三级，正文应该 67% 白 |
| 按钮文字看不清 | 金色底配了白字 —— 要用深色字（`#1A1140`） |

---

## 验证

做完调 `game_shot` 截图看一眼：**背景是不是渐变**、按钮有没有发光、
一屏是不是只有一个金按钮。这三条对了，风格就立住了。
