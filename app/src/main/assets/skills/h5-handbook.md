# H5 游戏手册（写 index.html + js 的工程看这份）

适用：工程根有 `index.html`，没有 `scripts/main.lua`、没有 `urhox-libs/`。
那就是 H5 工程 —— 用浏览器那套写，**不要**套 Maker/UrhoX 的写法。

配套：`game-design-playbook.md`（怎么做好玩）、`ui-design-spec.md`（UI 规范）。

---

## 0. 先搞清楚「跑在哪」

代码跑在**手机上的 WebView** 里，不是普通浏览器。所以：

| 有的 | 没有的 / 要小心的 |
|---|---|
| Canvas 2D / WebGL / WebAudio | 不能起本地服务、不能用 Node API |
| `requestAnimationFrame`、触摸事件 | 桌面鼠标事件**也可能触发**，别只写 touch |
| `localStorage`（够用） | 大文件别塞 localStorage，用工程里的 `_uploads/` |
| ES6+（现代内核） | 别用需要构建步骤的东西（TS / JSX / npm 包） |

**铁律：单文件可跑、零外部依赖、不引入构建工具。**
一个 `index.html` 打开就能玩 —— 这是硬要求，不是偏好。

---

## 1. 标准骨架

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<!-- 关键：禁止用户缩放，否则双击会放大整个游戏 -->
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<style>
  html,body{margin:0;height:100%;overflow:hidden;background:#111;
            -webkit-user-select:none;user-select:none;-webkit-tap-highlight-color:transparent;}
  canvas{display:block;touch-action:none;}
</style>
</head>
<body>
<canvas id="c"></canvas>
<script>
(function(){
  'use strict';
  const cvs = document.getElementById('c');
  const ctx = cvs.getContext('2d');
  let W = 0, H = 0, DPR = 1;

  function resize(){
    DPR = Math.min(window.devicePixelRatio || 1, 2);   // 封顶 2，3x 屏上别烧性能
    W = cvs.clientWidth; H = cvs.clientHeight;
    cvs.width  = Math.round(W * DPR);
    cvs.height = Math.round(H * DPR);
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);           // 之后都按 CSS 像素画
  }
  window.addEventListener('resize', resize);
  resize();

  // ---- 游戏状态 ----
  const G = { t: 0, over: false };

  function update(dt){
    if (G.over) return;
    G.t += dt;
    // 更新逻辑
  }

  function draw(){
    ctx.clearRect(0, 0, W, H);
    // 绘制
  }

  // ---- 固定步长循环：物理/逻辑用固定 dt，渲染跟帧率 ----
  let last = 0, acc = 0;
  const STEP = 1/60;
  function loop(now){
    if (!last) last = now;
    let dt = (now - last) / 1000; last = now;
    if (dt > 0.25) dt = 0.25;          // 切后台回来会有一个巨大的 dt，必须夹住
    acc += dt;
    let guard = 0;
    while (acc >= STEP && guard++ < 5) { update(STEP); acc -= STEP; }
    draw();
    requestAnimationFrame(loop);
  }
  requestAnimationFrame(loop);

  // ---- 触摸输入 ----
  function pos(e){
    const t = e.touches ? e.touches[0] : e;
    const r = cvs.getBoundingClientRect();
    return { x: t.clientX - r.left, y: t.clientY - r.top };
  }
  cvs.addEventListener('pointerdown', e => { e.preventDefault(); onDown(pos(e)); });
  cvs.addEventListener('pointermove', e => { e.preventDefault(); onMove(pos(e)); });
  cvs.addEventListener('pointerup',   e => { onUp(pos(e)); });
  function onDown(p){ /* ... */ }
  function onMove(p){ /* ... */ }
  function onUp(p){ /* ... */ }
})();
</script>
</body>
</html>
```

**几个必须照做的点：**

- **`dt` 要夹住**：切后台再回来 `now - last` 可能是几十秒，
  不夹的话角色会瞬移、物理会炸。
- **用 pointer 事件**，不要只写 `touchstart` —— pointer 一套同时覆盖触摸和鼠标，
  桌面调试时也能玩。
- **`touch-action: none`** 必须加，否则移动端会被浏览器的滚动/缩放手势抢走。
- **DPR 封顶 2**：3x 屏上按 3 倍分辨率渲染，像素量是 2.25 倍，手机直接掉帧。

---

## 2. 坐标系与屏幕适配

**只用一个坐标系**（推荐 CSS 像素），画的时候不要混着用。

```js
// 竖屏设计（最常见）
const DESIGN_W = 360, DESIGN_H = 640;      // 逻辑设计尺寸
let scale, offX, offY;
function layout(){
  scale = Math.min(W / DESIGN_W, H / DESIGN_H);   // 等比缩放 + 居中
  offX = (W - DESIGN_W * scale) / 2;
  offY = (H - DESIGN_H * scale) / 2;
}
// 画之前：ctx.save(); ctx.translate(offX,offY); ctx.scale(scale,scale);
// 之后所有坐标都按 360×640 写，不用管真实分辨率
```

**安全区**（刘海屏 / 手势条）：用 `env(safe-area-inset-*)`，
关键 UI（血条、按钮）不要贴边放：

```css
#hud{ padding-top: env(safe-area-inset-top); padding-bottom: env(safe-area-inset-bottom); }
```

**触摸目标 ≥ 44px**（CSS 像素）。手机上比 44 小的按钮很难点中。

---

## 3. 性能（手机上最容易翻车的地方）

| 别做 | 为什么 | 改成 |
|---|---|---|
| 每帧 `new` 对象 / 数组 | 每秒 60 次分配 → GC 抖动 | 对象池，或复用同一个对象 |
| 每帧 `ctx.font = ...` 设字符串 | 解析字体字符串不便宜 | 只在真正变化时设 |
| 每帧创建渐变 / pattern | 同上 | 建一次缓存起来 |
| `ctx.drawImage` 缩放同一个图很多次 | 每次都重采样 | 预生成不同尺寸的离屏 canvas |
| 大量 `fillText` | 文字渲染是最贵的 | 静态文字预渲染到离屏 canvas |
| 全屏 `clearRect` + 全量重绘复杂背景 | 像素填充量大 | 背景预渲染成一张图，每帧只 drawImage |

**离屏缓存**是最有效的一招：

```js
function makeCache(w, h, drawFn){
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  drawFn(c.getContext('2d'));
  return c;
}
// 用法：背景/静态 UI 只画一次
const bgCache = makeCache(W, H, c => { /* 画背景 */ });
// 每帧：ctx.drawImage(bgCache, 0, 0, W, H);
```

**先测量再优化**：`js_eval` 跑一段计时代码，看真实帧率，别凭感觉猜：

```js
// 用 js_eval 跑
(function(){
  let n = 0, t0 = performance.now();
  return new Promise(r => {
    function f(){ if (++n >= 120) r('平均帧 ' + ((performance.now()-t0)/n).toFixed(2) + 'ms');
                  else requestAnimationFrame(f); }
    requestAnimationFrame(f);
  });
})()
```

---

## 4. 音频

```js
// 必须等用户第一次交互才能播（浏览器自动播放策略）
let actx = null;
function ensureAudio(){
  if (!actx) actx = new (window.AudioContext || window.webkitAudioContext)();
  if (actx.state === 'suspended') actx.resume();
}
document.addEventListener('pointerdown', ensureAudio, { once: true });

// 简单音效用振荡器（不用加载文件）
function beep(freq = 440, dur = 0.08){
  if (!actx) return;
  const o = actx.createOscillator(), g = actx.createGain();
  o.frequency.value = freq;
  g.gain.setValueAtTime(0.15, actx.currentTime);
  g.gain.exponentialRampToValueAtTime(0.001, actx.currentTime + dur);
  o.connect(g); g.connect(actx.destination);
  o.start(); o.stop(actx.currentTime + dur);
}
```

⚠️ **不要在没交互前就 `new Audio().play()`** —— 会被拒，而且控制台会刷一片报错。

---

## 5. 存档

```js
// 小数据用 localStorage（够用、最简单）
localStorage.setItem('save1', JSON.stringify({ level: 3, score: 1200 }));
const s = JSON.parse(localStorage.getItem('save1') || 'null');
```

大数据（截图、音频）不要塞 localStorage（有 5MB 上限、且是同步的会卡）。
放工程 `_uploads/` 里，用相对路径引用。

---

## 6. 写完必须自检

**改完代码调 `game_validate`** —— 它会重载、跑一会儿、把 console 报错和页面状态给你。
比只看截图靠谱：截图只能看出「画面不对」，它告诉你「哪一行抛了异常」。

常见报错对照：

| 报错 | 多半是 |
|---|---|
| `xxx is not defined` | 脚本加载顺序错了，或者变量没挂到 window 上 |
| `Cannot read properties of null` | 元素还没加载出来就取（把 script 放到 body 末尾） |
| `Uncaught (in promise)` | 某个 async 函数没 catch |
| 画面全黑但没报错 | canvas 尺寸是 0（CSS 没给宽高），或者没画 |

**截图看效果**用 `game_shot` —— 它抓的是真实渲染结果，不切你的屏幕。

---

## 7. 交付检查清单

- [ ] 单个 `index.html` 打开就能玩，没有外部依赖
- [ ] `viewport` 带 `user-scalable=no` + `viewport-fit=cover`
- [ ] `touch-action: none`，用 pointer 事件
- [ ] 循环里 `dt` 夹住了上限
- [ ] DPR 封顶 2
- [ ] 关键 UI 避开了安全区，触摸目标 ≥ 44px
- [ ] 音频在首次交互后才播
- [ ] `game_validate` 跑过，没有真实报错
- [ ] `game_shot` 看过画面，不是白屏 / 错位
