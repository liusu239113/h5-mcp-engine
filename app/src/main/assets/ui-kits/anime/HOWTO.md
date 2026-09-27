# 动漫玻璃（anime）· 写法手册

适合二次元 / 卡牌 / 养成 / 剧情 / 乙女 / 文字冒险。核心是**深色渐变 + 毛玻璃 + 冷光**。

## 一、气质三件事

1. **底要深、要渐变**：`--hx-bg-image` 已是深色斜向渐变。别用纯白底，那不是这套风格。
2. **面板是毛玻璃**：`--hx-blur` 已配 `blur(14px)`，配 `--hx-panel` 的半透明白。
   背景放角色立绘时，毛玻璃的效果才出得来。
3. **冷光描边**：`--hx-accent` 是冷光蓝，高光走 `--hx-shadow`（大半径柔光）。

## 二、结构写法

```html
<body class="hx-root">
  <div class="hx-col">

    <!-- 卡面：立绘 + 玻璃信息层 -->
    <div class="hx-panel" style="text-align:center">
      <img src="assets/image/card_hero.png" alt=""
           style="width:100%;border-radius:12px;margin-bottom:10px">
      <h1 class="hx-title hx-title--accent">安洁莉卡</h1>
      <p class="hx-sub">「这次，我不会再放开你的手。」</p>
      <div class="hx-row hx-row--between">
        <span class="hx-badge">★ 5</span>
        <span class="hx-badge hx-badge--dim">火 / 剑士</span>
      </div>
    </div>

    <!-- 剧情进度 -->
    <div class="hx-panel">
      <div class="hx-row hx-row--between">
        <span>好感度</span><span class="hx-badge">62 / 100</span>
      </div>
      <div class="hx-bar"><i style="width:62%"></i></div>
    </div>

    <button class="hx-btn hx-btn--primary hx-btn--wide">与她对话</button>
    <button class="hx-btn hx-btn--ghost hx-btn--wide">返回主界面</button>
  </div>
</body>
```

## 三、细则

- **文字必须压在面板上**：别把文字裸着压立绘，会看不清。用 `.hx-panel` 包一层。
- 稀有度用两档色：`--hx-accent`（高星/稀有）与 `--hx-danger`（限定/危险）。
- 数值 / 星级用 `.hx-badge`，不要新造样式。
- 立绘建议用 Maker 的 `generate_image` 生成，提示词写明 "anime style, transparent/solid dark background"，
  放到 `assets/image/`。
- 动效走"淡入 + 轻微上浮"，不要弹跳（弹跳是 cartoon 那套的）。
- 弹窗用 `.hx-mask`（已配深色遮罩），禁止 `alert()`。

## 四、常见错误

- ❌ 纯白底（毛玻璃必须压在有内容的深色背景上）
- ❌ 面板不透明（那就成了普通深色 UI，没有"玻璃"质感）
- ❌ 高饱和大色块
- ❌ 文字直接压立绘

## 五、和 comic（动漫绘本）的区别

anime 是**深色 + 毛玻璃 + 冷光**（现代、战斗、卡牌）；
comic 是**纸白 + 墨线 + 硬投影**（绘本、漫画、日常）。两者都是动漫题材，但气质相反，
选了就不要混用两套的要素。