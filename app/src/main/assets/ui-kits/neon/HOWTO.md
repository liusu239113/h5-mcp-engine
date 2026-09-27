# 星塔霓虹（neon）· 写法手册

提炼自真实上线的竖屏回合制 Roguelite（真实色值：夜底 #06030F/#120A2E/#1B1040、
霓虹青绿 #66E28A、霓虹蓝 #7FD3FF、金 #FFD166、红 #FF4757、紫 #6C5CE7）。

## 一、霓虹三招（不会这三招就不叫霓虹）

1. **发光用带颜色的阴影**：`box-shadow: 0 0 18px rgba(102,226,138,.25)`。
   不是加粗描边，也不是纯白外发光。
2. **星夜紫底 + 5% 白半透明面板**：面板要能看见底下的渐变，才有"深空"感。
3. **高饱和只出现在小面积**：一个青绿按钮、一条金边、一个红警报 —— 大面积高饱和会土。

## 二、构筑盘（星语环）的写法

```html
<div class="hx-panel">
  <div class="hx-sub">星语环 · 6 格 + 中心神格</div>
  <div class="hx-grid" style="grid-template-columns:repeat(3,1fr);gap:8px;margin-top:8px">
    <div class="hx-slot hx-slot--on">裂界</div>
    <div class="hx-slot">星轨</div>
    <div class="hx-slot hx-slot--on">裂界</div>
    <div class="hx-slot">命途</div>
    <div class="hx-slot hx-slot--on" style="border-color:#ffd166">曜神</div>
    <div class="hx-slot">磐垒</div>
  </div>
  <div class="hx-sub" style="margin-top:8px">相邻同源 = 共鸣边　当前共鸣链长 3</div>
</div>
```

- 相邻同源要**画连线**（canvas 或绝对定位的细线 + 光晕），让"共鸣"看得见。
- 覆盖（换掉一格）要有明显的对比：选中的格子放大 + 光晕，被覆盖的格子闪红。

## 三、战斗界面

- 敌方**意图**必须在行动前显示（攻击/群攻/控制/回复 + 目标）。
- 能量用蓝、生命用红、护盾用青绿；伤害飘字金色。
- 回合数放显眼位置，第 10 回合后会有伤害递增机制，要让玩家感觉到"时限"。

## 四、禁忌

- ❌ 不透明深灰面板（要半透明 + 渐变）
- ❌ 大面积高饱和紫/粉
- ❌ 用白色发光代替带色光晕
- ❌ 标题字距太窄（这套要 .08em 才像日系标题）
