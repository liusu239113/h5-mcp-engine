/* ==========================================================================
   Hexora UI 小助手（可选，不引入也能用纯 CSS 组件）
   用法：<script src="ui/components.js"></script>
   ========================================================================== */
(function (g) {
  'use strict';

  var HX = g.HexUI || {};

  /** 弹一个风格化的提示条（替代 alert） */
  HX.toast = function (msg, ms) {
    var el = document.createElement('div');
    el.className = 'hx-toast';
    el.textContent = msg;
    document.body.appendChild(el);
    setTimeout(function () { el.remove(); }, ms || 1600);
  };

  /** 通用确认弹窗（替代 confirm），返回 Promise<boolean> */
  HX.confirm = function (title, okText, cancelText) {
    return new Promise(function (resolve) {
      var mask = document.createElement('div');
      mask.className = 'hx-mask';
      mask.innerHTML =
        '<div class="hx-panel hx-dialog">' +
        '<div class="hx-title">' + (title || '确定吗？') + '</div>' +
        '<div class="hx-dialog__foot">' +
        '<button class="hx-btn hx-btn--ghost">' + (cancelText || '再想想') + '</button>' +
        '<button class="hx-btn hx-btn--primary">' + (okText || '确定') + '</button>' +
        '</div></div>';
      var btns = mask.querySelectorAll('button');
      btns[0].onclick = function () { mask.remove(); resolve(false); };
      btns[1].onclick = function () { mask.remove(); resolve(true); };
      document.body.appendChild(mask);
    });
  };

  /** 数值条：设置百分比（自动夹到 0~100，并补个平滑过渡） */
  HX.setBar = function (el, pct) {
    var i = el && el.querySelector('i');
    if (i) i.style.width = Math.max(0, Math.min(100, pct)) + '%';
  };

  /** 虚拟摇杆：绑到元素上，回调归一化后的 (x, y)，松手回零 */
  HX.joystick = function (el, onMove) {
    if (!el) return function () {};
    var knob = el.querySelector('.hx-joystick__knob');
    var R = 0, active = null;
    function calc(e) {
      var t = e.touches ? e.touches[0] : e;
      var r = el.getBoundingClientRect();
      if (!R) R = r.width / 2 - (knob ? knob.offsetWidth / 2 : 0) - 4;
      var x = t.clientX - (r.left + r.width / 2);
      var y = t.clientY - (r.top + r.height / 2);
      var d = Math.hypot(x, y) || 1;
      var k = Math.min(1, R / d);
      if (knob) knob.style.transform = 'translate(' + (x * k) + 'px,' + (y * k) + 'px)';
      onMove(Math.max(-1, Math.min(1, x / R)), Math.max(-1, Math.min(1, y / R)));
    }
    el.addEventListener('touchstart', function (e) { active = 1; calc(e); e.preventDefault(); }, { passive: false });
    el.addEventListener('touchmove', function (e) { if (active) { calc(e); e.preventDefault(); } }, { passive: false });
    function end() {
      active = null;
      if (knob) knob.style.transform = '';
      onMove(0, 0);
    }
    el.addEventListener('touchend', end);
    el.addEventListener('touchcancel', end);
    return end;
  };

  /** 存档：localStorage 封装（带默认值与坏数据兜底） */
  HX.save = function (key, value) {
    try {
      if (value === undefined) {
        var raw = localStorage.getItem('hx:' + key);
        return raw ? JSON.parse(raw) : null;
      }
      localStorage.setItem('hx:' + key, JSON.stringify(value));
    } catch (e) {}
    return null;
  };

  g.HexUI = HX;
})(window);