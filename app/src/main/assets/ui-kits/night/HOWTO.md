# 暗夜勇者（night）· 写法手册

提炼自真实上线的英雄养成 RPG。核心是**暗紫夜色 + 青绿冷光 + 暖金奖励**。

## 一、气质三件事

1. **底是夜色渐变**，不是纯黑。纯黑面板会让画面"死"。
2. **青绿是生命色**：生命/能量/确认按钮都用 `--hx-accent`。
3. **金色只在奖励瞬间出现**：升星、传说、掉落。满屏金色就不值钱了。

## 二、英雄卡（这套风格的核心组件）

```html
<div class="hx-panel">
  <div class="hx-row" style="gap:10px">
    <img src="assets/sprites/hero_S_10_male.png" alt="" style="width:64px;height:64px">
    <div style="flex:1">
      <div class="hx-row hx-row--between">
        <span style="font-weight:800">巴尔德</span>
        <span class="hx-badge hx-badge--dim">枪兵</span>
      </div>
      <div class="hx-sub">HP 65 · 攻 12 · 速 1.25</div>
      <div class="hx-bar"><i style="width:72%"></i></div>
    </div>
  </div>
  <div class="hx-dialog__foot">
    <button class="hx-btn">升级</button>
    <button class="hx-btn hx-btn--primary">出战</button>
  </div>
</div>
```

## 三、体系化 UI（养成游戏必须给玩家"清楚"）

- **品质色带**：普通灰蓝 → 稀有紫 → 史诗青绿 → 传说金。四档，不要更多。
- **星级**：用 `★` 重复，不要用数字。
- 数值一律"当前 / 上限"，不要只给当前值。
- 升星/升级要给出**明确的材料消耗与来源**，玩家最烦"我该怎么变强"。

## 四、编队与战斗

- 编队页给"职业相克/定位"提示（近战/远程/辅助），别只排头像。
- 战斗里先行动方用高亮描边标出，出手顺序要能看见。
- 伤害飘字用暖金，治疗用青绿，受伤用红 —— 三色固定。

## 五、禁忌

- ❌ 纯黑底、纯灰面板
- ❌ 高饱和大色块（这套是暗色系，亮色只做点缀）
- ❌ 品质用超过四档颜色
- ❌ 数字堆成一片没有对比（数值要有"变化箭头 +Δ"）
