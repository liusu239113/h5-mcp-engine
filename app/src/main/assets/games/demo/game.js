/*
 * Demo：接方块
 *   - 手指拖动圆盘接住掉落的方块
 *   - 有连击、难度递增、粒子、最高分存档
 *   - window.__game 暴露全部状态，方便 AI 用 js_eval 观察/调试
 */
(function () {
  'use strict';

  var cv = document.getElementById('cv');
  var ctx = cv.getContext('2d');
  var scoreEl = document.getElementById('score');
  var bestEl = document.getElementById('best');

  var W = 0, H = 0, DPR = 1;
  function resize() {
    DPR = Math.min(window.devicePixelRatio || 1, 2);
    W = window.innerWidth;
    H = window.innerHeight;
    cv.width = W * DPR;
    cv.height = H * DPR;
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
  }
  window.addEventListener('resize', resize);
  resize();

  var CONFIG = {
    baseSpeed: 1.8,
    speedUpPerHit: 0.03,
    spawnMs: 800,
    minSpawnMs: 320,
    playerR: 30,
    itemR: 15
  };

  var state = {
    score: 0,
    best: 0,
    combo: 0,
    speed: CONFIG.baseSpeed,
    player: { x: W / 2, y: H * 0.82, r: CONFIG.playerR, tx: W / 2 },
    items: [],
    particles: [],
    spawnMs: CONFIG.spawnMs,
    over: false
  };

  /* ---------- 输入 ---------- */
  function moveTo(x) {
    state.player.tx = Math.max(state.player.r, Math.min(W - state.player.r, x));
  }
  window.addEventListener('pointerdown', function (e) {
    if (state.over) { restart(); return; }
    moveTo(e.clientX);
  });
  window.addEventListener('pointermove', function (e) {
    if (!state.over) moveTo(e.clientX);
  });

  /* ---------- 存档 ---------- */
  if (window.Engine && Engine.store) {
    Engine.store.load('demo.json').then(function (d) {
      if (d && typeof d.best === 'number') {
        state.best = d.best;
        bestEl.textContent = state.best;
      }
    }).catch(function () { /* 首次运行没有存档，忽略 */ });
  }

  function saveBest() {
    if (window.Engine && Engine.store) {
      Engine.store.save('demo.json', { best: state.best }).catch(function () {});
    }
  }

  /* ---------- 游戏逻辑 ---------- */
  function spawn() {
    var r = CONFIG.itemR;
    state.items.push({
      x: r + Math.random() * (W - r * 2),
      y: -r * 2,
      r: r,
      hue: Math.random() < 0.15 ? 60 : 150   /* 偶尔来个金色的 */
    });
  }

  var spawnTimer = 0;
  function update(dt) {
    spawnTimer += dt;
    if (spawnTimer >= state.spawnMs) {
      spawnTimer = 0;
      spawn();
      state.spawnMs = Math.max(CONFIG.minSpawnMs, state.spawnMs - 4);
    }

    state.player.x += (state.player.tx - state.player.x) * 0.25;

    for (var i = state.items.length - 1; i >= 0; i--) {
      var it = state.items[i];
      it.y += state.speed;

      var d = Math.hypot(it.x - state.player.x, it.y - state.player.y);
      if (d < it.r + state.player.r) {
        var gold = it.hue === 60;
        state.combo += 1;
        state.score += (gold ? 50 : 10) + state.combo * 2;
        state.speed = Math.min(7, state.speed + CONFIG.speedUpPerHit);
        burst(it.x, it.y, gold ? '#fbbf24' : '#4ade80');
        state.items.splice(i, 1);
        scoreEl.textContent = state.score;
        continue;
      }

      if (it.y > H + it.r * 2) {
        state.items.splice(i, 1);
        state.combo = 0;
      }
    }

    for (var p = state.particles.length - 1; p >= 0; p--) {
      var q = state.particles[p];
      q.x += q.vx; q.y += q.vy; q.vy += 0.15; q.life -= 1;
      if (q.life <= 0) state.particles.splice(p, 1);
    }

    if (state.score > state.best) {
      state.best = state.score;
      bestEl.textContent = state.best;
    }
  }

  function burst(x, y, color) {
    for (var i = 0; i < 10; i++) {
      state.particles.push({
        x: x, y: y,
        vx: (Math.random() - 0.5) * 5,
        vy: (Math.random() - 0.5) * 5,
        life: 22 + Math.random() * 14,
        color: color
      });
    }
  }

  function restart() {
    state.over = false;
    state.score = 0;
    state.combo = 0;
    state.speed = CONFIG.baseSpeed;
    state.spawnMs = CONFIG.spawnMs;
    state.items = [];
    state.particles = [];
    scoreEl.textContent = 0;
  }

  /* ---------- 渲染 ---------- */
  function draw() {
    ctx.fillStyle = '#0e1116';
    ctx.fillRect(0, 0, W, H);

    /* 底部光带，让画面不那么空 */
    var g = ctx.createLinearGradient(0, H * 0.6, 0, H);
    g.addColorStop(0, 'rgba(96,165,250,0)');
    g.addColorStop(1, 'rgba(96,165,250,0.10)');
    ctx.fillStyle = g;
    ctx.fillRect(0, H * 0.6, W, H * 0.4);

    for (var i = 0; i < state.items.length; i++) {
      var it = state.items[i];
      ctx.fillStyle = it.hue === 60 ? '#fbbf24' : '#4ade80';
      ctx.shadowColor = ctx.fillStyle;
      ctx.shadowBlur = 14;
      ctx.beginPath();
      ctx.arc(it.x, it.y, it.r, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
    }

    /* 玩家：圆盘 + 内圈 */
    ctx.fillStyle = '#60a5fa';
    ctx.beginPath();
    ctx.arc(state.player.x, state.player.y, state.player.r, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#0e1116';
    ctx.beginPath();
    ctx.arc(state.player.x, state.player.y, state.player.r * 0.5, 0, Math.PI * 2);
    ctx.fill();

    for (var p = 0; p < state.particles.length; p++) {
      var q = state.particles[p];
      ctx.globalAlpha = Math.max(0, q.life / 36);
      ctx.fillStyle = q.color;
      ctx.fillRect(q.x - 2, q.y - 2, 4, 4);
    }
    ctx.globalAlpha = 1;
  }

  /* ---------- 主循环 ---------- */
  var last = performance.now();
  function loop(now) {
    var dt = Math.min(50, now - last);
    last = now;
    update(dt);
    draw();
    window.__game = state;
    requestAnimationFrame(loop);
  }
  requestAnimationFrame(loop);

  window.addEventListener('pagehide', saveBest);
  window.addEventListener('blur', saveBest);

  console.log('[demo] 接方块启动完成，CONFIG=' + JSON.stringify(CONFIG));
})();