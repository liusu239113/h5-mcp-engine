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
  var _objs = [];

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
    },

    /*
     * 对象注册表 —— 供 App 的「对象」面板读取（GDevelop 那种对象列表）。
     *
     * 创建角色 / 敌人 / 墙……时顺手注册：
     *   var player = Engine.objects.register('Player', sprite, '角色');
     * 面板会实时列出（名字 / 类型 / 坐标 / 可见性），点一下能在画面上高亮。
     *
     * 不注册也能用：面板会自动探测 Phaser / Pixi 的场景树。
     * 但**自绘 canvas 的游戏只有注册了才看得见** —— 所以推荐都注册。
     */
    objects: {
      register: function (name, obj, kind) {
        try {
          var n = String(name);
          _objs = _objs.filter(function (o) { return o.name !== n; });
          _objs.push({ name: n, obj: obj, kind: kind || '' });
        } catch (e) { /* ignore */ }
        return obj;
      },
      remove: function (name) {
        _objs = _objs.filter(function (o) { return o.name !== String(name); });
      },
      clear: function () { _objs = []; },
      list: function () {
        return _objs.map(function (o) {
          var t = o.obj;
          var type = o.kind;
          if (!type && t) {
            type = (t.constructor && t.constructor.name) || '';
            if (typeof t.type === 'string') type = t.type;
          }
          return {
            name: o.name,
            type: String(type || ''),
            x: (t && typeof t.x === 'number') ? Math.round(t.x) : null,
            y: (t && typeof t.y === 'number') ? Math.round(t.y) : null,
            visible: !t || t.visible !== false
          };
        });
      },
      highlight: function (name, ms) {
        var hit = null;
        try {
          for (var i = 0; i < _objs.length; i++) {
            if (_objs[i].name === String(name)) { hit = _objs[i].obj; break; }
          }
        } catch (e) { return false; }
        if (!hit) return false;
        var dur = ms || 1500;
        try {
          // 自绘游戏可以自己实现 highlight(ms)：注册表优先用它（比如画一个圈）
          if (typeof hit.highlight === 'function') { hit.highlight(dur); return true; }
          if (typeof hit.setTint === 'function') {
            var old = (typeof hit.tint !== 'undefined') ? hit.tint : null;
            hit.setTint(0xffd54f);
            setTimeout(function () {
              try {
                if (old === null || old === undefined) {
                  if (typeof hit.clearTint === 'function') hit.clearTint();
                  else hit.setTint(0xffffff);
                } else { hit.setTint(old); }
              } catch (e) { /* ignore */ }
            }, dur);
            return true;
          }
          if (hit.style) {
            var o2 = hit.style.outline;
            hit.style.outline = '3px solid #ffd54f';
            setTimeout(function () { hit.style.outline = o2 || ''; }, dur);
            return true;
          }
        } catch (e) { /* ignore */ }
        return false;
      }
    }
  };

  console.log('[engine] ready v' + w.Engine.version + ', native=' + w.Engine.hasNative);
})(window);