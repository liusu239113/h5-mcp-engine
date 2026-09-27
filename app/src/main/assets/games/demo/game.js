/*
 * Demo 游戏：拖动小球吃方块
 * 顺手演示三件事：游戏循环、沙箱存档、MCP 工具调用
 */
(function () {
    'use strict';

    const cv = document.getElementById('cv');
    const ctx = cv.getContext('2d');
    const scoreEl = document.getElementById('score');
    const statusEl = document.getElementById('status');
    const logEl = document.getElementById('log');
    const urlEl = document.getElementById('url');
    const toolEl = document.getElementById('tool');

    let W = 0, H = 0, DPR = 1;

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

    /* ---------------- 游戏状态 ---------------- */
    const state = {
        score: 0,
        player: { x: W / 2, y: H * 0.72, r: 22 },
        items: [],
        theme: { bg: '#0e1116', item: '#4ade80', player: '#60a5fa' }
    };

    function log(line) {
        logEl.textContent = line + '\n' + logEl.textContent;
    }

    /* ---------------- 输入：拖动 ---------------- */
    let dragging = false;

    function moveTo(e) {
        const t = (e.touches && e.touches[0]) || e;
        if (!t) return;
        state.player.x = Math.max(state.player.r, Math.min(W - state.player.r, t.clientX));
        state.player.y = Math.max(state.player.r, Math.min(H - state.player.r, t.clientY));
    }

    cv.addEventListener('touchstart', (e) => { dragging = true; moveTo(e); e.preventDefault(); }, { passive: false });
    cv.addEventListener('touchmove', (e) => { if (dragging) { moveTo(e); e.preventDefault(); } }, { passive: false });
    cv.addEventListener('touchend', () => { dragging = false; });
    cv.addEventListener('mousedown', (e) => { dragging = true; moveTo(e); });
    cv.addEventListener('mousemove', (e) => { if (dragging) moveTo(e); });
    cv.addEventListener('mouseup', () => { dragging = false; });

    /* ---------------- 主循环 ---------------- */
    let last = performance.now();
    let spawnT = 0;

    function frame(now) {
        const dt = Math.min((now - last) / 1000, 0.05);
        last = now;

        // 生成
        spawnT += dt;
        if (spawnT > 0.75) {
            spawnT = 0;
            state.items.push({
                x: 40 + Math.random() * Math.max(40, W - 80),
                y: -20,
                r: 14,
                vy: 90 + Math.random() * 70
            });
        }

        // 更新 + 碰撞
        for (let i = state.items.length - 1; i >= 0; i--) {
            const it = state.items[i];
            it.y += it.vy * dt;

            const dx = it.x - state.player.x;
            const dy = it.y - state.player.y;
            if (Math.hypot(dx, dy) < it.r + state.player.r) {
                state.items.splice(i, 1);
                state.score++;
                scoreEl.textContent = state.score;
                continue;
            }
            if (it.y > H + 40) state.items.splice(i, 1);
        }

        // 绘制
        ctx.fillStyle = state.theme.bg;
        ctx.fillRect(0, 0, W, H);

        ctx.fillStyle = state.theme.item;
        for (const it of state.items) {
            ctx.beginPath();
            ctx.arc(it.x, it.y, it.r, 0, Math.PI * 2);
            ctx.fill();
        }

        ctx.fillStyle = state.theme.player;
        ctx.beginPath();
        ctx.arc(state.player.x, state.player.y, state.player.r, 0, Math.PI * 2);
        ctx.fill();

        requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);

    /* ---------------- MCP ---------------- */
    let tools = [];

    function renderTools() {
        toolEl.innerHTML = '';
        if (!tools.length) {
            toolEl.innerHTML = '<option>（无工具）</option>';
            return;
        }
        tools.forEach((t) => {
            const o = document.createElement('option');
            o.value = t.name;
            o.textContent = t.name;
            toolEl.appendChild(o);
        });
    }

    /** 调完工具后尝试用返回值改游戏主题，证明数据链路真的通了 */
    function applyToolResult(text) {
        try {
            const obj = JSON.parse(text);
            if (obj && obj.theme) {
                Object.assign(state.theme, obj.theme);
                log('已应用 AI 主题: ' + JSON.stringify(obj.theme));
            }
        } catch (e) {
            /* 不是 JSON 就算了，当成纯文本 */
        }
    }

    document.getElementById('connect').onclick = async () => {
        const url = urlEl.value.trim();
        try {
            statusEl.textContent = '连接中…';
            const info = await Engine.mcp.connect(url);
            statusEl.textContent = '已连接 ' + ((info.serverInfo && info.serverInfo.name) || '');
            log('initialize ok, protocol=' + info.protocolVersion);

            const res = await Engine.mcp.listTools();
            tools = (res && res.tools) || [];
            renderTools();
            log('工具数: ' + tools.length);
        } catch (e) {
            statusEl.textContent = '连接失败';
            log('错误: ' + e.message);
        }
    };

    document.getElementById('call').onclick = async () => {
        const name = toolEl.value;
        if (!name || name.startsWith('（')) return;
        try {
            log('调用 ' + name + ' …');
            const r = await Engine.mcp.callTool(name, {});
            const text = Engine.mcp.text(r);
            log('返回: ' + text.slice(0, 300));
            applyToolResult(text);
        } catch (e) {
            log('调用失败: ' + e.message);
        }
    };

    document.getElementById('save').onclick = async () => {
        try {
            await Engine.store.save('slot1.json', { score: state.score, at: Date.now() });
            const files = await Engine.store.list();
            log('已存档，现有: ' + files.join(', '));
        } catch (e) {
            log('存档失败: ' + e.message);
        }
    };

    // 启动时尝试读档
    (async () => {
        try {
            const s = await Engine.store.load('slot1.json', null);
            if (s) {
                state.score = s.score || 0;
                scoreEl.textContent = state.score;
                log('已读档: 得分 ' + state.score);
            }
        } catch (e) { /* 首次运行没有存档，忽略 */ }
    })();
})();