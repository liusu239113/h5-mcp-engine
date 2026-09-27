/* ============================================================================
 * adkit.js — 激励视频统一入口（随 App 打包，AI 可直接复制进游戏）
 *
 * 设计来源：拆包分析 H5_942712（方舟·足球线）的广告桥，把它的全部纪律抄齐：
 *   ① 只有「用户点击」能触发广告；加载期零副作用
 *   ② 同一时间只允许一个广告流程（单飞 busy），否则玩家连点会拉多个流程
 *   ③ 成功唯一定义：code===200 且 data.rewarded===true（或 TapTap 的 isEnded===true）
 *   ④ 失败绝不补发奖励（补发 = 白嫖，历史缺陷形状）
 *   ⑤ 看门狗 90s：底座 Promise 永不 settle 时复位，否则玩家卡死到只能刷新页面
 *   ⑥ 流程纪元 flowSeq：迟到结果丢弃，防一次广告发两份奖励
 *   ⑦ 配额只在 onReward 里消耗（失败不扣，玩家可重试）
 *   ⑧ 每条出口都有玩家可读提示，绝不静默
 *
 * 三条真实通道（按优先级探测）：
 *   1. 热点资讯底座  window.ColorboxAI.vatask.completeRewardVideo()   → mode 'app'
 *   2. TapTap 小游戏  window.tap.createRewardedVideoAd({ adUnitId })   → mode 'tapapp'
 *   3. 都没有          → 'broken'（容器在、能力没注入）/ 'blocked'（纯网页）
 *      ★ 这两条都「不发奖 + 明确提示」，不允许降级成假发奖
 *
 * 用法：
 *   AdKit.toast = UIkit.toast;                 // 可选：接管提示
 *   AdKit.track = function (kind, placement) { ... };   // 可选：埋点
 *   AdKit.onBeforeShow = function () { State.save(); }; // 可选：广告前存档
 *   AdKit.preload();                            // 启动后预热（只拉素材，不弹界面）
 *   AdKit.show('heal', function () {            // onReward：只有真完播才进来
 *     AdKit.consumeDaily(state, 'heal');        // 配额在此消耗
 *     ...发奖...
 *   }, function (reason) { ...失败出口... });    // onFail 可选
 * ========================================================================== */
(function (g) {
    'use strict';

    var WATCHDOG_MS = 90000;

    /* ── 平台失败原因 → 玩家可读文案（禁 emoji / 禁写死运行时数字）── */
    var REASON_TEXT = {
        APP_REQUIRED: '请在 App 内打开本页面，看完视频即可领取奖励',
        LOGIN_REQUIRED: '请先登录账号，再看完视频领取奖励',
        UNAVAILABLE: '该奖励暂时不可用或本期次数已用完，请稍后再试',
        NOT_REWARDED: '视频没有完整播完，本次未获得奖励；完整观看后即可领取',
        REQUEST_FAILED: '广告请求失败，请稍后再试',
        REWARD_FLOW_FAILED: '奖励发放流程异常，请稍后再试'
    };
    var GENERIC_FAIL = '未能获得奖励，请稍后再试';
    var BUSY_TEXT = '广告正在播放，看完后即可领取奖励';
    var BROKEN_TEXT = '广告能力暂不可用，请稍后重试';
    var TIMEOUT_TEXT = '广告加载超时，请稍后重试';
    var BLOCKED_TEXT = '请在 App 内打开本页面，才有真实广告可以领取奖励';
    var SETTLE_TEXT = '上一步奖励正在结算，请稍候';

    /* 开发调试开关：本地来源 + ?admock=1 才生效；发布前必须置 false（构建期消除） */
    var MOCK_ENABLED = true;

    var busy = false, busyText = BUSY_TEXT, lastReason = '', flowSeq = 0;

    /* ── 可替换出口 ── */
    function toast(msg, color) {
        try { if (typeof g.AdKit.toast === 'function') { g.AdKit.toast(msg, color || ''); return; } } catch (e) { }
        try { if (g.UIkit && typeof g.UIkit.toast === 'function') { g.UIkit.toast(msg, color || ''); return; } } catch (e) { }
        try { console.log('[adkit] ' + msg); } catch (e) { }
    }
    function log(placement, ev, extra) {
        try { console.log('[adkit] ' + placement + ' ' + ev + (extra ? ' ' + extra : '')); } catch (e) { }
    }
    function track(kind, placement, extra) {
        try { if (typeof g.AdKit.track === 'function') g.AdKit.track(kind, placement, extra || {}); } catch (e) { }
    }
    function briefMsg(m) { return (typeof m === 'string' && m.trim()) ? m.trim().slice(0, 60) : ''; }

    /* ══════════ 通道探测 ══════════ */
    var adUnitIdOverride = '';

    function vatask() {
        try {
            var v = g.ColorboxAI && g.ColorboxAI.vatask;
            return (v && typeof v.completeRewardVideo === 'function') ? v : null;
        } catch (e) { return null; }
    }
    function hasVatask() { return !!vatask(); }

    function tapBridge() {
        try {
            var t = g.tap;
            return (t && typeof t.createRewardedVideoAd === 'function') ? t : null;
        } catch (e) { return null; }
    }

    /** 广告位 id 优先级：setAdUnitId > window.TAP_AD_UNIT_ID > meta[name=tap-ad-unit] */
    function adUnitId() {
        if (adUnitIdOverride) return adUnitIdOverride;
        try { if (g.TAP_AD_UNIT_ID) return String(g.TAP_AD_UNIT_ID); } catch (e) { }
        try {
            var m = document.querySelector('meta[name="tap-ad-unit"]');
            if (m && m.getAttribute('content')) return m.getAttribute('content');
        } catch (e) { }
        return '';
    }

    function inContainer() {
        try { return !!g.ColorboxAI; } catch (e) { return true; }   /* fail-closed：探测异常按"在容器内" */
    }
    function localOrigin() {
        var loc;
        try { loc = g.location; } catch (e) { return false; }
        if (!loc) return true;
        var proto = '', host = '';
        try { proto = String(loc.protocol || ''); host = String(loc.hostname || ''); } catch (e) { return false; }
        if (!host) {
            try {
                var mm = /^([a-zA-Z][a-zA-Z0-9+.-]*):\/\/([^/?#:]*)/.exec(String(loc.href || ''));
                if (mm) { if (!proto) proto = mm[1] + ':'; host = mm[2]; }
            } catch (e) { return false; }
        }
        if (proto === 'file:') return true;
        host = host.replace(/^\[|\]$/g, '').toLowerCase();
        return host === 'localhost' || host === '127.0.0.1' || host === '::1';
    }
    function admockOptIn() {
        if (!MOCK_ENABLED) return false;
        if (!localOrigin()) return false;
        var q = '';
        try { q = String((g.location && g.location.search) || ''); } catch (e) { return false; }
        if (!q) return false;
        var segs = q.replace(/^\?/, '').split('&');
        for (var i = 0; i < segs.length; i++) {
            var j = segs[i].indexOf('=');
            var k = j < 0 ? segs[i] : segs[i].slice(0, j);
            if (k === 'admock') return (j < 0 ? '' : decodeURIComponent(segs[i].slice(j + 1))) === '1';
        }
        return false;
    }

    /** app / tapapp / broken / mock / blocked —— 唯一权威判定 */
    function mode() {
        if (hasVatask()) return 'app';
        if (tapBridge() && adUnitId()) return 'tapapp';
        if (inContainer()) return 'broken';
        return admockOptIn() ? 'mock' : 'blocked';
    }

    /* ══════════ TapTap 桥：组件单例复用 + 回调单槽 ══════════
       官方契约（TapTap 小游戏 激励视频广告）：
         · tap.createRewardedVideoAd({adUnitId}) 是**单例组件**，多次调用返回同一实例
         · 组件创建后**自动拉取素材**，成功走 onLoad()，失败走 onError(err)
         · show() 返回 Promise；素材未就绪时 rejected → 建议 load().then(show) 重试
         · 只有用户点关闭或播放完毕才会关闭，开发者不能主动隐藏/关闭
         · onClose(res) 的 res.isEnded === true 才允许发奖（唯一发奖判据） */
    var _tapAd = null, _tapResolve = null, _preloaded = false, tapReady = false;

    function tapEnsureAd(t, id) {
        if (_tapAd) return _tapAd;
        var ad = t.createRewardedVideoAd({ adUnitId: id });
        if (typeof ad.onLoad === 'function') {
            ad.onLoad(function () { tapReady = true; log('preload', 'tap-ad-ready'); });
        }
        ad.onClose(function (res) {
            var r = _tapResolve; _tapResolve = null;
            if (!r) return;
            r(res && res.isEnded
                ? { code: 200, data: { rewarded: true } }
                : { code: 200, data: { rewarded: false, reason: 'NOT_REWARDED' } });
        });
        ad.onError(function (err) {
            var r = _tapResolve; _tapResolve = null;
            if (r) r({ code: 500, message: (err && (err.errMsg || err.errorMessage)) || '', data: { reason: 'REQUEST_FAILED' } });
        });
        _tapAd = ad;
        return ad;
    }

    function tapRewardedPromise(t, id) {
        return new Promise(function (resolve) {
            var done = false;
            function finish(res) { if (!done) { done = true; _tapResolve = null; resolve(res); } }
            try { if (typeof g.AdKit.onBeforeShow === 'function') g.AdKit.onBeforeShow(); } catch (e) { }
            var ad;
            try { ad = tapEnsureAd(t, id); }
            catch (e) { finish({ code: 500, data: { reason: 'REQUEST_FAILED' } }); return; }
            _tapResolve = function (res) { finish(res); };
            try {
                Promise.resolve(ad.show()).catch(function () {
                    try {
                        Promise.resolve(ad.load())
                            .then(function () { return Promise.resolve(ad.show()); })
                            .catch(function () { finish({ code: 500, data: { reason: 'REQUEST_FAILED' } }); });
                    } catch (e2) { finish({ code: 500, data: { reason: 'REQUEST_FAILED' } }); }
                });
            } catch (e) { finish({ code: 500, data: { reason: 'REQUEST_FAILED' } }); }
        });
    }

    /** 启动预热（只拉素材，不弹界面）；非 tap 通道静默 no-op。
       官方口径：激励视频组件**创建后会自动拉取素材**，所以「创建实例」本身就是预热；
       这里在创建后再补一次 load()（幂等，用于刷新素材），完成后 onLoad 会置 tapReady。 */
    function preload() {
        if (_preloaded) return false;
        if (mode() !== 'tapapp') return false;
        var t = tapBridge(), id = adUnitId();
        if (!t || !id) return false;
        _preloaded = true;
        log('preload', 'tap-load-start');
        try {
            Promise.resolve(tapEnsureAd(t, id).load()).then(
                function () { log('preload', 'tap-load-ok'); },
                function (e) { log('preload', 'tap-load-fail', (e && e.errMsg) || ''); }
            );
        } catch (e) { log('preload', 'tap-load-throw', e && e.message); }
        return true;
    }

    /* ══════════ 统一流程机 ══════════ */
    function failByReason(res, placement) {
        var reason = (res && res.data && res.data.reason) || '';
        lastReason = reason || ('code ' + ((res && res.code) != null ? res.code : '?'));
        var msg = REASON_TEXT[reason] || briefMsg(res && res.message) || GENERIC_FAIL;
        log(placement, 'fail', lastReason);
        track('ad_fail', placement, { reason: reason || lastReason });
        toast(msg, reason === 'APP_REQUIRED' ? '' : 'red');
    }

    function runFlow(placement, onReward, onFail, transport) {
        var me = ++flowSeq;
        busy = true;
        busyText = BUSY_TEXT;

        var wd = null;
        function clearWd() { if (wd != null) { try { clearTimeout(wd); } catch (e) { } wd = null; } }
        try {
            wd = setTimeout(function () {
                wd = null;
                if (!busy) return;
                busy = false;
                lastReason = 'TIMEOUT';
                log(placement, 'timeout');
                track('ad_fail', placement, { reason: 'TIMEOUT' });
                toast(TIMEOUT_TEXT, 'red');
                try { if (onFail) onFail('TIMEOUT'); } catch (e) { }
            }, WATCHDOG_MS);
        } catch (e) { wd = null; }

        var p;
        try { p = transport(); }
        catch (e) {
            clearWd(); busy = false; lastReason = 'THROW';
            log(placement, 'throw', e && e.message);
            track('ad_fail', placement, { reason: 'THROW' });
            toast(GENERIC_FAIL, 'red');
            try { if (onFail) onFail('THROW'); } catch (e2) { }
            return;
        }

        Promise.resolve(p).then(function (res) {
            clearWd();
            if (me !== flowSeq) { log(placement, 'late-drop', res && res.code); return; }
            busy = false;
            if (res && res.code === 200 && res.data && res.data.rewarded === true) {
                lastReason = '';
                log(placement, 'rewarded');
                track('ad_reward', placement, {});
                try { onReward(); }
                catch (e) {
                    log(placement, 'reward-cb-error', e && e.message);
                    toast('奖励发放出错，请稍后重试', 'red');
                }
                return;
            }
            failByReason(res, placement);
            try { if (onFail) onFail((res && res.data && res.data.reason) || 'FAIL'); } catch (e) { }
        }, function (err) {
            clearWd();
            if (me !== flowSeq) { log(placement, 'late-drop-reject'); return; }
            busy = false;
            lastReason = 'REJECT';
            log(placement, 'reject', err && err.message);
            track('ad_fail', placement, { reason: 'REJECT' });
            toast(GENERIC_FAIL, 'red');
            try { if (onFail) onFail('REJECT'); } catch (e) { }
        });
    }

    /* ══════════ 对外入口 ══════════ */
    function show(placement, onReward, onFail) {
        if (typeof onReward !== 'function') { log(placement, 'bad-callback'); return; }
        if (busy) { log(placement, 'busy-skip'); toast(busyText || BUSY_TEXT, ''); return; }
        var m = mode();

        if (m === 'app') {
            var vt = vatask();
            runFlow(placement, onReward, onFail, function () { return vt.completeRewardVideo(); });
            return;
        }
        if (m === 'tapapp') {
            var t = tapBridge(), id = adUnitId();
            runFlow(placement, onReward, onFail, function () { return tapRewardedPromise(t, id); });
            return;
        }
        if (m === 'mock') {
            busy = true; busyText = SETTLE_TEXT;
            var me = ++flowSeq;
            setTimeout(function () {
                if (me !== flowSeq) { busy = false; return; }
                busy = false; onReward();
            }, 120);
            return;
        }
        if (m === 'broken') {
            lastReason = 'NO_VATASK'; log(placement, 'broken');
            toast(BROKEN_TEXT, 'red');
            try { if (onFail) onFail('NO_VATASK'); } catch (e) { }
            return;
        }
        lastReason = 'APP_REQUIRED'; log(placement, 'blocked');
        toast(BLOCKED_TEXT, '');
        try { if (onFail) onFail('APP_REQUIRED'); } catch (e) { }
    }

    /* ══════════ 配额助手（懒重置，失败不扣）══════════
       用法：if (AdKit.dailyLeft(state, 'heal') <= 0) return 提示;
             Ads 成功后 AdKit.consumeDaily(state, 'heal'); */
    function _bag(state) {
        state.data.usage = state.data.usage || {};
        return state.data.usage;
    }
    function dayKey(state) {
        var d = state.data.date || {};
        return d.year + '-' + d.month + '-' + d.day;
    }
    /** 每日桶：换天自动清空（懒重置，无时序风险） */
    function _day(state) {
        var u = _bag(state), k = dayKey(state);
        if (!u._day || u._day.key !== k) u._day = { key: k, n: {} };
        return u._day.n;
    }
    /** 赛季桶 */
    function _season(state) {
        var u = _bag(state);
        if (!u._season || u._season.season !== state.data.season) u._season = { season: state.data.season, n: {} };
        return u._season.n;
    }
    function dailyLeft(state, key, cap) { return Math.max(0, (cap || 1) - (_day(state)[key] || 0)); }
    function consumeDaily(state, key) { var n = _day(state); n[key] = (n[key] || 0) + 1; }
    function seasonLeft(state, key, cap) { return Math.max(0, (cap || 1) - (_season(state)[key] || 0)); }
    function consumeSeason(state, key) { var n = _season(state); n[key] = (n[key] || 0) + 1; }

    g.AdKit = {
        VERSION: '1.0.0',
        WATCHDOG_MS: WATCHDOG_MS,
        show: show,
        preload: preload,
        mode: mode,
        isBusy: function () { return busy; },
        isReady: function () { return tapReady; },   /* tap 通道：素材是否已 load 完 */
        lastReason: function () { return lastReason; },
        isLocal: localOrigin,
        setAdUnitId: function (id) { adUnitIdOverride = id ? String(id) : ''; _tapAd = null; _preloaded = false; return adUnitIdOverride; },
        getAdUnitId: adUnitId,
        /* 可替换出口 */
        toast: null,
        track: null,
        onBeforeShow: null,
        /* 配额 */
        dailyLeft: dailyLeft, consumeDaily: consumeDaily,
        seasonLeft: seasonLeft, consumeSeason: consumeSeason,
        REASON_TEXT: REASON_TEXT
    };
})(typeof window !== 'undefined' ? window : this);
