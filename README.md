# MCP H5 Engine

一个**安卓原生**的 H5 游戏容器：跑 H5 游戏，同时让游戏能直接调用 **MCP 工具**。

工程位于 `/sdcard/AndroidIDEProjects/H5McpEngine/`，用 AndroidIDE / Android Studio 直接打开即可。

---

## 一、架构

```
┌──────────────────────────────────────────────┐
│  WebView (Chromium)                          │
│  https://appassets.androidplatform.net/...   │
│   ├─ assets/games/demo/index.html            │  ← 游戏
│   ├─ assets/games/demo/engine.js             │  ← JS 运行时（window.Engine）
│   └─ /games/...                              │  ← 游戏沙箱（存档读写）
└───────────────┬──────────────────────────────┘
                │  Native.post({id, op, data})
                │  window.__nativeResolve(payload)
┌───────────────▼──────────────────────────────┐
│  EngineBridge (Kotlin, @JavascriptInterface)  │
│   op: http / mcp.open / mcp.request / fs.*   │
├──────────────────────────────────────────────┤
│  McpClient (Kotlin)                          │
│   JSON-RPC 2.0 over Streamable HTTP / SSE    │
└──────────────────────────────────────────────┘
```

**三个关键设计**

1. **不用 `file://`，用 `WebViewAssetLoader`**
   本地资源映射到 `https://appassets.androidplatform.net/`，这样是 secure context：WebGL / WebAudio / `crypto.subtle` / ServiceWorker 全部可用，`fetch` 也不会被 `file://` 的 CORS 打死。

2. **MCP 走 HTTP，不走 stdio**
   安卓上起不了子进程喂 stdio，所以只支持 Streamable HTTP / SSE 两种传输。协议细节都在 `McpClient.kt` 里，约 150 行。

3. **所有网络都过原生**
   JS 调 `Engine.http.get()` → 走 `EngineBridge` 用 OkHttp 发 → 结果回 JS。顺手把 CORS 问题彻底绕过去，游戏代码不用管跨域。

---

## 二、文件清单

```
H5McpEngine/
├── settings.gradle.kts
├── build.gradle.kts
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/values/strings.xml
        ├── java/com/mcp/h5engine/
        │   ├── MainActivity.kt      # WebView 宿主 + AssetLoader
        │   ├── EngineBridge.kt      # JS↔原生桥（http/mcp/fs）
        │   └── McpClient.kt         # MCP JSON-RPC 客户端
        └── assets/games/demo/
            ├── index.html           # demo 界面
            ├── engine.js            # JS 运行时（window.Engine）
            └── game.js              # demo 游戏逻辑
```

---

## 三、编译

```bash
cd /sdcard/AndroidIDEProjects/H5McpEngine
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

环境要求：JDK 17、Android SDK 34、AGP 8.2.2、Gradle 8.2。

---

## 四、游戏侧怎么用

```js
// 1. 连 MCP
const info = await Engine.mcp.connect('http://192.168.1.10:3000/mcp', {
    headers: { Authorization: 'Bearer xxx' },   // 可选
    id: 'my-server'                             // 多 server 时用来区分
});
console.log(info.serverInfo, info.protocolVersion);

// 2. 列工具
const { tools } = await Engine.mcp.listTools();

// 3. 调工具
const r = await Engine.mcp.callTool('generate_level', { difficulty: 3 });
const text = Engine.mcp.text(r);        // 把 content 数组拍平成文本
const level = JSON.parse(text);         // 服务端返回 JSON 的话

// 4. 存档（写在 filesDir/games/<gameId>/saves/ 下）
await Engine.store.save('slot1.json', { hp: 100 });
const s = await Engine.store.load('slot1.json', null);

// 5. 随便发 HTTP（绕过 CORS）
const resp = await Engine.http.get('https://api.example.com/data');
```

`Engine.mcp.text()` 会自动处理 `content: [{type:'text'}, ...]` 这种标准返回结构。

---

## 五、魔改指南

| 想干什么 | 改哪里 |
|---|---|
| **接官方 MCP TS SDK** | 删掉 `engine.js` 里的 mcp 部分，改成 `import { Client } from '@modelcontextprotocol/sdk/client/index.js'` + 自定义 fetch transport，fetch 指向 `Engine.http` |
| **用官方 Kotlin SDK** | `McpClient.kt` 换成 `io.modelcontextprotocol:kotlin-sdk`，`EngineBridge` 不用动 |
| **加原生能力**（震动/传感器/Toast/剪贴板） | `EngineBridge.dispatch()` 里加一个 `op`，JS 侧 `Engine.call('vibrate', {...})` |
| **支持多游戏包** | 现在 `gameId` 写死在 `MainActivity`，改成从 `filesDir/games/` 扫描目录列个列表，点进去再 `loadUrl` |
| **支持 zip 游戏包** | 启动时把 zip 解到 `filesDir/games/<id>/`，再交给 `InternalStoragePathHandler` |
| **换非 Chromium 引擎** | 换成 GeckoView（WebGL 更强，但体积大 40MB+）|
| **图形性能不够** | 开 `WebView.setWebContentsDebuggingEnabled(true)` 用 Chrome DevTools 看帧；或把游戏换成 WebGL 渲染路线 |
| **内存/进程隔离** | 游戏跑在 `android:process=":game"` 单独进程，崩溃不影响主界面 |

---

## 六、可参考/魔改的开源项目

| 项目 | 用途 |
|---|---|
| `ionic-team/capacitor` | WebView + 插件桥 + 本地 server，工业级方案，写个 `McpPlugin` 就能接 |
| `LiquidPlayer/LiquidCore` | Android 内嵌 Node.js，能直接跑 `@modelcontextprotocol/sdk`（想要 npm 生态选它） |
| `mozilla/geckoview` | 非 Chromium 的完整引擎 |
| `nodejs-mobile/nodejs-mobile` | 要跑 **stdio** MCP server 才需要 |
| `modelcontextprotocol/kotlin-sdk` | 官方 Kotlin MCP 客户端 |
| `modelcontextprotocol/typescript-sdk` | 官方 TS MCP SDK（想跑在 JS 层就引它） |
| `androidx/webkit` | `WebViewAssetLoader`，本地资源映射的核心 |

---

## 七、已知待办

- [ ] SSE **增量**流式解析（目前是一次性读完 body 再解析，`tools/call` 慢的时候体验不好）
- [ ] `notifications/*` 服务端主动通知 → 转发给 JS
- [ ] MCP 认证（OAuth resource server 那套）
- [ ] 游戏列表 UI + 包管理
- [ ] WebWorker / 子线程渲染支持
- [ ] 崩溃捕获回传给游戏做容错
