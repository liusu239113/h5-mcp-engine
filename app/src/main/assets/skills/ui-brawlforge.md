# UI 风格 · BrawlForge（竞技 / 美式卡通 / 硬边 HUD）

**什么时候用**：用户说「竞技 / 对战 / 美式 / 卡通 / 潮酷 / 战斗 / 硬核」，
或者你要做一款**力量感强、边缘硬朗、配色鲜艳**的游戏 UI。

**H5 和 Maker 都能用**：
· H5 → 下面那套 CSS 变量直接用
· Maker（UrhoX）→ 色值同样有效，用 `urhox-libs/UI` 的组件时把这些值传进 `theme` 或组件样式

⚠️ 原版文档写的是 `UI.Theme.Color("primary")` 那种 Lua API，**别照抄** ——
H5 工程里没有那个对象。下面已经转成两端都能用的形式。

---

## 设计 DNA（8 条）

1. **硬边** —— 外框和按钮 **圆角 = 0**。不要圆角，要像冲压金属板。
2. **描边系统** —— 黑色外描边（`#0A1020`）定结构；
   底部用更深一档的同色描边做厚度感；彩色描边（primary / borderFocus）表示状态。
3. **硬阴影** —— 阴影**模糊度 = 0**，纯偏移：面板/按钮 `(6,6)`，toast `(8,8)`。
   这是这套风格的关键，用模糊阴影立刻就不像了。
4. **蓝色层叠** —— 背景 `#2259B7` / 面板 `#21458A` / 抬升 `#2D66C8`，
   层与层之间用深黑描边分开。所有东西都坐在蓝色的层上。
5. **强调色阶** —— 主色是亮青蓝，次色是亮紫，各带三档（默认 / hover / pressed）+ 内嵌深色描边。
6. **粗体字** —— 全部 **bold**，没有细体。字号档：24 / 20 / 18 / 16 / 14 / 12 / 10。
7. **状态色 + HUD 色** —— 成功/警告/错误/信息，以及 HP/MP/体力/经验条各有专用色。
8. **状态系统** —— 每个组件都要有 默认 / hover / 按下 / 聚焦 / 禁用 五态；
   聚焦用 `#6FE7FF` 青色发光。

---

## 色板

```css
:root {
  --primary: #1FA2FF;        --primary-hover: #46B7FF;   --primary-pressed: #0D7EE6;
  --secondary: #D635FF;      --secondary-hover: #E061FF; --secondary-pressed: #B523E8;

  --background: #2259B7;     /* 底 */
  --surface: #21458A;        /* 面板 */
  --surface-hover: #2D66C8;  /* 抬升 */
  --disabled: #39476B;

  --text: #FFFFFF;
  --text-secondary: #D5E2FF;
  --text-disabled: #9DA6C6;

  --border: #0A1020;         /* 黑色硬描边 —— 这套风格的骨架 */
  --border-focus: #6FE7FF;   /* 聚焦青色发光 */
  --overlay: rgba(7,16,28,.73);

  --success: #43D52C;  --warning: #FFC61A;  --error: #F5322D;  --info: #46C7FF;

  --sp-xs:4px; --sp-sm:8px; --sp-md:12px; --sp-lg:16px; --sp-xl:24px; --sp-xxl:32px;
  --fs-h1:24px; --fs-h2:20px; --fs-h3:18px; --fs-body:16px; --fs-comp:14px; --fs-small:12px;
}
```

---

## 骨架

```css
body {
  margin: 0; height: 100vh; overflow: hidden;
  background: var(--background);
  color: var(--text);
  font-family: "Noto Sans SC", Inter, system-ui, sans-serif;
  font-weight: 700;                 /* 规则 6：全 bold */
  -webkit-user-select: none; user-select: none;
  -webkit-tap-highlight-color: transparent;
}

/* 面板：硬边 + 黑描边 + 零模糊阴影（规则 1/2/3） */
.panel {
  background: var(--surface);
  border: 3px solid var(--border);
  border-radius: 0;                              /* 规则 1：不圆 */
  padding: var(--sp-lg);
  box-shadow: 6px 6px 0 rgba(0,0,0,.25);         /* 规则 3：模糊 = 0 */
}
.panel--raised { background: var(--surface-hover); }

/* 按钮：硬边 + 底部厚度 + 零模糊阴影 */
.btn {
  border: 3px solid var(--border);
  border-radius: 0;
  padding: var(--sp-md) var(--sp-xl);
  font-size: var(--fs-h3); font-weight: 700;
  color: #fff;
  background: var(--primary);
  /* 底部深色描边做厚度（规则 2） */
  border-bottom-color: var(--primary-pressed);
  box-shadow: 6px 6px 0 rgba(0,0,0,.25);
  transition: transform .06s;
}
.btn:active {
  transform: translate(3px, 3px);                /* 按下去 = 往阴影方向位移 */
  box-shadow: 3px 3px 0 rgba(0,0,0,.25);
}
.btn:focus-visible { border-color: var(--border-focus); }

.btn--secondary { background: var(--secondary); border-bottom-color: var(--secondary-pressed); }
.btn--danger    { background: var(--error); }
.btn--ghost     { background: var(--surface); color: var(--text); }
.btn:disabled   { background: var(--disabled); color: var(--text-disabled); }

h1 { font-size: var(--fs-h1); margin: 0 0 var(--sp-md); }
h2 { font-size: var(--fs-h2); margin: 0 0 var(--sp-sm); }
p  { font-size: var(--fs-body); font-weight: 700; color: var(--text-secondary); margin: 0 0 var(--sp-sm); }
```

---

## HUD 条（规则 7）

```css
.hud { height: 14px; border: 2px solid var(--border); background: #0A1020; }
.hud > i { display: block; height: 100%; transition: width .15s; }
.hud--hp  > i { background: var(--error); }
.hud--mp  > i { background: var(--primary); }
.hud--xp  > i { background: var(--warning); }
.hud--sta > i { background: var(--success); }
```

---

## 常见做坏的地方

| 症状 | 原因 |
|---|---|
| 看着像普通卡通 UI | 用了圆角 —— 这套必须 `border-radius: 0` |
| 软塌塌的、没有力量感 | 用了模糊阴影 —— 必须 `blur = 0`，纯偏移 |
| 层次糊在一起 | 没加黑色描边（`#0A1020`） |
| 字太细 | 违反规则 6，全站 `font-weight: 700` |
| 按下去没反馈 | 少了 `translate(3px,3px)` 位移 |

---

## 验证

调 `game_shot` 看一眼：**是不是全直角**、阴影是不是**硬边无模糊**、
字是不是**全粗体**。这三条对了就立住了。
