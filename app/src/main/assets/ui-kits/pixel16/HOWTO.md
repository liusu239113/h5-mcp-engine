# 像素复古（pixel16）· 写法手册

适合像素 / 怀旧 / 红白机 / 街机 / 地牢 / 8bit。核心是**硬**：硬边、零圆角、零平滑。

## 一、铁律

1. **圆角必须为 0**：任何 `border-radius` 都不许出现（`--hx-radius` 已是 0）。
2. **图片关平滑**：`image-rendering: pixelated`（`--hx-img-render` 已配），
   canvas 里要显式写 `ctx.imageSmoothingEnabled = false;`。
3. 字号取**整数倍**（8 / 16 / 24px），等宽字体（`--hx-font` 已配 mono）。
4. 描边用 2px 实线，像老式 UI 边框；不要阴影渐变。

## 二、结构写法

```html
<body class="hx-root">
  <div class="hx-col">
    <div class="hx-panel hx-panel--flat">
      <div class="hx-row hx-row--between">
        <span>LV 7</span><span class="hx-badge">HP 24/40</span>
      </div>
      <div class="hx-bar"><i style="width:60%"></i></div>
    </div>

    <!-- 数值块用等宽对齐，像老游戏的数值面板 -->
    <div class="hx-row" style="gap:8px">
      <span class="hx-badge">ATK 12</span>
      <span class="hx-badge">DEF 07</span>
      <span class="hx-badge hx-badge--dim">LCK 03</span>
    </div>

    <button class="hx-btn hx-btn--primary hx-btn--wide">▶ START</button>
    <button class="hx-btn hx-btn--wide">CONTINUE</button>
  </div>
</body>
```

## 三、细则

- 按钮按下时只做 `translate(1px,1px)` 位移，**不要缩放、不要柔和动效**
  （`--hx-transition` 已调成近乎瞬时）。
- 布局按网格对齐（8px 栅格）：所有 padding / gap 取 8 的倍数。
- 颜色低饱和，别用纯色高饱和；暗底 + 亮字是标配，也可以做浅底深字（GameBoy 味）。
- 汉字在像素风格里很难做，**优先用英文 + 数字**，或者用近似像素字体。
- 音效用方波味（Maker 的 `text_to_sound_effect` 里写 "8-bit square wave beep"）。

## 四、常见错误

- ❌ 圆角、阴影、模糊、渐变
- ❌ 平滑缩放的图片（人物糊成一团就不是像素风了）
- ❌ 非整数倍的缩放（0.75x 之类会让像素错位）
- ❌ 现代无衬线字体 + 大字号标题

## 五、动效代替方案

不要用缓动曲线，用**逐帧 / 硬切换**：
`animation: step 0.24s steps(1) infinite;` —— 阶跃才像老机器。
