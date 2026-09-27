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

// 子进程要用的额外 node 参数（App 侧注入，例如 "--use-bundled-ca -r /path/dnsfix.js"）
const childExtra = (process.env.MAKER_CHILD_ARGS || '')
  .split(/\s+/)
  .filter(Boolean);

// ---------------- 子进程（stdio MCP server） ----------------
let child = null;
let childAlive = false;
let restarts = 0;
let lastExit = '';
let buf = '';
const pending = new Map(); // jsonrpc id -> resolve(msg)

function failPending(msg) {
  for (const [, done] of pending) {
    done({ jsonrpc: '2.0', error: { code: -32000, message: msg } });
  }
  pending.clear();
}

function startChild() {
  try {
    child = spawn(process.execPath, [...childExtra, target], {
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
        continue; // 非 JSON（日志等）直接忽略
      }
      if (msg.id !== undefined && pending.has(msg.id)) {
        const done = pending.get(msg.id);
        pending.delete(msg.id);
        done(msg);
      }
    }
  });

  child.stderr.on('data', (d) => process.stderr.write(d));
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
  setTimeout(() => {
    // 上一次留下的管道先收干净，避免句柄泄漏
    try {
      if (child && child.stdin && !child.stdin.destroyed) child.stdin.destroy();
    } catch {}
    startChild();
  }, 1500);
}

startChild();

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
      target: targetArg,
      project: projectDir,
    });
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

    const timer = setTimeout(() => {
      pending.delete(msg.id);
      send(res, 504, { jsonrpc: '2.0', id: msg.id, error: { code: -32001, message: 'Maker 超时未响应' } });
    }, 10 * 60 * 1000);

    pending.set(msg.id, (reply) => {
      clearTimeout(timer);
      send(res, 200, reply);
    });
    try {
      child.stdin.write(JSON.stringify(msg) + '\n');
    } catch (e) {
      clearTimeout(timer);
      pending.delete(msg.id);
      send(res, 500, { jsonrpc: '2.0', id: msg.id, error: { code: -32002, message: '写入子进程失败: ' + e.message } });
    }
  });
});

server.listen(port, '127.0.0.1', () => {
  console.log('[bridge] maker 已就绪 http://127.0.0.1:' + port + ' (child pid ' + (child && child.pid) + ')');
});
