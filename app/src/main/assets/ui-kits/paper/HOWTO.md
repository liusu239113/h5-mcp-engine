# 手绘童话（paper）· 写法手册

提炼自真实上线的「手绘看图猜梗」游戏（它把 50 多种颜色写成一张 COLORS 常量表）。
核心是**暖纸底 + 手绘描线 + 圆润**，一切都要"软"。

## 一、三件套

```
奶油纸黄底 (#fff7de)  +  2px 橙棕描线 (rgba(173,106,44,.55))  +  落地感阴影 (0 4px 0 .22)
```

- 描线**不要用黑**：用橙棕，才不会显得脏。
- 阴影不是"浮起来"而是"落下去"：`0 4px 0 rgba(173,106,44,.22)`，小偏移、无模糊。
- 卡片、按钮、弹窗全是这套。

## 二、结构写法

```html
<body class="hx-root">
  <div class="hx-col">
    <h1 class="hx-title">一起来猜谐音</h1>
    <p class="hx-sub">看图 → 想梗 → 选字卡</p>

    <div class="hx-panel" style="text-align:center">
      <div style="font-size:20px;font-weight:800;margin-bottom:8px">上：认物</div>
      <div style="font-size:56px">🍉</div>
      <div style="font-size:20px;font-weight:800;margin:10px 0 8px">下：看动作</div>
      <div style="font-size:56px">🙈</div>
    </div>

    <!-- 字卡：白底圆角，选中态用靛蓝 -->
    <div class="hx-row" style="flex-wrap:wrap;gap:8px;justify-content:center">
      <button class="hx-btn">蛋</button>
      <button class="hx-btn">抱</button>
      <button class="hx-btn hx-btn--primary">瓜</button>
    </div>

    <button class="hx-btn hx-btn--ghost hx-btn--wide">💡 提示（今日剩 5 次）</button>
  </div>
</body>
```

## 三、细则

- 字卡：白底 + 橙棕描线 + 圆角 10；选中变靛蓝底白字；点错要有抖动反馈。
- 反馈节奏：正确/错误提示停留约 1.2 秒再继续（不要一闪而过）。
- 音效要成套：点击 / 正确 / 错误 / 过关 / 选字 / 取消选字 / 提示揭示 / 解锁。
- 提示与答案**永远可选**，不要逼玩家硬想；每日免费次数用 `hx-badge` 显示。
- 图鉴/收集页用 `hx-grid` + `hx-slot`，解锁与未解锁用 `hx-slot--on` 区分。

## 四、禁忌

- ❌ 纯白底、纯黑描线
- ❌ 霓虹色 / 高饱和大色块
- ❌ 大模糊阴影、玻璃拟态（那是 anime 那套的）
- ❌ 细字重（这套要 800 的标题才像手绘）
