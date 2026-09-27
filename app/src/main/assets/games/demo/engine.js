/*
 * MCP H5 Engine —— 游戏侧运行时，挂到 window.Engine
 *
 * 游戏里可以：
 *   await Engine.mcp.connect(url)       // 游戏主动去连外部 MCP 服务
 *   await Engine.mcp.listTools()
 *   await Engine.mcp.callTool(name, {})
 *   await Engine.store.save('slot1.json', {...})
 *   await Engine.http.get(url)          // 走原生，绕过 CORS
 *
 * 另外这里会把 console 输出上报给原生，AI 用 game_console 就能看到。
 */
(function (global) {
    'use strict';

    /* ---------------- 0. console 上报 ---------------- */

    const origLog = console.log.bind(console);
    const origWarn = console.warn.bind(console);
    const origError = console.error.bind(console);

    function fmt(v) {
        if (typeof v === 'string') return v;
        if (v instanceof Error) return v.stack || v.message;
        try { return JSON.stringify(v); } catch (e) { return String(v); }
    }

    function report(level, args) {
        try {
            if (nativeOk()) {
                window.Native.post(JSON.stringify({
                    id: '__log',
                    op: 'log',
                    data: { level: level, text: Array.prototype.map.call(args, fmt).join(' ') }
                }));
            }
        } catch (e) { /* 忽略 */ }
    }

    console.log = function () { report('log', arguments); origLog.apply(null, arguments); };
    console.warn = function () { report('warn', arguments); origWarn.apply(null, arguments); };
    console.error = function () { report('error', arguments); origError.apply(null, arguments); };

    window.addEventListener('error', function (e) {
        report('error', ['未捕获异常: ' + (e.message || e.type) +
            ' @' + (e.filename || '?') + ':' + (e.lineno || 0)]);
    });

    window.addEventListener('unhandledrejection', function (e) {
        report('error', ['未处理的 Promise 拒绝: ' + fmt(e.reason)]);
    });

    /* ---------------- 1. 原生桥 ---------------- */

    const pending = new Map();
    let seq = 0;

    function nativeOk() {
        return typeof window.Native !== 'undefined' &&
            window.Native !== null &&
            typeof window.Native.post === 'function';
    }

    window.__nativeResolve = function (payload) {
        if (!payload) return;
        const p = pending.get(payload.id);
        if (!p) return;
        pending.delete(payload.id);
        if (payload.error) p.reject(new Error(payload.error));
        else p.resolve(payload.result);
    };

    function call(op, data) {
        return new Promise(function (resolve, reject) {
            if (!nativeOk()) {
                reject(new Error('原生桥不可用：需要在 Android 容器里跑'));
                return;
            }
            const id = 'r' + (++seq);
            pending.set(id, { resolve: resolve, reject: reject });
            setTimeout(function () {
                if (pending.has(id)) {
                    pending.delete(id);
                    reject(new Error('原生调用超时: ' + op));
                }
            }, 120000);
            window.Native.post(JSON.stringify({ id: id, op: op, data: data || {} }));
        });
    }

    /* ---------------- 2. 游戏主动连 MCP ---------------- */

    const mcp = {
        connect: function (url, options) {
            const opt = options || {};
            return call('mcp.open', {
                url: url,
                headers: opt.headers || {},
                name: opt.name || 'h5-game',
                id: opt.id || url
            });
        },

        listTools: function (session) {
            return call('mcp.request', { session: session, method: 'tools/list' });
        },

        callTool: function (name, args, session) {
            return call('mcp.request', {
                session: session,
                method: 'tools/call',
                params: { name: name, arguments: args || {} }
            });
        },

        listResources: function (session) {
            return call('mcp.request', { session: session, method: 'resources/list' });
        },

        readResource: function (uri, session) {
            return call('mcp.request', {
                session: session,
                method: 'resources/read',
                params: { uri: uri }
            });
        },

        /** 把 tools/call 的 content 数组拍平成文本 */
        text: function (result) {
            if (!result) return '';
            if (typeof result === 'string') return result;
            if (Array.isArray(result.content)) {
                return result.content.map(function (c) {
                    if (c.type === 'text') return c.text;
                    if (c.type === 'image') return '[image]';
                    return JSON.stringify(c);
                }).join('\n');
            }
            return JSON.stringify(result);
        }
    };

    /* ---------------- 3. 沙箱存档 ---------------- */

    const store = {
        save: function (name, obj) {
            return call('fs.write', { path: 'saves/' + name, text: JSON.stringify(obj) });
        },
        load: function (name, fallback) {
            return call('fs.read', { path: 'saves/' + name }).then(function (r) {
                if (!r || !r.text) return fallback === undefined ? null : fallback;
                try { return JSON.parse(r.text); }
                catch (e) { return fallback === undefined ? null : fallback; }
            });
        },
        list: function () {
            return call('fs.list', { path: 'saves' }).then(function (r) {
                return (r && r.files) || [];
            });
        }
    };

    /* ---------------- 4. HTTP（绕过 CORS） ---------------- */

    const http = {
        get: function (url, headers) {
            return call('http', { method: 'GET', url: url, headers: headers || {} });
        },
        post: function (url, body, headers) {
            return call('http', {
                method: 'POST',
                url: url,
                headers: headers || {},
                body: typeof body === 'string' ? body : JSON.stringify(body)
            });
        }
    };

    global.Engine = {
        call: call,
        mcp: mcp,
        store: store,
        http: http,
        hasNative: nativeOk
    };
})(window);