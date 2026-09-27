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
const { spawn } = require('child_process');
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
const PROJECT_SUBDIRS = ['assets', 'assets/image', 'assets/sprites', 'assets/video', 'assets/audio', 'scripts'];
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
  req.on('end', () => {
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

    // 桥自带的本地工具：直接自己答，不进子进程
    if (msg.method === 'tools/call' && tname === LOCAL_TOOL.name) {
      const forceNew = !!(msg.params.arguments && msg.params.arguments.create);
      const wantDir = (msg.params.arguments && msg.params.arguments.dir) || tdir;
      ensureBinding(wantDir, { forceNew }).then((r) => reply(res, msg.id, r));
      return;
    }

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
function forward(msg, res) {
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

  const method = msg.method;
  const timer = setTimeout(() => {
    pending.delete(msg.id);
    send(res, 504, { jsonrpc: '2.0', id: msg.id, error: { code: -32001, message: 'Maker 超时未响应' } });
  }, 10 * 60 * 1000);

  pending.set(msg.id, (reply) => {
    clearTimeout(timer);
    if (method === 'tools/list' && reply && reply.result && Array.isArray(reply.result.tools)) {
      if (!reply.result.tools.some((t) => t && t.name === LOCAL_TOOL.name)) {
        reply.result.tools.push(LOCAL_TOOL);
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
