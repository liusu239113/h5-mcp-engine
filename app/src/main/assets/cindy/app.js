/* Hexora · Cindy 风格对话层
 * 与原生（Kotlin）通过 HexoraNative.post(JSON) 上行、
 * UI.on(obj) 下行。事件模型对齐 Cindy 的 agent 事件流。 */
(function () {
  'use strict';

  var $ = function (s) { return document.querySelector(s); };
  var stream = $('#stream'), inp = $('#inp'), sendBtn = $('#send');
  var mask = $('#mask'), sheet = $('#sheet'), shBody = $('#shBody'), shTitle = $('#shTitle');

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '"', "'": '&#39;' }[c];
    });
  }

  /* ---------- 轻量 markdown：代码块 / 行内码 / 粗体 / 链接 ---------- */
  function md(src) {
    src = String(src == null ? '' : src);
    var blocks = [], out = '';
    src = src.replace(/```([a-zA-Z0-9_+-]*)\n([\s\S]*?)```/g, function (m, lang, code) {
      blocks.push(code.replace(/\n$/, ''));
      return '\u0000B' + (blocks.length - 1) + '\u0000';
    });
    out = esc(src);
    out = out.replace(/`([^`\n]+)`/g, '<code>$1</code>');
    out = out.replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>');
    out = out.replace(/\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/g,
      '<a href="$2" style="color:var(--blue)">$1</a>');
    out = out.replace(/\u0000B(\d+)\u0000/g, function (m, i) {
      return '<pre style="background:var(--chip);border:1px solid var(--border);' +
        'border-radius:10px;padding:10px;overflow:auto;font-size:12.5px;' +
        'font-family:ui-monospace,Menlo,monospace;margin:6px 0">' +
        esc(blocks[+i]) + '</pre>';
    });
    return out;
  }

  function near() {
    return stream.scrollHeight - stream.scrollTop - stream.clientHeight < 90;
  }
  function toBottom(force) {
    if (force || near()) stream.scrollTop = stream.scrollHeight;
  }
  function toast(t) {
    var el = $('#toast'); el.textContent = t; el.classList.add('on');
    clearTimeout(toast._t); toast._t = setTimeout(function () { el.classList.remove('on'); }, 1600);
  }

  /* ================= 渲染 ================= */
  var S = {
    busy: false,
    cur: null,          // 当前 AI 消息容器
    think: null,        // 当前思考块
    tools: null,        // 当前工具组
    toolMap: {},        // 工具行索引
    buf: '',            // 正文流缓冲
    toolsStart: 0       // 工具组起始时刻（算「已工作 Xs」）
  };

  function el(tag, cls, html) {
    var d = document.createElement(tag);
    if (cls) d.className = cls;
    if (html != null) d.innerHTML = html;
    return d;
  }

  function addUser(t) {
    stream.appendChild(el('div', 'row user', '<div class="body">' + esc(t) + '</div>'));
    toBottom(true);
  }
  function addSys(t) {
    stream.appendChild(el('div', 'row sys', esc(t)));
    toBottom(true);
  }
  function addError(t) {
    stream.appendChild(el('div', 'row ai', '<div class="err">' + esc(t) + '</div>'));
    toBottom(true);
  }

  /** 开一条新的 AI 消息（正文容器） */
  function turn() {
    var wrap = el('div', 'row ai');
    var body = el('div', 'body');
    wrap.appendChild(body);
    // 顺序是：思考块 → 工具组 → 正文。用占位插入，保证正文永远在最下。
    stream.appendChild(wrap);
    S.cur = { wrap: wrap, body: body };
    S.think = null; S.tools = null; S.toolMap = {};
    S.buf = '';
    return S.cur;
  }

  function ensureTurn() { return S.cur || turn(); }

  function appendText(t, final) {
    ensureTurn();
    S.buf += t;
    S.cur.body.innerHTML = md(S.buf) + (final ? '' : '<span class="spin" style="color:var(--text2)">▍</span>');
    toBottom();
  }
  function setText(t) {
    ensureTurn();
    S.buf = t;
    S.cur.body.innerHTML = md(S.buf);
    toBottom();
  }

  function ensureThink() {
    ensureTurn();
    if (S.think) return S.think;
    var d = el('details', 'think', '<summary><span class="dot"></span>思考中…</summary>' +
      '<div class="txt"></div>');
    S.cur.wrap.insertBefore(d, S.cur.body);
    S.think = d;
    return d;
  }
  function appendThink(t) {
    var d = ensureThink();
    var tx = d.querySelector('.txt');
    tx.textContent += t;
    tx.scrollTop = tx.scrollHeight;
    toBottom();
  }
  function endThink() {
    if (!S.think) return;
    S.think.querySelector('summary').innerHTML =
      '<span class="dot" style="background:var(--text2)"></span>已思考';
    S.think = null;
  }

  function ensureTools() {
    ensureTurn();
    if (S.tools) return S.tools;
    var d = el('div', 'tools', '<div class="th">已工作</div>');
    S.cur.wrap.insertBefore(d, S.cur.body);
    S.tools = d;
    S.toolsStart = Date.now();
    S.toolMap = {};
    return d;
  }

  /** 秒 → 「X分 Y秒」/「Y秒」 */
  function fmtDur(sec) {
    sec = Math.max(0, Math.round(sec));
    var m = Math.floor(sec / 60), s = sec % 60;
    return m > 0 ? (m + '分 ' + s + '秒') : (s + '秒');
  }

  /** 工具组头：已工作 Xs */
  function paintToolsHead(live) {
    if (!S.tools || !S.toolsStart) return;
    var sec = (Date.now() - S.toolsStart) / 1000;
    if (!live) S.tools._done = sec;
    S.tools.querySelector('.th').textContent =
      '已工作 ' + Math.round(S.tools._done != null && !live ? S.tools._done : sec) + 's';
  }

  function toolRow(name, target, state, head, full) {
    var box = ensureTools();
    var key = name + '|' + (target || '');
    var row = S.toolMap[key];
    if (!row) {
      row = el('div', 'tool');
      box.appendChild(row);
      S.toolMap[key] = row;
    }
    var mark = state === 'run' ? '<span class="spin">◜</span>'
      : state === 'ok' ? '✓' : '✕';
    row.className = 'tool ' + state;
    row.innerHTML = '<span class="st">' + mark + '</span>' +
      '<span class="nm">' + esc(name) + '</span>' +
      '<span class="tg">' + esc(full || head || target || '') + '</span>';
    row.onclick = function () { row.classList.toggle('open'); };
    if (state !== 'run') paintToolsHead(false);
    return row;
  }

  function addShots(items) {
    var box = el('div', 'shots');
    items.forEach(function (it) {
      var src = it.src || it, path = it.path || src;
      var img = el('img');
      img.src = src;
      img.onclick = function () { post({ a: 'open', path: path }); };
      box.appendChild(img);
    });
    ensureTurn();
    S.cur.wrap.insertBefore(box, S.cur.body);
    toBottom();
  }

  function addCard(kind, k, v) {
    var box = el('div', 'card',
      '<span class="k">' + esc(k) + '</span><span class="v">' + esc(v) + '</span>');
    box.onclick = function () { post({ a: 'asset', path: v }); };
    ensureTurn();
    S.cur.wrap.insertBefore(box, S.cur.body);
    toBottom();
  }

  function addSummary(ms, steps, verified, sections) {
    var secs = (sections || []).map(function (s) {
      return '<details><summary>' + esc(s[0]) + '</summary><div class="sd">' +
        esc(s[1]) + '</div></details>';
    }).join('');
    var d = el('div', 'sum',
      '<div class="sh">▸ 本轮 · ' + steps + ' 步 · ' + (ms / 1000).toFixed(1) + 's · ' +
      (verified ? '已核验' : '未核验') + '</div>' + secs);
    stream.insertBefore(d, S.cur && S.cur.wrap ? S.cur.wrap : null);
    toBottom();
  }

  function clear() {
    stream.innerHTML = '';
    S.cur = null; S.think = null; S.tools = null; S.toolMap = {}; S.buf = '';
  }

  /* ================= 状态栏 / 发送按钮 ================= */
  function setBusy(on) {
    S.busy = on;
    sendBtn.classList.toggle('stop', on);
    sendBtn.textContent = on ? '■' : '↑';
    inp.placeholder = on ? '正在工作…' : '说点什么…（回车发送）';
  }
  function setStat(t) { $('#stat').textContent = t || ''; }
  function setStep(n) { $('#stepi').textContent = n ? ('· ' + n) : ''; }

  /* ================= 与原生通信 ================= */
  function post(o) {
    try {
      if (window.HexoraNative && window.HexoraNative.post) {
        window.HexoraNative.post(JSON.stringify(o));
      } else {
        console.log('[cindy] no native', o);
      }
    } catch (e) { console.log('post err', e); }
  }

  /* ================= 设置抽屉 ================= */
  var CFG = { providers: [], cur: null, keys: {} };

  function openSheet(title, html) {
    shTitle.textContent = title;
    shBody.innerHTML = html;
    mask.classList.add('on');
    sheet.classList.add('on');
  }
  function closeSheet() {
    mask.classList.remove('on');
    sheet.classList.remove('on');
  }
  function chipText() {
    var c = CFG.cur;
    if (!c || !c.provider) return '未配置模型';
    return (c.model || c.provider);
  }
  function refreshChip() { $('#mchip').textContent = chipText(); }

  function settings() {
    post({ a: 'getConfig' });
  }
  function renderSettings() {
    var p = CFG.cur && CFG.cur.provider;
    var prov = CFG.providers.filter(function (x) { return x.id === p; })[0] || CFG.providers[0];
    var models = prov ? prov.models : [];
    var h = '';
    h += '<div class="lbl">供应商</div><div class="sec" style="margin:0" id="provWrap">' +
      CFG.providers.map(function (x) {
        return '<div class="opt' + (x.id === p ? ' sel' : '') + '" data-p="' + esc(x.id) + '">' +
          '<span class="t">' + esc(x.name) + '</span><span class="d">' + esc(x.group || '') + '</span></div>';
      }).join('') + '</div>';
    h += '<div class="lbl">模型</div>' + models.map(function (m) {
      return '<div class="opt' + (m.id === (CFG.cur && CFG.cur.model) ? ' sel' : '') +
        '" data-m="' + esc(m.id) + '"><span class="t">' + esc(m.label || m.id) + '</span>' +
        (m.vision ? '<span class="d">看图</span>' : '') + '</div>';
    }).join('');
    if (!models.length) h += '<div class="sec">该供应商未预设模型，直接填模型名</div>';
    h += '<div class="lbl">模型名（可手填）</div>' +
      '<input class="f" id="fModel" placeholder="' + esc((prov && prov.baseModel) || 'model-id') +
      '" value="' + esc((CFG.cur && CFG.cur.model) || '') + '">';
    h += '<div class="lbl">API Key' + (prov && prov.keyHint ? '（' + esc(prov.keyHint) + '）' : '') + '</div>' +
      '<input class="f" id="fKey" type="password" placeholder="' +
      esc((CFG.keys && CFG.keys[p]) || '粘贴你的 Key') + '">';
    if (prov && prov.editableBaseUrl) {
      h += '<div class="lbl">Base URL（可改）</div>' +
        '<input class="f" id="fBase" value="' + esc((CFG.cur && CFG.cur.baseUrl) || prov.baseUrl) + '">';
    }
    h += '<div class="lbl">最多步数</div><div class="seg" id="fSteps">' +
      [['6', '少'], ['12', '中'], ['24', '多'], ['48', '极多']].map(function (x) {
        return '<span class="sg' + (String(CFG.cur && CFG.cur.steps) === x[0] ? ' sel' : '') +
          '" data-s="' + x[0] + '">' + x[1] + '</span>';
      }).join('') + '</div>';
    h += '<div class="lbl">温度</div><div class="seg" id="fTemp">' +
      [['0.2', '稳'], ['0.6', '中'], ['1', '活']].map(function (x) {
        return '<span class="sg' + (String(CFG.cur && CFG.cur.temperature) === x[0] ? ' sel' : '') +
          '" data-t="' + x[0] + '">' + x[1] + '</span>';
      }).join('') + '</div>';
    h += '<button class="btn" onclick="UI._save()">保存</button>';
    h += '<button class="btn ghost" onclick="UI.more()">工作区 / 技能 / 发布</button>';
    openSheet('设置', h);

    shBody.querySelectorAll('[data-p]').forEach(function (n) {
      n.onclick = function () {
        CFG.cur = CFG.cur || {};
        CFG.cur.provider = n.getAttribute('data-p');
        CFG.cur.model = '';
        renderSettings();
      };
    });
    shBody.querySelectorAll('[data-m]').forEach(function (n) {
      n.onclick = function () {
        CFG.cur = CFG.cur || {};
        CFG.cur.model = n.getAttribute('data-m');
        renderSettings();
      };
    });
    shBody.querySelectorAll('#fSteps .sg').forEach(function (n) {
      n.onclick = function () { CFG.cur.steps = +n.getAttribute('data-s'); renderSettings(); };
    });
    shBody.querySelectorAll('#fTemp .sg').forEach(function (n) {
      n.onclick = function () { CFG.cur.temperature = +n.getAttribute('data-t'); renderSettings(); };
    });
  }

  function save() {
    var o = {
      a: 'saveConfig',
      provider: CFG.cur && CFG.cur.provider,
      model: ($('#fModel') && $('#fModel').value.trim()) || (CFG.cur && CFG.cur.model),
      key: $('#fKey') ? $('#fKey').value.trim() : '',
      baseUrl: $('#fBase') ? $('#fBase').value.trim() : '',
      steps: CFG.cur && CFG.cur.steps,
      temperature: CFG.cur && CFG.cur.temperature
    };
    post(o);
    closeSheet();
    toast('已保存');
  }

  function more() {
    var h = '<div class="opt" data-x="workspace"><span class="t">工作区</span>' +
      '<span class="d">选目录</span></div>' +
      '<div class="opt" data-x="skills"><span class="t">技能</span><span class="d">' +
      '内置技能库</span></div>' +
      '<div class="opt" data-x="publish"><span class="t">发布</span><span class="d">' +
      '预览 / 打包 / 上传</span></div>' +
      '<div class="opt" data-x="new"><span class="t">新对话</span></div>';
    openSheet('更多', h);
    shBody.querySelectorAll('[data-x]').forEach(function (n) {
      n.onclick = function () {
        var x = n.getAttribute('data-x');
        closeSheet();
        if (x === 'new') newChat(); else post({ a: x });
      };
    });
  }

  /* ================= 输入 ================= */
  function autosize() {
    inp.style.height = 'auto';
    inp.style.height = Math.min(inp.scrollHeight, 132) + 'px';
  }
  inp.addEventListener('input', autosize);
  inp.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); tapSend(); }
  });
  sendBtn.onclick = function () { tapSend(); };

  function tapSend() {
    if (S.busy) { post({ a: 'stop' }); return; }
    var t = inp.value.trim();
    if (!t) return;
    inp.value = ''; autosize();
    addUser(t);
    S.cur = null;
    post({ a: 'send', text: t });
  }

  function newChat() { post({ a: 'newChat' }); }
  function jump() { toBottom(true); }

  stream.addEventListener('scroll', function () {
    $('#jump').style.display = near() ? 'none' : 'flex';
  });

  /* ================= 原生下行事件 ================= */
  function on(e) {
    if (!e || !e.t) return;
    switch (e.t) {
      case 'clear': clear(); break;
      case 'title': $('#title').textContent = e.text || '新对话'; break;
      case 'user': addUser(e.text || ''); break;
      case 'text':
        if (e.final) { endThink(); setText(e.text || ''); }
        else { endThink(); appendText(e.text || '', false); }
        break;
      case 'textdelta': appendText(e.text || '', false); break;
      case 'thinking': appendThink(e.text || ''); break;
      case 'tool':
        endThink();
        toolRow(e.name || '', e.target || '', e.state || 'run', e.head, e.full);
        if (e.state !== 'run') toBottom();
        break;
      case 'image': addShots([{ src: e.src, path: e.path || e.src }]); break;
      case 'card': addCard(e.kind, e.k || '', e.v || ''); break;
      case 'error': endThink(); addError(e.text || ''); break;
      case 'sys': addSys(e.text || ''); break;
      case 'status': setStat(e.text || ''); break;
      case 'tick':
        setStat('正在工作… ' + fmtDur(e.sec) +
          (e.tok > 0 ? ' · ' + e.tok + ' tok/s' : '') +
          (e.total ? ' · 累计 ' + e.total + ' tok' : ''));
        paintToolsHead(true);
        break;
      case 'step': setStep(e.n); break;
      case 'summary': addSummary(e.ms || 0, e.steps || 0, !!e.verified, e.sections || []); break;
      case 'busy': setBusy(!!e.on); if (!e.on) { endThink(); appendText('', true); } break;
      case 'toast': toast(e.text || ''); break;
      case 'config':
        CFG.providers = e.providers || [];
        CFG.cur = e.cur || {};
        CFG.keys = e.keys || {};
        refreshChip();
        renderSettings();
        break;
      default: console.log('unknown event', e);
    }
  }

  window.UI = {
    on: on, settings: settings, closeSheet: closeSheet, save: save, _save: save,
    more: more, newChat: newChat, jump: jump, tapSend: tapSend,
    toast: toast, post: post
  };
  window.addEventListener('error', function (ev) {
    try { post({ a: 'jserr', text: String(ev.message) }); } catch (x) {}
  });
})();