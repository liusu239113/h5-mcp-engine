/*
 * H5 引擎运行时 —— 挂在 window.Engine 上，所有游戏共用。
 *
 * 做的事：
 *   1. 把 console 输出托管给原生，AI 可以用 console_logs 读走
 *   2. 提供 store（存档）/ http / ping 给游戏用
 *   3. 捕获 window.onerror 与未处理的 Promise 异常，一并回报
 */
(function (w) {
  'use strict';

  var Native = w.Native;
  var seq = 0;
  var waiting = {};

  w.__nativeResolve = function (payload) {
    var cb = waiting[payload.id];
    if (!cb) return;
    delete waiting[payload.id];
    if (payload.error) cb.reject(new Error(payload.error));
    else cb.resolve(payload.result || {});
  };

  function call(op, data, timeoutMs) {
    if (!Native || !Native.post) return Promise.reject(new Error('原生桥不可用'));
    return new Promise(function (resolve, reject) {
      var id = 'r' + (++seq) + '_' + Date.now();
      waiting[id] = { resolve: resolve, reject: reject };
      try {
        Native.post(JSON.stringify({ id: id, op: op, data: data || {} }));
      } catch (e) {
        delete waiting[id];
        reject(e);
        return;
      }
      setTimeout(function () {
        if (waiting[id]) {
          delete waiting[id];
          reject(new Error('原生调用超时: ' + op));
        }
      }, timeoutMs || 15000);
    });
  }

  /* ---------- console 托管 ---------- */
  var orig = {};
  ['log', 'warn', 'error', 'info'].forEach(function (level) {
    orig[level] = console[level];
    console[level] = function () {
      var text = Array.prototype.map.call(arguments, function (a) {
        if (typeof a === 'string') return a;
        try { return JSON.stringify(a); } catch (e) { return String(a); }
      }).join(' ');
      try { orig[level].apply(console, arguments); } catch (e) { /* ignore */ }
      call('log', { level: level, text: text }, 3000).catch(function () { /* ignore */ });
    };
  });

  w.addEventListener('error', function (e) {
    console.error('[onerror] ' + (e.message || '') + ' @' + (e.lineno || 0) + ':' + (e.colno || 0));
  });
  w.addEventListener('unhandledrejection', function (e) {
    console.error('[unhandledrejection] ' + (e.reason && (e.reason.message || e.reason)));
  });

  /* ---------- 给游戏用的接口 ---------- */
  w.Engine = {
    version: '2.0.0',
    hasNative: !!(Native && Native.post),
    call: call,
    ping: function () { return call('ping', {}, 3000); },

    store: {
      save: function (key, obj) {
        return call('fs.write', { path: 'saves/' + key, text: JSON.stringify(obj) });
      },
      load: function (key) {
        return call('fs.read', { path: 'saves/' + key }).then(function (r) {
          try { return r.text ? JSON.parse(r.text) : null; } catch (e) { return null; }
        });
      },
      remove: function (key) { return call('fs.delete', { path: 'saves/' + key }); },
      list: function () { return call('fs.list', { path: 'saves' }); }
    },

    http: function (url, opt) {
      opt = opt || {};
      return call('http', {
        url: url,
        method: opt.method || 'GET',
        headers: opt.headers || {},
        body: opt.body || ''
      });
    }
  };

  console.log('[engine] ready v' + w.Engine.version + ', native=' + w.Engine.hasNative);
})(window);