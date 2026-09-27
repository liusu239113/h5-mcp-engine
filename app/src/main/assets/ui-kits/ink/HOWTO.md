# 水墨国风（ink）· 写法手册

适合武侠 / 仙侠 / 修仙 / 古代 / 历史 / 门派对战。核心是**留白**与**克制**。

## 一、气质三件事

1. **留白要多**：宁可空，不要塞满。面板之间留 16–20px，一屏最多 2~3 个信息块。
2. **只有一种彩色**：朱红（`--hx-accent`）。其余全是墨色浓淡。多一个颜色就廉价。
3. **字要"写"出来**：标题用衬线（`--hx-font-title` 已配楷/宋），字距 `.06em` 以上，
   像匾额或题跋；正文行高 1.8，段落之间空一行。

## 二、结构写法

```html
<body class="hx-root">
  <div class="hx-col">
    <h1 class="hx-title">青云门</h1>
    <p class="hx-sub">山门积雪未消，钟声荡了三回。</p>
    <div class="hx-hr"></div>

    <div class="hx-panel">
      <div class="hx-row hx-row--between">
        <span>内功修为</span><span class="hx-badge">七成</span>
      </div>
      <div class="hx-bar"><i style="width:70%"></i></div>
      <p class="hx-sub">再练三日，可破关。</p>
    </div>

    <button class="hx-btn hx-btn--primary hx-btn--wide">闭关</button>
    <button class="hx-btn hx-btn--ghost hx-btn--wide">下山</button>
  </div>
</body>
```

## 三、细则

- 面板圆角保持小（`--hx-radius` 已给 6px 左右），**不要用大圆角**，那是卡通风格。
- 按钮默认是"墨框留白"，只有最重的主操作才用朱红。
- 分隔用 `.hx-hr`，不要用色块堆叠。
- 图标优先用**单色**（emoji 也行，但别用彩色卡通图标）。
- 动效要慢：`--hx-transition` 已是缓入缓出，不要加弹跳。
- 不要出现任何霓虹色、渐变光晕、发光文字 —— 那是动漫/科幻风格的东西。

## 四、常见错误

- ❌ 一屏塞 5 个面板 → 变成表格，没有水墨味
- ❌ 用蓝/紫/绿当强调色
- ❌ 大字重（900）+ 大字号的现代风格标题
- ❌ 圆角 20px 以上

## 五、文案建议

用短句、留余韵。「钟声荡了三回」比「播放了三次钟声音效」好一百倍。
战斗结果用对仗句收尾，比「你赢了」有味道。
