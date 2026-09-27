#!/usr/bin/env node
/**
 * Hexora 内置「stdio → Streamable HTTP」桥（v2）
 *
 * 背景：@taptap/maker 只提供 stdio 传输；stdio 管道绑死在父进程上，
 * 一旦 App 主界面被系统冻结/回收，管道就断，Maker 跟着死。
 * 而 Hexora 的守护体系（独立进程 :mcp + 12 秒巡检 + 自动拉起）只认 HTTP 服务。
 *
 * 所以这里把它包成 HTTP：bridge.js <目标脚本> <端口> [--cwd 目录] [--target-arg 字段名]
 *   GET  /health  → {"ok":true,...}   给守护巡检用
 *   POST /        → MCP JSON-RPC（透传 stdio，按 id 配对响应）
 *
 * 另外自动给工具调用注入 target_dir（Maker 靠它定位项目，因为 App 侧没有 MCP Roots）。
 *
 * v2 修了两个把「生图能力」整个搞没的坑：
 *   1. 子进程退出后**永不重启**。旧版 bridge 只是把 childAlive 置 false，
 *      而 /health 照样回 200，App 侧 makerReady() 又只看 HTTP 200 →
 *      于是「桥活着 + 子进程死了」被当成「Maker 已就绪」，从此工具数永远是 0，
 *      用户授权了也没有 generate_image。现在子进程退出会自动拉起（带退避）。
 *   2. 子进程没继承 node 的 CA / DNS 修正参数。父进程是带着 --use-bundled-ca 和
 *      dnsfix.js 起的，但 spawn 出来的子进程裸跑，它自己发起的网络请求会因为
 *      musl 读不到 Android DNS、找不到 CA 而失败。现在通过 MAKER_CHILD_ARGS 透传。
 */
const { spawn, spawnSync } = require('child_process');
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const os = require('os');

const argv = process.argv.slice(2);
const target = argv[0];
const port = parseInt(argv[1] || '3011', 10);
function flag(name, def) {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : def;
}
const cwd = flag('--cwd', process.cwd());
const targetArg = flag('--target-arg', 'target_dir');
const projectDir = flag('--project', '');

if (!target) {
  console.error('usage: bridge.js <target.js> <port> [--cwd dir] [--project dir] [--target-arg name]');
  process.exit(2);
}

// ==================== Maker 项目自动绑定（v3） ====================
// 为什么必须有这一段：
//   generate_image / text_to_music / text_to_sound_effect 这类「素材生成」工具，
//   进门第一件事是 identifyMakerProject() —— 它只认 <项目根>/.maker-mcp/config.json
//   里的 project_id。缺这个文件，工具直接报：
//     "xxx is not bound to a Maker project. .maker-mcp/config.json is missing."
//   而官方唯一的绑定路径 `taptap-maker init`，第一行就是 ensureGitAvailable()；
//   安卓沙箱里既没有 git 也没有 python，init 根本走不到「写 config」那一步。
//   → 死循环：想生图就得绑定；想绑定就得有 git；没有 git 就永远生不了图（只能用户手动去建项目）。
//
// 读 dist/maker.js 得到的事实：
//   · 绑定判定只看 config.json 里的 project_id（loadProjectConfig / parseConfig）
//   · 列项目 GET  {apiBase}/apps  —— 不需要 git
//   · 建项目 POST {apiBase}/apps  —— 不需要 git
//   · init 的顺序是「选项目 → 先写 config → 再 git clone」，git 只有 clone 才需要
// 所以绑定完全可以由桥自己完成：查项目 → 没有就建 → 写 config。用户零操作。
const CFG_DIR = '.maker-mcp';
const PROJECT_SUBDIRS = ['assets', 'assets/image', 'assets/sprites', 'assets/video', 'assets/audio', 'scripts', 'ui'];
// 素材/环境相关工具：调它们之前必须先确保工程已绑定，否则必报未绑定
const GEN_TOOLS = new Set([
  'generate_image', 'batch_generate_images', 'edit_image',
  'create_video_task', 'query_video_task', 'create_3d_asset',
  'text_to_music', 'text_to_sound_effect', 'batch_sound_effects',
  'text_to_dialogue', 'audition_voices_for_character', 'confirm_character_voice',
  'generate_test_qrcode', 'add_test_whitelist', 'get_ad_config',
]);

const LOCAL_TOOL = {
  name: 'maker_ensure_project',
  description:
    '确保当前工程已绑定一个 Maker 项目（没有就自动新建一个），素材生成类工具必须先满足这个条件。' +
    '桥在调用 generate_image / text_to_music / text_to_sound_effect 等工具前会自动完成绑定，' +
    '所以通常你不需要手动调用它；只有自动绑定失败、或用户明确要求「新建一个项目」时才用，' +
    '传 create=true 表示强制新建。',
  inputSchema: {
    type: 'object',
    properties: {
      create: { type: 'boolean', description: 'true = 强制新建一个 Maker 项目（默认复用同名项目）' },
      dir: { type: 'string', description: '要绑定的工程目录，默认就是当前工程' },
    },
    required: [],
  },
};

/**
 * 本地工具 2：列 Maker 项目。
 *
 * 为什么必须有它：用户问「我的 Maker 项目都有什么」时，模型以前只能去抓旁边那套
 * TapTap 开放平台 MCP 的 list_developers_and_apps —— 那条要 OAuth 授权，于是回答永远变成
 * 「授权失败 / 请点这条授权链接」。而这个列表本身就是 GET {apiBase}/apps + PAT：
 * 设置页「查看我的应用」用的就是它，一次授权都不需要，跟 MCP 那套毫无关系。
 */
const LOCAL_LIST_TOOL = {
  name: 'maker_list_apps',
  description:
    '列出当前 PAT 名下全部 TapTap Maker 项目（就是设置页的「我的应用」），并标注当前工程已绑定哪一个。' +
    '这是查询 Maker 项目的唯一正确方式：走 Maker PAT，不需要 OAuth 授权、不需要 git、也不依赖 Maker 子进程。' +
    '用户问「我的 Maker 项目/应用都有什么」时用它，不要改用 TapTap 开放平台的开发者接口。',
  inputSchema: { type: 'object', properties: {}, required: [] },
};

/**
 * 本地工具 3：列 UI 风格包。
 *
 * 为什么放在桥里：它跟素材生成、授权、git 都无关，纯粹是读一批 CSS 模板 —— 放在本地
 * 就意味着 Maker 子进程挂掉时，AI 照样能挑风格、照样能建界面。
 */
const UI_LIST_TOOL = {
  name: 'maker_ui_list_kits',
  description:
    '列出引擎预制的 UI 风格包（水墨国风 / 像素复古 / 卡通圆润 / 动漫玻璃），并标出当前项目用的是哪一套。' +
    '做任何界面之前**先**调它，按游戏题材挑一套：武侠/仙侠→ink，像素/怀旧→pixel16，休闲/卡通→cartoon，二次元/卡牌→anime。' +
    '绝不允许使用浏览器原生默认样式（灰按钮、裸 sans-serif、alert 当弹窗）。' +
    '本地工具：不需要授权、不依赖 Maker 子进程。',
  inputSchema: { type: 'object', properties: {}, required: [] },
};

const UI_APPLY_TOOL = {
  name: 'maker_ui_apply_kit',
  description:
    '把指定的 UI 风格包落地到当前项目的 ui/ 目录（theme.css + components.css + components.js + assets）。' +
    '落地后页面里用相对路径引用 ui/theme.css 与 ui/components.css，组件类名与用法照 ui/SPEC.md 抄；' +
    '所有颜色/圆角/字体必须写 var(--hx-*)，禁止硬编码色值。' +
    '换风格只要再调一次本工具，界面结构一行都不用改。',
  inputSchema: {
    type: 'object',
    properties: {
      kit: {
        type: 'string',
        description: '风格 id：ink（水墨国风）/ pixel16（像素复古）/ cartoon（卡通圆润）/ anime（动漫玻璃）',
      },
      dir: { type: 'string', description: '目标工程目录，默认就是当前工程' },
    },
    required: ['kit'],
  },
};

/**
 * 本地工具 5：抠图 / 去背景（走抠抠图在线 API）。
 *
 * 为什么要它：游戏素材里「去掉背景」是高频需求（角色立绘、道具、图标）。
 * 以前 AI 只能手写 canvas 色键抠图 —— 对真实照片/复杂背景完全无效，出来是脏边，
 * 用户看到的就「这 AI 不会抠图」。现在直接把项目里的图片丢给在线抠图，拿回透明 PNG。
 *
 * 为什么走 API 而不是让 AI 去点那个网页：网页要上传文件、等结果、再下载，
 * 靠 UI 自动化做既慢又脆（对方一改版就废）。它自己有公开接口，直接调就完了。
 *
 * 密钥：设置 → 图片工具（抠图）里粘贴一次，或直接改 <运行时>/home/koukoutu.json。
 * 计费：抠图 1 积分 / 张；output_format=png 额外 +1。
 */
const BG_TOOL = {
  name: 'maker_remove_bg',
  description:
    '把项目里的一张图片抠成透明背景（AI 去背景，支持发丝/复杂背景），结果存成 PNG 回项目。' +
    '素材要「去掉背景」时必须用它，不要自己写 canvas 色键抠图（那对真实照片无效，只会出脏边）。' +
    '需要先在 设置 → 图片工具（抠图） 里粘一次 API Key（申请地址 https://www.koukoutu.com/user/dev ）。' +
    '每次调用计费 1 积分（输出 png 再 +1），一次一张图；要批量就多调几次。',
  inputSchema: {
    type: 'object',
    properties: {
      image: {
        type: 'string',
        description: '要抠的图片：项目内相对路径（如 assets/image/hero.png）或绝对路径',
      },
      out: {
        type: 'string',
        description: '结果保存路径，默认与源图同目录、同名的 -nobg.png',
      },
      format: {
        type: 'string',
        description: 'png（默认，透明边缘最稳，+1 积分）或 webp（省积分）',
      },
      border: {
        type: 'integer',
        description: '边缘增强：0 不增强 / 1 标准（默认） / 2 高度增强 —— 发丝、毛发建议 2',
      },
      crop: {
        type: 'integer',
        description: '是否裁切到主体边缘：0 保留原画布（默认）/ 1 裁切',
      },
    },
    required: ['image'],
  },
};

/** 桥自带的本地工具全集（tools/list 时追加给 AI） */
const LOCAL_TOOLS = [LOCAL_TOOL, LOCAL_LIST_TOOL, UI_LIST_TOOL, UI_APPLY_TOOL, BG_TOOL];

function localTool(name) {
  return LOCAL_TOOLS.find((t) => t.name === name) || null;
}

/** 真正干活：读 PAT → GET /apps → 与当前绑定做对照 */
async function listMakerApps() {
  const token = readPatToken();
  if (!token) {
    return {
      ok: false,
      reason:
        '没有找到 Maker 凭证（pat.json）。到设置 → Maker 面板粘贴 PAT 或扫码登录授权一次即可 —— ' +
        '这一步与 TapTap 开放平台的 OAuth 授权完全是两回事，不要去点那类授权链接。',
    };
  }
  const base = apiBase();
  const listed = normalizeProjects(await httpsJson('GET', base + '/apps', token));
  const curDir = projectDir || cwd;
  const cur = (curDir && readBinding(curDir)) || null;
  const boundId = (binding && binding.project_id) || (cur && cur.project_id) || '';
  const apps = listed.map((p) => ({
    id: p.id,
    name: p.name,
    bound: !!(boundId && p.id === boundId),
    lastAccessedAt: p.lastAccessedAt || '',
    createdAt: p.createdAt || '',
  }));
  return {
    ok: true,
    count: apps.length,
    api_base: base,
    bound_project_id: boundId,
    current_dir: curDir,
    apps,
    note: '这是 Maker 项目列表（走 PAT，无需任何授权）。要绑定/新建请用 maker_ensure_project；素材生成类工具会自动完成绑定。',
  };
}

// ==================== UI 风格包（预制主题） ====================
// 目录来源：App 启动桥时把 assets/ui-kits 解包到 rt 目录并写进 HEXORA_UI_KITS；
// 没有就用 <工程根>/_ui-kits 兜底（方便用户自己往工程里丢主题）。
function kitsDir(tdir) {
  return process.env.HEXORA_UI_KITS || path.join(tdir || projectDir || cwd, '_ui-kits');
}

function readKitIndex(tdir) {
  try {
    return JSON.parse(fs.readFileSync(path.join(kitsDir(tdir), 'kit.json'), 'utf8'));
  } catch (e) {
    return null;
  }
}

/** 把 src 目录（递归）复制进 dst，只覆盖内容真的不同的文件；返回复制的文件数 */
function copyTree(src, dst) {
  let n = 0;
  fs.mkdirSync(dst, { recursive: true });
  for (const name of fs.readdirSync(src)) {
    const s = path.join(src, name);
    const d = path.join(dst, name);
    const st = fs.statSync(s);
    if (st.isDirectory()) {
      n += copyTree(s, d);
      continue;
    }
    let same = false;
    try {
      same = fs.statSync(d).size === st.size && fs.readFileSync(d).equals(fs.readFileSync(s));
    } catch {}
    if (!same) {
      fs.copyFileSync(s, d);
      n++;
    }
  }
  return n;
}

function uiListKits(tdir) {
  const idx = readKitIndex(tdir);
  if (!idx) {
    return {
      ok: false,
      reason: '没有找到 UI 风格包目录（' + kitsDir(tdir) + '）。请更新到带 ui-kits 的版本，' +
        '或者直接把主题目录放到工程根的 _ui-kits/ 下。',
    };
  }
  let current = null;
  try {
    current = JSON.parse(fs.readFileSync(path.join(tdir || projectDir || cwd, 'ui', '.kit.json'), 'utf8')).id;
  } catch {}
  return {
    ok: true,
    count: idx.kits.length,
    current,
    kits: idx.kits.map((k) => ({ id: k.id, name: k.name, scenes: k.scenes, mood: k.mood, in_use: k.id === current })),
    usage:
      '挑好之后调 maker_ui_apply_kit 落地。页面里用 <link rel="stylesheet" href="ui/theme.css"> + ui/components.css，' +
      '组件类名照 ui/SPEC.md 抄，颜色一律 var(--hx-*)，不要自己写死颜色。',
  };
}

function uiApplyKit(tdir, kit) {
  const idx = readKitIndex(tdir);
  if (!idx) return { ok: false, reason: '没有找到 UI 风格包目录（' + kitsDir(tdir) + '）' };
  const meta = idx.kits.find((k) => k.id === kit);
  if (!meta) {
    return { ok: false, reason: '没有这套风格：' + kit, available: idx.kits.map((k) => k.id) };
  }
  const root = tdir || projectDir || cwd;
  const dir = kitsDir(tdir);
  const dst = path.join(root, 'ui');
  // 共享样式（components.css / components.js / SPEC.md）和主题一起落地到项目 ui/。
  // 注意目录名**不能**以 `_` 开头：Android 打包 assets 时会忽略 `_` / `.` 开头的目录，
  // 曾经叫 ui-kits/_shared 时整个目录没进 APK，换肤后组件样式全部缺失。
  // 这里同时兼容旧名字，免得老设备上残留 _shared 时又出一次同样的坑。
  const sharedDir = fs.existsSync(path.join(dir, 'shared'))
    ? path.join(dir, 'shared')
    : path.join(dir, '_shared');
  const n = copyTree(path.join(dir, kit), dst) + copyTree(sharedDir, dst);
  fs.writeFileSync(
    path.join(dst, '.kit.json'),
    JSON.stringify({ id: kit, name: meta.name, mood: meta.mood, at: Date.now() }, null, 2),
    'utf8'
  );
  return {
    ok: true,
    id: kit,
    name: meta.name,
    files: n,
    where: path.relative(root, dst) || dst,
    usage:
      '页面按顺序引 ui/theme.css 与 ui/components.css（组件用法见 ui/SPEC.md），颜色/圆角/字体一律 var(--hx-*)。' +
      '换风格再调一次本工具即可，界面结构不用改。',
  };
}

/** tools/call 落到本地工具时的统一分发（不进 Maker 子进程） */
function handleLocalTool(res, msg, tname, tdir) {
  const args = (msg.params && msg.params.arguments) || {};
  const fail = (e) => reply(res, msg.id, { ok: false, reason: (e && e.message) || String(e) });
  if (tname === LOCAL_LIST_TOOL.name) {
    listMakerApps().then((r) => reply(res, msg.id, r)).catch(fail);
    return;
  }
  if (tname === UI_LIST_TOOL.name) {
    try {
      reply(res, msg.id, uiListKits(tdir));
    } catch (e) {
      fail(e);
    }
    return;
  }
  if (tname === UI_APPLY_TOOL.name) {
    try {
      reply(res, msg.id, uiApplyKit(tdir, args.kit || ''));
    } catch (e) {
      fail(e);
    }
    return;
  }
  if (tname === BG_TOOL.name) {
    // 抠图只走 HTTPS 接口，不经过 Maker 子进程 —— 子进程死了也能用
    removeBg(args.dir || tdir, args).then((r) => reply(res, msg.id, r)).catch(fail);
    return;
  }
  ensureBinding(args.dir || tdir, { forceNew: !!args.create }).then((r) => reply(res, msg.id, r)).catch(fail);
}

function makerHomeDir() {
  return process.env.TAPTAP_MAKER_HOME || path.join(os.homedir(), '.taptap-maker');
}
function apiBase() {
  return (process.env.TAPTAP_MAKER_API_BASE || 'https://maker.taptap.cn/api/v1').replace(/\/+$/, '');
}
function readPatToken() {
  try {
    const j = JSON.parse(fs.readFileSync(path.join(makerHomeDir(), 'pat.json'), 'utf8'));
    return j && j.token ? j.token : '';
  } catch {
    return '';
  }
}
function readBinding(root) {
  try {
    const j = JSON.parse(fs.readFileSync(path.join(root, CFG_DIR, 'config.json'), 'utf8'));
    return j && j.project_id ? j : null;
  } catch {
    return null;
  }
}
function writeBinding(root, project) {
  const dir = path.join(root, CFG_DIR);
  fs.mkdirSync(dir, { recursive: true });
  const now = new Date().toISOString();
  const cfg = { project_id: String(project.id), created_at: now, updated_at: now };
  const uid = project.user_id || project.userId;
  if (uid) cfg.user_id = uid;
  if (project.sce_endpoint) cfg.sce_endpoint = project.sce_endpoint;
  fs.writeFileSync(path.join(dir, 'config.json'), JSON.stringify(cfg, null, 2), 'utf8');
  // 与官方 saveProjectConfig 保持一致：凭证目录整体忽略
  fs.writeFileSync(path.join(dir, '.gitignore'), '*\n', 'utf8');
  for (const s of PROJECT_SUBDIRS) {
    try { fs.mkdirSync(path.join(root, s), { recursive: true }); } catch {}
  }
  return cfg;
}
function normalizeProjects(data) {
  const body = data || {};
  const list = Array.isArray(data)
    ? data
    : Array.isArray(body.data)
      ? body.data
      : Array.isArray(body.apps)
        ? body.apps
        : Array.isArray(body.projects)
          ? body.projects
          : [];
  return list
    .map((it) => ({
      id: String(it.id || it.app_id || it.project_id || ''),
      name: it.name || it.title || '',
      user_id: it.user_id || it.userId,
      sce_endpoint: it.sce_endpoint || it.sce_mcp_url || '',
      lastAccessedAt: it.lastAccessedAt || it.last_accessed_at || '',
      createdAt: it.createdAt || it.created_at || '',
    }))
    .filter((p) => p.id);
}

// 用 node:https 直接请求（不依赖全局 fetch，node 版本兼容性更稳）；
// CA 用 --use-bundled-ca，DNS 用 dnsfix.js，都是启动桥时已经带上的。
function httpsJson(method, url, token, body) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const payload = body ? JSON.stringify(body) : null;
    const headers = { Authorization: 'Bearer ' + token, Accept: 'application/json' };
    if (payload) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = Buffer.byteLength(payload);
    }
    const req = https.request(
      {
        method,
        hostname: u.hostname,
        port: u.port || 443,
        path: u.pathname + u.search,
        headers,
        timeout: 20000,
      },
      (res) => {
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (c) => (data += c));
        res.on('end', () => {
          let json;
          try { json = JSON.parse(data); } catch { json = { raw: data.slice(0, 300) }; }
          if (res.statusCode >= 200 && res.statusCode < 300) resolve(json);
          else reject(new Error('HTTP ' + res.statusCode + ' ' + data.slice(0, 240)));
        });
      }
    );
    req.on('timeout', () => req.destroy(new Error('Maker API 请求超时')));
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

// ==================== 抠图（抠抠图在线 API） ====================
// 文档：https://doc.koukoutu.com    申请 Key：https://www.koukoutu.com/user/dev
// 同步接口 1 积分/张（output_format=png 再 +1），并发上限 5。
const KOU_BASE = process.env.HEXORA_KOUKOUTU_BASE || 'https://sync.koukoutu.com';
const KOU_APPLY_URL = 'https://www.koukoutu.com/user/dev';

/**
 * 抠图 API Key 的读取顺序（顺序有讲究）：
 *   1) <HOME>/koukoutu.json 里的 key 字段 —— 设置页「图片工具（抠图）」保存时写这里，
 *      每次调用现读，所以**换 Key 不用重启桥**；
 *   2) 环境变量 HEXORA_KOUKOUTU_KEY —— App 启动桥时按同一份文件注入的兜底值。
 * 都没有就返回空串，由调用方给用户一条「去哪拿 Key」的明确指引。
 */
function kouKey() {
  try {
    const home = process.env.HOME || os.homedir();
    if (home) {
      const f = path.join(home, 'koukoutu.json');
      if (fs.existsSync(f)) {
        const j = JSON.parse(fs.readFileSync(f, 'utf8'));
        const k = (j && (j.key || j.apiKey || j.api_key)) || '';
        if (String(k).trim()) return String(k).trim();
      }
    }
  } catch (e) {
    // 文件坏了/没权限就退回环境变量，不让抠图整个哑掉
  }
  if (process.env.HEXORA_KOUKOUTU_KEY) return String(process.env.HEXORA_KOUKOUTU_KEY).trim();
  return '';
}

/** 把接口的报错翻译成「用户能照做」的话，别丢一串 HTTP 码给模型 */
function kouErrHint(code, body) {
  const b = String(body || '').slice(0, 240);
  if (code === 401 || code === 403) {
    return '抠图 API Key 无效或没配置。到 设置 → 图片工具（抠图） 粘贴一次；申请地址：'
      + KOU_APPLY_URL + '（原文：' + b + '）';
  }
  if (code === 402) {
    return '积分不足（抠图 1 积分/张，输出 png 再 +1）。到 ' + KOU_APPLY_URL + ' 充值后再试。（原文：' + b + '）';
  }
  if (code === 413) return '图片超过接口上限（40MB / 10000x10000），先压小再抠。（原文：' + b + '）';
  if (code === 429) return '并发超限（同步接口最多 5 个任务），等几秒重试。（原文：' + b + '）';
  return 'HTTP ' + code + ' ' + b;
}

/**
 * multipart/form-data 直传，收二进制。
 * 用 response=bytes：结果直接是图片字节，不用先拿 URL 再下载（少一次请求，也少一个会过期的链接）。
 */
function httpsMultipartBinary(url, params, fileField, filePath, apiKey) {
  return new Promise((resolve, reject) => {
    const body0 = fs.readFileSync(filePath);
    const u = new URL(url);
    u.search = new URLSearchParams(params).toString();
    const boundary = '----hexora' + Date.now().toString(16) + Math.random().toString(16).slice(2);
    const head = Buffer.from(
      '--' + boundary + '\r\n'
      + 'Content-Disposition: form-data; name="' + fileField + '"; filename="' + path.basename(filePath) + '"\r\n'
      + 'Content-Type: application/octet-stream\r\n\r\n',
      'utf8'
    );
    const tail = Buffer.from('\r\n--' + boundary + '--\r\n', 'utf8');
    const body = Buffer.concat([head, body0, tail]);

    const req = https.request({
      method: 'POST',
      hostname: u.hostname,
      port: u.port || 443,
      path: u.pathname + u.search,
      headers: {
        'X-API-Key': apiKey,
        'Content-Type': 'multipart/form-data; boundary=' + boundary,
        'Content-Length': body.length,
        Accept: '*/*',
      },
      timeout: 180000,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const buf = Buffer.concat(chunks);
        if (res.statusCode >= 200 && res.statusCode < 300 && buf.length > 64) {
          resolve({ buf: buf, type: String(res.headers['content-type'] || '') });
        } else {
          reject(new Error(kouErrHint(res.statusCode, buf.slice(0, 240).toString('utf8'))));
        }
      });
    });
    req.on('timeout', () => req.destroy(new Error('抠图接口超时（图太大或网络慢），稍后重试')));
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

/** 相对路径按「当前工程」解析；绝对路径原样用 */
function resolveInProject(tdir, p) {
  const s = String(p == null ? '' : p).trim();
  if (!s) return '';
  if (path.isAbsolute(s)) return s;
  return path.join(tdir || cwd, s);
}

/** 真正干活：读图 → 传接口 → 写回项目 */
async function removeBg(tdir, args) {
  const key = kouKey();
  if (!key) {
    return {
      ok: false,
      reason: '还没有配置抠图 API Key。到 设置 → 图片工具（抠图） 粘贴一次即可（申请地址：'
        + KOU_APPLY_URL + '）。配好后重新调用本工具。',
    };
  }
  const src = resolveInProject(tdir, args.image);
  if (!src || !fs.existsSync(src) || !fs.statSync(src).isFile()) {
    return { ok: false, reason: '找不到要抠的图片：' + String(args.image || '(空)') + '（工程目录：' + (tdir || cwd) + '）' };
  }
  const size = fs.statSync(src).size;
  if (size > 40 * 1024 * 1024) {
    return { ok: false, reason: '图片 ' + Math.round(size / 1048576) + 'MB，超过接口 40MB 上限，先压小或裁小再抠。' };
  }
  const fmt = String(args.format || '').toLowerCase() === 'webp' ? 'webp' : 'png';
  const border = [0, 1, 2].indexOf(Number(args.border)) >= 0 ? Number(args.border) : 1;
  const crop = Number(args.crop) === 1 ? 1 : 0;
  const params = {
    model_key: 'background-removal',
    output_format: fmt,
    crop: String(crop),
    border: String(border),
    response: 'bytes',
  };
  const got = await httpsMultipartBinary(KOU_BASE + '/v1/create', params, 'image_file', src, key);
  const out = args.out
    ? resolveInProject(tdir, args.out)
    : path.join(path.dirname(src), path.basename(src).replace(/\.[^.]+$/, '') + '-nobg.' + fmt);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, got.buf);

  const rel = (p) => path.relative(tdir || cwd, p).split(path.sep).join('/');
  return {
    ok: true,
    src: rel(src),
    out: rel(out),
    abs: out,
    kb: Math.round(got.buf.length / 1024),
    format: fmt,
    credits: fmt === 'png' ? 2 : 1,
    note: '已抠成透明背景：' + rel(out) + '（原图没动）。'
      + '开始写代码时直接引用 ' + rel(out) + '；'
      + '还有别的图要抠，用同样的方式继续调本工具（一次一张，接口并发上限 5）。',
  };
}

function pickExisting(projects, root) {
  const base = path.basename(path.resolve(root)).toLowerCase();
  if (!base) return null;
  const same = projects.find((p) => (p.name || '').toLowerCase() === base);
  if (same) return same;
  // 名字里含目录名（例如工程叫「武侠」、Maker 项目叫「武侠 demo」）也认
  return (
    projects.find((p) => {
      const n = (p.name || '').toLowerCase();
      return n && (n.includes(base) || base.includes(n));
    }) || null
  );
}

let binding = null;      // 本次绑定结果（进程内缓存 + 给 /health 看）
let bindingBusy = null;  // 并发合并：同时来多个生成请求，只绑定一次
let bindingErr = '';     // 最近一次绑定失败的原因（给 /health 排障）

/**
 * 确保 root 目录已绑定 Maker 项目。
 * 策略：同名项目复用 → 没有就新建（项目名 = 目录名）→ 建不了就退回复用最近活跃的项目。
 * 任何失败都不阻塞工具调用（保持原行为，让 Maker 自己报错），只在日志/health 里留原因。
 */
async function ensureBinding(root, opts) {
  const dir = path.resolve(root || projectDir || cwd);
  if (!dir) return { ok: false, reason: '没有目标目录' };
  if (!(opts && opts.forceNew)) {
    const existing = readBinding(dir);
    if (existing) {
      binding = { dir, project_id: existing.project_id, name: '(复用已绑定)' };
      return { ok: true, already: true, dir, project_id: existing.project_id };
    }
  }
  if (bindingBusy) return bindingBusy;
  bindingBusy = (async () => {
    const token = readPatToken();
    if (!token) {
      throw new Error('未找到 Maker 凭证 pat.json，需要先在 Maker 面板完成授权');
    }
    const base = apiBase();
    const listed = normalizeProjects(await httpsJson('GET', base + '/apps', token));
    let project = opts && opts.forceNew ? null : pickExisting(listed, dir);
    let created = false;
    let note = '';
    if (!project) {
      const name = path.basename(dir) || 'hexora';
      try {
        const r = await httpsJson('POST', base + '/apps', token, { name, gameType: 'sce' });
        project = normalizeProjects(r && r.app ? { apps: [r.app] } : r)[0];
        created = true;
      } catch (e) {
        // 建不了（额度/权限/网络）就退回复用最近活跃的项目，至少让生图能用
        const recent = listed
          .slice()
          .sort((a, b) =>
            String(b.lastAccessedAt || b.createdAt).localeCompare(String(a.lastAccessedAt || a.createdAt))
          )[0];
        if (!recent) throw e;
        project = recent;
        note = '新建项目失败，已复用最近项目：' + (e && e.message ? e.message : String(e));
      }
    }
    if (!project || !project.id) throw new Error('未能确定 Maker 项目（列表为空且无法新建）');
    const cfg = writeBinding(dir, project);
    binding = { dir, project_id: cfg.project_id, name: project.name || '' };
    bindingErr = '';
    console.log(
      '[bridge] 已绑定 Maker 项目 ' + cfg.project_id + ' (' + (project.name || '') + ') → ' + dir +
        (created ? ' [新建]' : ' [复用]') + (note ? ' · ' + note : '')
    );
    return { ok: true, dir, project_id: cfg.project_id, name: project.name || '', created, note };
  })()
    .catch((e) => {
      bindingErr = e && e.message ? e.message : String(e);
      console.error('[bridge] 自动绑定失败: ' + bindingErr);
      return { ok: false, reason: bindingErr };
    })
    .then((r) => {
      bindingBusy = null;
      return r;
    });
  return bindingBusy;
}


// 子进程要用的额外 node 参数（App 侧注入，例如 "--use-bundled-ca -r /path/dnsfix.js"）
const childExtra = (process.env.MAKER_CHILD_ARGS || '')
  .split(/\s+/)
  .filter(Boolean);

// 子进程的启动前缀（App 侧注入）。
// 关键：hexrt/node 是 musl 链接的，PT_INTERP 指向 ld-musl-aarch64.so.1（Android 上不存在），
// 直接 exec 它必然失败（表现就是「child 退出 code=1」）。App 自己能跑 node 是因为套了
// libmuslrt.so --library-path <rt> 这层加载器 —— 子进程必须走同一套前缀。
const prefix = (process.env.MAKER_CHILD_PREFIX || '')
  .split(/\s+/)
  .filter(Boolean);
const childExe = prefix.length ? prefix[0] : process.execPath;
const childLead = prefix.length ? prefix.slice(1) : [];

// ---------------- 子进程（stdio MCP server） ----------------
let child = null;
let childAlive = false;
// 子进程自己注册了多少个工具（不含桥的本地工具）。
// -1 = 还没抓过。App 侧靠它区分「桥连上了但工具没注册」和「工具真的就这些」——
// 这正是「授权过了却永远没有 generate_image」的分水岭。
let childTools = -1;
let restarts = 0;
let lastExit = '';
let buf = '';
const pending = new Map(); // jsonrpc id -> resolve(msg)

// 子进程的原始输出留一份尾巴：它要是起不来，我们得能看见原因，
// 而不是只能去翻 App 私有目录里的日志（shell 根本读不到）。
const logs = [];
function pushLog(d) {
  logs.push(d.toString('utf8'));
  if (logs.length > 80) logs.shift();
}
function logTail() {
  return logs.join('').slice(-1600);
}

function failPending(msg) {
  for (const [, done] of pending) {
    done({ jsonrpc: '2.0', error: { code: -32000, message: msg } });
  }
  pending.clear();
}

/**
 * 等子进程回来（最多 ms 毫秒），回来返回 true。
 *
 * 为什么必须有：tools/list 是「一次性把工具清单抓走」，而 App 只在启动时抓一次。
 * 子进程刚好在重启的那一两秒被抓，就会抓到空清单 —— 然后「授权好的生图工具」永远不出现。
 * 给它一个短窗口等一等，比事后让用户重启 App 划算得多。
 */
function waitChild(ms) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    const tick = () => {
      if (childAlive) return resolve(true);
      if (Date.now() - t0 > ms) return resolve(false);
      setTimeout(tick, 300);
    };
    tick();
  });
}

/**
 * 沙箱里有没有可用的 git（只探测，不安装）。
 *
 * 为什么要探测：以前「绑定工程」走官方 init，而 init 第一行就是 ensureGitAvailable() ——
 * 安卓沙箱里没有 git，于是永远绑不上，症状看起来像「生图坏了」。
 * 现在绑定由桥直接写 .maker-mcp/config.json 完成，git 只有 `git clone` 那一步才需要。
 * 这里把事实和结论一起返回，免得以后又有人把 "git not found" 当成生图故障。
 */
let gitCache = null;
function gitProbe() {
  if (gitCache) return gitCache;
  const out = { available: false, path: '', version: '' };
  try {
    const r = spawnSync('git', ['--version'], { encoding: 'utf8', timeout: 3000 });
    if (r && r.status === 0 && r.stdout) {
      out.available = true;
      out.path = 'git';
      out.version = String(r.stdout).trim();
    }
  } catch (e) {
    /* 没有就没有，不是错误 */
  }
  out.note = out.available
    ? 'git 可用（只有 clone 才用到它；绑定工程与素材生成都不需要）'
    : '沙箱里没有 git —— 这不影响绑定工程与素材生成（桥直接写 .maker-mcp/config.json），只有官方 init 的 git clone 用到它。';
  gitCache = out;
  return out;
}

function startChild() {
  try {
    child = spawn(childExe, [...childLead, ...childExtra, target], {
      stdio: ['pipe', 'pipe', 'pipe'],
      cwd,
      env: process.env,
    });
  } catch (e) {
    childAlive = false;
    lastExit = 'spawn 失败: ' + e.message;
    scheduleRestart();
    return;
  }

  childAlive = true;
  childTools = 0; // 刚起来：工具还没注册，等它回一次 tools/list 才算就绪
  buf = '';
  console.log('[bridge] child 已启动 pid=' + child.pid + ' restarts=' + restarts);

  child.stdout.on('data', (d) => {
    buf += d.toString('utf8');
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        pushLog(line + '\n'); // 非 JSON（日志等）
        continue;
      }
      if (msg.id !== undefined && pending.has(msg.id)) {
        const done = pending.get(msg.id);
        pending.delete(msg.id);
        done(msg);
      }
    }
  });

  child.stderr.on('data', (d) => {
    pushLog(d);
    process.stderr.write(d);
  });
  child.on('error', (e) => {
    lastExit = 'child error: ' + e.message;
  });
  child.on('exit', (code, sig) => {
    childAlive = false;
    lastExit = 'code=' + code + (sig ? ' sig=' + sig : '');
    console.log('[bridge] child 退出 ' + lastExit + '，准备重启');
    failPending('子进程已退出 ' + lastExit);
    scheduleRestart();
  });
}

/**
 * 关键修复：子进程死了必须自己拉起来。
 * 否则 App 侧只看 /health 的 HTTP 200，会永远以为 Maker 是好的。
 */
function scheduleRestart() {
  if (restarts >= 500) return;
  restarts++;
  // 前几次快速重试（子进程偶发崩溃能立刻救回来）；一直起不来就拉长间隔，
  // 免得每 1.5 秒硬撞一个 60MB 的 node，白烧电。
  const delay = restarts <= 4 ? 1500 : 10000;
  setTimeout(() => {
    // 上一次留下的管道先收干净，避免句柄泄漏
    try {
      if (child && child.stdin && !child.stdin.destroyed) child.stdin.destroy();
    } catch {}
    startChild();
  }, delay);
}

// ==================== 子进程「node 包装」（v3） ====================
// 为什么还要这一层：
//   Maker 的生图/音乐/音效不是它自己直接干的 —— 它会再 spawn 一个「远端代理」子进程
//   （`__maker-proxy`，连 https://maker.taptap.cn/mcp/v1）。它启动子进程用的是：
//       return { command: process.execPath, args: [maker.js, "__maker-proxy"] }
//   而在安卓沙箱里，我们是用 libmuslrt.so 加载器把这颗 musl node 跑起来的，
//   于是 process.execPath 指向的是**加载器本身**（不是 node）。
//   → 系统拿加载器去加载一个 .js 文件 → 秒挂 → 上层只看到 "MCP error -32000: Connection closed"。
//   （症状：绑定已经成功、工具也挂上了，但一调 generate_image 就报 remote proxy 失败。）
//
// 解法两条（都对用户透明）：
//   1) 造一个 maker-node 包装脚本，把「加载器 + node + CA/DNS 参数」封成可执行文件；
//   2) 把 maker.js 里所有 process.execPath 改成 MAKER_NODE_BIN || process.execPath，
//      于是它 spawn 出来的每个子孙进程都会自动走这个包装（共 12 处启动点，一网打尽）。
function setupNodeShim() {
  if (!prefix.length) return; // 桌面/正常环境：prefix 为空，直接走原生 node
  const ld = prefix[0];
  let libPath = '';
  let node = '';
  const li = prefix.indexOf('--library-path');
  if (li >= 0 && prefix[li + 1]) {
    libPath = prefix[li + 1];
    node = prefix[li + 2] || '';
  }
  if (!node) node = prefix[prefix.length - 1];
  if (!ld || !node) return;

  const rt = libPath || path.dirname(node);

  // ★ 这里曾经写过一个 maker-node 包装脚本，结果是 spawn EACCES：
  //   安卓只允许从 lib/ 目录（APK 原生库目录，只读）执行文件，
  //   app 私有数据目录（filesDir）里的脚本**一律 execve 失败**，chmod 755 也没用
  //   （在 /sdcard 上更彻底：fuse 连权限位都不存，chmod 直接无效）。
  // 所以现在不落任何脚本，改用「加载器直启」：
  //   command  = libmuslrt.so（它在 lib/ 里，可执行 ✓）
  //   args头部 = --library-path <rt> <rt>/node --use-bundled-ca -r <rt>/dnsfix.js
  // 拼出来就是 App 自己启动 node 时那条命令，子进程/孙进程因此都能跑起来。
  const pre = [];
  if (libPath) pre.push('--library-path', libPath);
  pre.push(node);
  for (let i = 0; i < childExtra.length; i++) pre.push(childExtra[i]);

  process.env.MAKER_PROXY_EXE = ld;        // 精确补丁用：command 指向加载器
  process.env.MAKER_PROXY_PRE = pre.join(' '); // 精确补丁用：node + 参数前缀
  process.env.MAKER_NODE_BIN = ld;         // 兜底补丁用：其它 process.execPath 也走加载器
  process.env.MAKER_RT_DIR = rt;

  // 清掉 1.9m 留下的那个包装脚本（它跑不起来，留着只会让人误以为在用）
  try {
    const old = path.join(rt, 'maker-node');
    if (fs.existsSync(old)) fs.unlinkSync(old);
  } catch (e) { /* 忽略 */ }

  console.log('[bridge] node 加载器就绪（libmuslrt 直启、不落脚本）: ' + ld);
  console.log('[bridge] 子进程参数前缀: ' + process.env.MAKER_PROXY_PRE);
}

/**
 * 给 maker.js 打补丁：把 process.execPath 换成 MAKER_NODE_BIN（未设置时行为不变）。
 * 幂等：打过就直接返回；原文件留一份 .hexbak 备份便于排查。
 */
function patchMakerScript(script) {
  try {
    if (!script || !fs.existsSync(script)) return 'no script';
    let src = fs.readFileSync(script, 'utf8');
    const bak = script + '.hexbak';
    if (!fs.existsSync(bak)) fs.writeFileSync(bak, src, 'utf8');
    const done = [];

    // ① 兜底：把所有 process.execPath 指向 MAKER_NODE_BIN（= libmuslrt 加载器）。
    //    这一步只改 command，不够 —— 加载器还需要 node 路径，所以下面 ② 才是关键。
    if (src.indexOf('(process.env.MAKER_NODE_BIN||process.execPath)') < 0) {
      const hits = (src.match(/process\.execPath/g) || []).length;
      src = src.split('process.execPath').join('(process.env.MAKER_NODE_BIN||process.execPath)');
      if (hits) done.push('execPath x' + hits);
    }

    // ② 关键：embedded MCP proxy（生图/音效/音乐走的就是它）的启动命令。
    //    原样是  command: process.execPath, args: [makerEntry, "__maker-proxy"]
    //    我们要变成  command: 加载器, args: [--library-path, rt, node, --CA, -r, dnsfix, makerEntry, __maker-proxy]
    //    —— 否则只换 command 会让加载器去加载一个 .js，照样秒挂。
    if (src.indexOf('MAKER_PROXY_EXE') < 0) {
      const before = src;
      src = src.replace(
        /command:\s*(?:\(process\.env\.MAKER_NODE_BIN\s*\|\|\s*)?process\.execPath\)?,/,
        'command: (process.env.MAKER_PROXY_EXE||process.env.MAKER_NODE_BIN||process.execPath),'
      );
      src = src.replace(
        /args:\s*\[\s*path(\d+)\.resolve\(makerEntry\),\s*"__maker-proxy"\s*\]/,
        'args: [...((process.env.MAKER_PROXY_PRE||"").split(" ").filter(Boolean)), ' +
          'path$1.resolve(makerEntry), "__maker-proxy"]'
      );
      if (src !== before) done.push('proxy-command');
    }

    if (!done.length) return 'already';
    fs.writeFileSync(script, src, 'utf8');
    console.log('[bridge] maker.js 已打补丁：' + done.join('、'));
    return 'patched: ' + done.join('、');
  } catch (e) {
    console.error('[bridge] maker.js 打补丁失败: ' + (e && e.message));
    return 'error: ' + (e && e.message);
  }
}

// 补丁必须在 spawn 子进程之前生效（子进程启动时才读这个文件）
setupNodeShim();
patchMakerScript(target);

startChild();

// 启动即尝试绑定一次（App 一开就能生图，不必等第一次工具调用）。
// 失败不致命：只把原因留在 /health 的 bindingError 里，工具照原样报错，便于排障。
if (projectDir) {
  ensureBinding(projectDir).catch(() => {});
}

// ---------------- HTTP 层 ----------------
let sid = 'hexora-bridge-' + process.pid;

function send(res, code, obj, raw) {
  const text = raw !== undefined ? raw : JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Mcp-Session-Id': sid,
    'Content-Length': Buffer.byteLength(text),
  });
  res.end(text);
}

const server = http.createServer((req, res) => {
  if (req.url === '/health' || req.url === '/healthz') {
    // ok 严格表示「子进程可用」：App 侧要按它决定是否重启桥
    send(res, 200, {
      ok: childAlive,
      // 子进程注册的工具数：App 用它判断「连上了但工具还没注册」并自动重抓
      childTools: childTools,
      pid: process.pid,
      child: child && child.pid,
      restarts: restarts,
      lastExit: lastExit,
      tail: logTail(),
      target: targetArg,
      project: projectDir,
      // 绑定状态：素材生成工具能不能用，就看这个（AI 排障也能直接看到）
      bound: !!readBinding(projectDir || cwd),
      binding: binding,
      bindingError: bindingErr,
      // 沙箱里有没有可用的 git。
      // 说明：绑定工程这条路**不需要 git**（桥直接写 .maker-mcp/config.json），
      // 只有官方 `init` 里那步 git clone 才需要。这里只上报事实，
      // 免得下次看到 "git not found" 又以为是「生图坏了」。
      git: gitProbe(),
      // 图片工具（在线抠图）的配置状态：没配 Key 时 AI 会被明确告知去哪申请
      imageApi: { koukoutu: !!kouKey() },
    });
    return;
  }
  if (req.url === '/logs') {
    // 子进程起不来时唯一的现场：shell 读不到 App 私有目录里的日志，从这里拿
    send(res, 200, { ok: childAlive, lastExit: lastExit, restarts: restarts, tail: logTail() });
    return;
  }
  // 手动确认/补绑定（设置页「素材项目」按钮用；create=1 强制新建）
  if (req.url.startsWith('/ensure-project')) {
    const u = new URL(req.url, 'http://127.0.0.1');
    const create = u.searchParams.get('create') === '1' || u.searchParams.get('create') === 'true';
    const dir = u.searchParams.get('dir') || projectDir || cwd;
    ensureBinding(dir, { forceNew: create }).then((r) => send(res, 200, r));
    return;
  }

  if (req.method !== 'POST') {
    send(res, 405, { error: 'use POST' });
    return;
  }

  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', async () => {
    let msg;
    try {
      msg = JSON.parse(body);
    } catch (e) {
      send(res, 400, { jsonrpc: '2.0', error: { code: -32700, message: 'parse error' } });
      return;
    }

    // 把 MCP 会话头回给客户端（它初始化时会带）
    const incomingSid = req.headers['mcp-session-id'];
    if (incomingSid) sid = incomingSid;

    // 自动补 target_dir：本项目目录（App 侧没有 MCP Roots，Builder 要求每次带上）
    if (
      projectDir &&
      msg.method === 'tools/call' &&
      msg.params &&
      msg.params.arguments &&
      typeof msg.params.arguments === 'object' &&
      msg.params.arguments[targetArg] === undefined
    ) {
      msg.params.arguments[targetArg] = projectDir;
    }

    // 本轮调用的工具名：素材生成类工具需要先确保工程已绑定 Maker 项目。
    // 注意是 params.name（MCP 标准），不是 params.tool.name —— 之前按后者取，
    // 结果 tname 永远是空串，整个「自动绑定」预检都不会触发。
    const tname = (msg.params && (msg.params.name || (msg.params.tool && msg.params.tool.name))) || '';

    // 桥自带的本地工具：直接自己答，不进子进程。
    // 特意放在「子进程存活」检查**之前** —— 列 Maker 项目 / 绑定只走 Maker HTTP API（PAT），
    // 子进程死了它们照样能用。以前放在检查之后，于是 Maker 子进程一挂，连「我的项目有哪些」
    // 都答不出来，用户看到的就是「明明设置里能查、对话里查不出来」。
    const tdir0 =
      (msg.params && msg.params.arguments && msg.params.arguments[targetArg]) || projectDir || cwd;
    if (msg.method === 'tools/call' && localTool(tname)) {
      handleLocalTool(res, msg, tname, tdir0);
      return;
    }

    if (!childAlive) {
      send(res, 503, {
        jsonrpc: '2.0',
        error: { code: -32000, message: 'Maker 子进程未运行（bridge 正在自动重启，请稍后重试）' },
      });
      return;
    }

    const isNotify = msg.id === undefined || msg.id === null;
    if (isNotify) {
      try {
        child.stdin.write(JSON.stringify(msg) + '\n');
      } catch {}
      send(res, 202, { ok: true });
      return;
    }

    // 素材生成类工具：先确保「当前工程已绑定 Maker 项目」（没有就自动建一个），再转发。
    // 这就是「用户不该被要求先手动建项目」这句话的落地处 —— 绑定由桥自己完成，
    // 用户（和 AI）都不需要知道 .maker-mcp/config.json 这回事。
    // 注意绑的是本次调用真正用的 target_dir（AI 显式传了别的目录时以它为准）。
    const tdir = (msg.params && msg.params.arguments && msg.params.arguments[targetArg]) || projectDir || cwd;
    if (msg.method === 'tools/call' && GEN_TOOLS.has(tname)) {
      ensureBinding(tdir)
        .catch(() => {})
        .then(() => forward(msg, res));
      return;
    }

    // 本地工具（maker_ensure_project / maker_list_apps）已经在上面、子进程存活检查之前处理完了：
    // 它们只走 Maker HTTP API，Maker 子进程死着也能答。

    forward(msg, res);
  });
});

/** 把一段 JSON 包成 MCP 工具结果回给调用方 */
function reply(res, id, obj) {
  send(res, 200, {
    jsonrpc: '2.0',
    id: id,
    result: {
      content: [{ type: 'text', text: JSON.stringify(obj, null, 2) }],
      isError: !obj.ok,
    },
  });
}

/**
 * 转发给 Maker 子进程。tools/list 时顺手把自己实现的本地工具（maker_ensure_project）
 * 追加进去，这样 AI 也能主动触发绑定/新建项目。
 */
async function forward(msg, res) {
  if (!childAlive) {
    // tools/list 是「一次性把工具清单抓走」的请求：App 只在启动 / 掉线重连时抓一次，
    // 抓到空清单就再也不重试 —— 表现出来就是「授权好了，生图工具却总是不在」。
    // 这里给刚在重启的子进程一个等待窗口，比让用户重启 App 划算得多。
    const needList = !!(msg && msg.method === 'tools/list');
    const ok = await waitChild(needList ? 15000 : 5000);
    if (!ok) {
      send(res, 503, {
        jsonrpc: '2.0',
        error: { code: -32000, message: 'Maker 子进程未运行（bridge 正在自动重启，请稍后重试）' },
      });
      return;
    }
  }

  const isNotify = msg.id === undefined || msg.id === null;
  if (isNotify) {
    try {
      child.stdin.write(JSON.stringify(msg) + '\n');
    } catch {}
    send(res, 202, { ok: true });
    return;
  }

  const method = msg.method;
  const timer = setTimeout(() => {
    pending.delete(msg.id);
    send(res, 504, { jsonrpc: '2.0', id: msg.id, error: { code: -32001, message: 'Maker 超时未响应' } });
  }, 10 * 60 * 1000);

  pending.set(msg.id, (reply) => {
    clearTimeout(timer);
      if (method === 'tools/list' && reply && reply.result && Array.isArray(reply.result.tools)) {
        // 先记下子进程自己的工具数（排除桥的本地工具），再追加本地工具 ——
        // 顺序不能反，否则会把本地工具也数进去，App 就没法判断「生成类工具在不在」。
        childTools = reply.result.tools.filter((t) => t && t.name && !localTool(t.name)).length;
        // 桥自带的本地工具（maker_ensure_project / maker_list_apps）追加给 AI。
        // 注意名字必须以 maker_ 开头：App 侧按前缀判归属，mcp_ 开头会被当成开放平台那套。
        for (const lt of LOCAL_TOOLS) {
          if (!reply.result.tools.some((t) => t && t.name === lt.name)) {
            reply.result.tools.push(lt);
          }
        }
      }
    send(res, 200, reply);
  });
  try {
    child.stdin.write(JSON.stringify(msg) + '\n');
  } catch (e) {
    clearTimeout(timer);
    pending.delete(msg.id);
    send(res, 500, { jsonrpc: '2.0', id: msg.id, error: { code: -32002, message: '写入子进程失败: ' + e.message } });
  }
}

server.listen(port, '127.0.0.1', () => {
  console.log('[bridge] maker 已就绪 http://127.0.0.1:' + port + ' (child pid ' + (child && child.pid) + ')');
});
