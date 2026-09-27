# 古卷暖褐（scroll）· 写法手册

提炼自真实上线的「大明人生模拟」（真实色值：卷底 #28231E / #322D23 / #3C3228、
淡金 #B49650、亮金 #FFD700、玉绿 #B4DCA0、朱红 #FF5050、字色 #DCC8AA）。
和 `ink` 的差别：ink 是**浅底宣纸**，scroll 是**暗底古卷**（适合夜戏与厚重感）。

## 一、气质三件事

1. **暗底 + 宣纸色字**：底色暗褐，文字 #DCC8AA（偏黄的米色），不要纯白。
2. **小圆角、细金线**：圆角 6px，边框 1px 暗金。圆润和粗描边都破坏古朴感。
3. **只有三种彩色**：暗金（本）、玉绿（民生/正向）、朱红（弹劾/危险）。

## 二、卷宗式信息块

```html
<div class="hx-panel">
  <div class="hx-row hx-row--between">
    <span style="font-weight:700">崇祯元年 · 九月</span>
    <span class="hx-badge hx-badge--dim">正七品·知县</span>
  </div>
  <div class="hx-hr"></div>
  <div class="hx-row hx-row--between">
    <span class="hx-sub">民望</span><span class="hx-num">亲近 · 62</span>
  </div>
  <div class="hx-bar"><i style="width:62%"></i></div>
  <p class="hx-sub" style="margin-top:8px">仓中存粮二百石，够吃到腊月。</p>
</div>
```

- 年份 / 官职 / 数值都靠右对齐，像一份公文抬头。
- 数值用 tabular-nums，列对齐才像账册。
- 长文本（事件叙事）一屏不超过 3 段，关键句单独成行。

## 三、抉择界面

- 选项用【方括号】标动作，例如「【开仓放粮】」，比纯句子更像"可执行的命令"。
- 门槛（需要多少库银/多少圣眷）要**显示在按钮上**，不要让玩家点了才知道不满足。
- 事后点评（comment）用 `.hx-sub` 弱化，与抉择文字形成主次。

## 四、禁忌

- ❌ 纯白文字（要用宣纸色）
- ❌ 大圆角、粗描边、亮色渐变
- ❌ 出现蓝/紫等冷色（这套只有金/绿/红）
- ❌ 现代无衬线大标题
