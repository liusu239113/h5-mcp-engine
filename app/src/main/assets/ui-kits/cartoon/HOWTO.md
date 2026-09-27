# 卡通圆润（cartoon）· 写法手册

适合休闲 / 三消 / 可爱 / 动物 / 儿童 / 跑酷 / 益智。核心是**大圆角 + 粗描边 + 弹跳**。

## 一、气质三件事

1. **圆角要大**：`--hx-radius` 已给 22px（面板）/ `--hx-radius-sm` 更小一档。
   圆角小于 12px 立刻就不"卡通"了。
2. **粗描边**：`--hx-border-w` 已给 3px，颜色用 `--hx-border`。元素要像贴纸一样有轮廓。
3. **一切都弹**：按下缩放、出现回弹、加分飘字。动效是这套风格的灵魂，不是装饰。

## 二、结构写法

```html
<body class="hx-root">
  <div class="hx-col">
    <div class="hx-panel" style="text-align:center">
      <div style="font-size:56px;line-height:1">🐱</div>
      <h1 class="hx-title">小猫跳跳</h1>
      <div class="hx-row hx-row--between">
        <span class="hx-badge">🍎 12</span>
        <span class="hx-badge hx-badge--dim">⭐ 3</span>
      </div>
    </div>

    <button class="hx-btn hx-btn--primary hx-btn--wide">开始玩</button>
    <button class="hx-btn hx-btn--ghost hx-btn--wide">选关卡</button>
  </div>
</body>
```

## 三、细则

- 文字尽量短，图标优先于文字；一个界面最多两个主色。
- 圆角、描边、内边距都用大值；**不要用直角和小间距**。
- 弹跳动效示例（放在关键按钮/奖励上）：

```css
@keyframes hxPop { 0%{transform:scale(.86)} 60%{transform:scale(1.06)} 100%{transform:scale(1)} }
.hx-pop { animation: hxPop .28s cubic-bezier(.22,1.4,.36,1) both; }
```

- 奖励要给"飘字 + 缩放 + 音效"三件套，只用其一都不够爽。
- 音效用 Maker 的 `text_to_sound_effect`，提示词写 "cute bubbly pop，小清新"。
- 颜色可以高饱和，但**同一屏不超过 2 个主色**，否则花。
- 儿童向要避免细字与小按钮：正文 ≥ 16px，触摸目标 ≥ 48px。

## 四、常见错误

- ❌ 直角、细线、1px 描边
- ❌ 深色压抑配色（这套风格要亮）
- ❌ 死板无动效（点了没反应）
- ❌ 文字太多（儿童向一屏超过 2 行说明就是错的）

## 五、和 comic（动漫绘本）的区别

cartoon 是**圆润 + 高饱和 + 弹跳**；comic 是**纸白 + 墨线 + 硬投影**。
想要"玩具感"选 cartoon，想要"绘本/漫画感"选 comic。