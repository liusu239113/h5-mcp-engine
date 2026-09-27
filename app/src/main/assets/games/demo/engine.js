/*
 * MCP H5 Engine - JS 运行时
 * 挂到 window.Engine，游戏侧直接：
 *   await Engine.mcp.connect('http://10.0.2.2:3000/mcp')
 *   const tools = await Engine.mcp.listTools()
 *   const r = await Engine.mcp.callTool('weather', { city: '上海' })
 *
 * 完全零依赖，方便你魔改 / 换成 @modelcontextprotocol/sdk
 */
(function (global) {
    'use strict';

    /* ---------------- 1. 原生桥 ---------------- */
    const pending = new Map();
    let seq = 0;

    const hasNative = () =>
        typeof window.Native !== 'undefined' &&
        window.Native !== null &&
        typeof window.Native.post === 'function';

    // 原生会调这个（注意：原生传的是对象，不是字符串）
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
            if (!hasNative()) {
                reject(new Error('原生桥不可用：需要在 Android 容器里跑'));
                return;
            }
            const id = 'r' + (++seq);
            pending.set(id, { resolve: resolve, reject: reject });
            // 超时兜底，防止游戏卡死在 await 上
            setTimeout(function () {
                if (pending.has(id)) {
                    pending.delete(id);
                    reject(new Error('原生调用超时: ' + op));
                }
            }, 120000);
            window.Native.post(JSON.stringify({ id: id, op: op, data: data || {} }));
        });
    }

    /* ---------------- 2. MCP ---------------- */
    const sessions = new Map();

    const mcp = {
        /** 连接并 initialize，返回 { serverInfo, protocolVersion } */
        connect: function (url, options) {
            const opt = options || {};
            return call('mcp.open', {
                url: url,
                headers: opt.headers || {},
                name: opt.name || 'h5-game',
                id: opt.id || url
            }).then(function (info) {
                sessions.set(opt.id || url, true);
                return info;
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

        /** 把 tools/call 返回的 content 数组拍平成人能看的文本 */
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

    global.Engine = { call: call, mcp: mcp, store: store, http: http, hasNative: hasNative };
})(window);