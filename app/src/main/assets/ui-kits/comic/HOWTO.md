# 动漫绘本（comic）· 写法手册

这套风格的写法提炼自一款真实上线的动漫风 H5 游戏（它把样式全写成内联 `style`）。
下面把它「为什么好看」的原因拆成能照抄的规则 —— **照着写就能像，凭感觉写就会跑偏**。

---

## 一、视觉三件套（每个元素都逃不出这三样）

```
白底 (#ffffff)  +  2px 墨线描边  +  硬投影 (2.5px 2.5px 0)
```

- **硬投影**：`box-shadow: 2.5px 2.5px 0 rgba(26,36,28,.8)` —— 注意第三个值是 `0`，
  **没有模糊半径**。它让元素像贴在纸上的一层贴纸，这就是整套风格的签名。
  一旦改成 `0 4px 12px rgba(0,0,0,.3)` 这种软阴影，立刻变成普通小游戏界面。
- **描边**：`2px solid rgba(26,36,28,.8)`。颜色不是纯黑，是「墨绿黑」，
  纯黑会显得脏、没有手绘感。
- 元素必须**同时**有描边和硬投影，只给一个就会显得是半成品。

```html
<div class="hx-panel">…</div>
<!-- 面板类已内置三件套；自己造新组件时也照这个配 -->
```

## 二、文字

| 位置 | 字号 | 字重 | 说明 |
|---|---|---|---|
| 页面主标题 | 26–32px | 900 | 用 `.hx-title`，自带金色描边阴影 |
| 卡片标题 | 17–19px | 800 | 黑字，可配一个 emoji 当图标 |
| 正文 | 14–15px | 600 | 行高 1.7，段间距 ≥ 10px |
| 次要信息 | 12–13px | 500 | 用 `.hx-sub`（自动用 `--hx-text-dim`） |

- 字重**普遍偏重**（600 起步，标题 900）—— 这是这套风格最容易被忽略的点，
  字太细会立刻失去「手绘 / 漫画」的味道。
- 标题加 `text-shadow: 1.5px 1.5px 0 rgba(232,193,90,.55)`（金色描边），已经内置在 `.hx-title`。
- **不要**用系统默认 `sans-serif` 且不设字号。

## 三、按钮

```html
<!-- 主操作：绿底白字（唯一一个「重」的按钮，一屏只放一个） -->
<button class="hx-btn hx-btn--primary">开始冒险</button>

<!-- 次操作：白底墨字（这才是默认态） -->
<button class="hx-btn">查看详情</button>

<!-- 弱操作/返回：幽灵按钮 -->
<button class="hx-btn hx-btn--ghost">返回</button>
```

- 按钮按下时必须「压扁」：`transform: translate(2px,2px)` 同时 `box-shadow: 0 0 0`。
  主题已内置 `.hx-btn:active`，自己造按钮记得也加。
- 按钮内文字 `letter-spacing: .04em`，带点呼吸感。
- 按钮高度 ≥ 44px（触摸目标），圆角 10–12px。

## 四、配色比例（配错了就不像了）

| 颜色 | 令牌 | 占比 | 用在哪 |
|---|---|---|---|
| 纸白 | `--hx-panel` `#fff` | ~60% | 所有面板、卡片、按钮底 |
| 墨线绿黑 | `--hx-ink` | ~25% | 描边、文字、投影 |
| 草木绿 | `--hx-accent` `#2e8a45` | ~10% | 主按钮、进度条、强调数值 |
| 暖金 | `--hx-gold` `#e8c15a` | ~5% | 奖励、稀有、星级、高光 —— **只做点缀** |
| 墨夜 | `--hx-dark` `#241f16` | 局部 | 夜戏、深色弹窗、对话条 |

> 金色一旦大面积铺（比如整个面板刷成金色），廉价感立刻上来。它只能是「星星点点」。

## 五、图标

原仓库用一个 `ICO()` 函数统一产出图标（内联 SVG / emoji），**不要引任何图标库**：

```html
<!-- 最省事：emoji 直接写在按钮/标题里 -->
<button class="hx-btn">🌿 采集</button>

<!-- 要矢量就内联 SVG，颜色用 currentColor 跟着文字走 -->
<span class="hx-ico" style="width:20px;height:20px;display:inline-block">
  <svg viewBox="0 0 24 24" width="20" height="20">
    <path d="M12 3l2.6 6.2 6.4.5-4.9 4.1 1.5 6.3L12 16.7 6.4 20.1l1.5-6.3L3 9.7l6.4-.5z"
          fill="currentColor" stroke="rgba(26,36,28,.8)" stroke-width="1.5"/>
  </svg>
</span>
```

## 六、常见页面的骨架

```html
<body class="hx-root">
  <div class="hx-col">

    <!-- 顶部：标题 + 资源（金币/体力用 badge） -->
    <div class="hx-row hx-row--between">
      <h1 class="hx-title">小森林物语</h1>
      <span class="hx-badge">🪙 1,280</span>
    </div>
    <div class="hx-hr"></div>

    <!-- 主内容：一张一张的卡片，卡片之间留 12px -->
    <div class="hx-panel">
      <div class="hx-row hx-row--between">
        <span style="font-weight:800">第 3 章 · 初遇</span>
        <span class="hx-badge hx-badge--dim">进行中</span>
      </div>
      <p class="hx-sub">今天天气很好，森林里飘着淡淡的光。</p>
      <div class="hx-bar"><i style="width:62%"></i></div>
      <div class="hx-dialog__foot">
        <button class="hx-btn hx-btn--primary hx-btn--wide">继续</button>
      </div>
    </div>

    <!-- 选择支：三个白底墨线按钮竖排，主推的那个用 primary -->
    <button class="hx-btn hx-btn--wide">🌿 帮她采药</button>
    <button class="hx-btn hx-btn--wide">🗡 直接问清楚</button>
    <button class="hx-btn hx-btn--wide">👀 假装没看见</button>

  </div>
</body>
```

- 页面节奏：面板之间 `--hx-gap`(12px)，面板内边距 `--hx-pad`(14px)。
- 一屏**只有一个 primary 按钮**，其余全是白底。
- 弹窗用 `.hx-mask` > `.hx-dialog`，**禁止** `alert()`。
- 反馈用 `.hx-toast`，别让点击没反应。

## 七、禁忌清单

- ❌ 软阴影 / 模糊阴影（风格立刻失效）
- ❌ 纯黑描边 `#000`、纯灰背景 `#ddd`
- ❌ 裸 `<button>`（浏览器那个灰方块）
- ❌ `alert() / confirm() / prompt()`
- ❌ 大面积金色、大面积绿色
- ❌ 字重低于 500 的正文
- ❌ 圆角超过 12px（圆润是 cartoon 那套的风格，不是这套）
- ❌ 在游戏代码里写死颜色 —— 一律 `var(--hx-*)`

## 八、深色场景（夜戏 / 对话）

```html
<div class="hx-panel" style="background:var(--hx-dark);color:#fff;border-color:var(--hx-ink)">
  <p style="font-weight:800">「这么晚了，你还要上山？」</p>
  <p class="hx-sub" style="color:rgba(255,255,255,.7)">她的声音在夜里格外清楚。</p>
</div>
```

深色块**仍然保留墨线描边 + 硬投影**，这样它和白色卡片是「同一套纸片」，
而不是突然插进来一块别的设计语言。
