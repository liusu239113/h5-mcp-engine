/*
 * 本地框架加载器（随 App 打包，离线可用）
 *
 * 用法：
 *   Engine.lib('phaser').then(function () {  /* 开始写游戏 */ });
 *   Engine.lib(['phaser', 'howler']).then(...)
 *
 * 加载顺序：先试本地 assets/lib，失败再退回 CDN。
 */
(function (global) {
  'use strict';

  var LOCAL = 'https://appassets.androidplatform.net/lib/';
  var CDN = {
    'phaser': ['https://cdn.jsdelivr.net/npm/phaser@3.80.1/dist/phaser.min.js', 'https://unpkg.com/phaser@3.80.1/dist/phaser.min.js'],
    'pixi': ['https://cdn.jsdelivr.net/npm/pixi.js@7.4.2/dist/pixi.min.js', 'https://unpkg.com/pixi.js@7.4.2/dist/pixi.min.js'],
    'three': ['https://cdn.jsdelivr.net/npm/three@0.137.0/build/three.min.js', 'https://unpkg.com/three@0.137.0/build/three.min.js'],
    'matter': ['https://cdn.jsdelivr.net/npm/matter-js@0.19.0/build/matter.min.js', 'https://unpkg.com/matter-js@0.19.0/build/matter.min.js'],
    'p5': ['https://cdn.jsdelivr.net/npm/p5@1.9.0/lib/p5.min.js', 'https://unpkg.com/p5@1.9.0/lib/p5.min.js'],
    'howler': ['https://cdn.jsdelivr.net/npm/howler@2.2.4/dist/howler.min.js', 'https://unpkg.com/howler@2.2.4/dist/howler.min.js']
  };
  var FILES = {
    'phaser': 'phaser.min.js',
    'pixi': 'pixi.min.js',
    'three': 'three.min.js',
    'matter': 'matter.min.js',
    'matter_tools': 'matter.min.js',
    'p5': 'p5.min.js',
    'howler': 'howler.min.js'
  };
  var GLOBAL_NAME = {
    'phaser': 'Phaser', 'pixi': 'PIXI', 'three': 'THREE',
    'matter': 'Matter', 'matter_tools': 'Matter', 'p5': 'p5', 'howler': 'Howl'
  };

  var cache = {};

  function inject(url) {
    return new Promise(function (resolve, reject) {
      var s = document.createElement('script');
      s.src = url;
      s.async = false;
      s.onload = function () { resolve(url); };
      s.onerror = function () { reject(new Error('加载失败: ' + url)); };
      document.head.appendChild(s);
    });
  }

  /** 依次尝试多个 CDN 地址 */
  function tryList(list, i) {
    if (!list || i >= list.length) return Promise.reject(new Error('全部 CDN 地址都失败'));
    return inject(list[i]).catch(function () { return tryList(list, i + 1); });
  }

  function one(key) {
    if (cache[key]) return cache[key];
    var file = FILES[key];
    if (!file) return Promise.reject(new Error('未知框架: ' + key));
    var name = GLOBAL_NAME[key];
    if (name && global[name]) { cache[key] = Promise.resolve(key); return cache[key]; }

    cache[key] = inject(LOCAL + file).catch(function () {
      // 本地没有（比如构建时没下载成功）就退回 CDN（多地址依次尝试）
      return tryList(CDN[key], 0);
    }).then(function () { return key; });
    return cache[key];
  }

  global.Engine = global.Engine || {};
  global.Engine.lib = function (names) {
    var list = Array.isArray(names) ? names : [names];
    return Promise.all(list.map(one));
  };
  global.Engine.libList = Object.keys(FILES);
  global.Engine.libLocalBase = LOCAL;
})(window);
