package com.mcp.h5engine

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TapTap 小游戏 MCP 的本地运行时。
 *
 * APK 里带的是「musl 版 Node 20 + 官方 @taptap/instant-games-open-mcp」：
 *   assets/rt.tar            node + musl 全套库 + 官方包（含官方 musl 签名模块）
 *   jniLibs/libmuslrt.so     musl 动态加载器（安卓只允许从 lib/ 目录 exec）
 *
 * 首次启动把 rt.tar 解到私有目录，然后：
 *   libmuslrt.so --library-path <rt> <rt>/node package/dist/server.js
 * 以 Streamable HTTP 模式在 127.0.0.1:3000 起服务，App 再用 MCP 客户端去连。
 */
object McpRt {
    /**
     * 运行时版本戳。**改了 rt.tar 内容就必须升这个值**：
     * 已装设备靠它判断「要不要重新解包」，不升的话 ready() 一直返回 true，新文件永远进不去。
     *   2 → 官方 MCP
     *   3 → 追加 @taptap/maker（本地制造开发）+ bridge.js（stdio→HTTP 桥）
     */
    private const val STAMP = "5"
    const val PORT = 3000
    const val URL_BASE = "http://127.0.0.1:3000/"

    /**
     * Maker 只提供 stdio，而 stdio 管道绑死在父进程上（主界面被冻结就一起死）。
     * 所以经 bridge.js 包成 HTTP，挂到独立进程里，才能和官方 MCP 享受同一套自愈保护。
     */
    const val MAKER_PORT = 3011
    const val MAKER_URL_BASE = "http://127.0.0.1:3011/"

    @Volatile
    var process: Process? = null
        private set

    /** Maker 桥进程（和官方 MCP 分开，一个坏了不影响另一个） */
    @Volatile
    var makerProcess: Process? = null
        private set

    /** 桥当前绑定的项目目录：切项目要重启它，Maker 才知道素材该落进哪个工程 */
    @Volatile
    var makerProject: String = ""
        private set

    private val busy = AtomicBoolean(false)
    private val makerBusy = AtomicBoolean(false)

    fun rtDir(ctx: Context): File = File(ctx.filesDir, "hexrt")

    // ==================== 图片工具（抠图 / 去背景）的 Key ====================
    /**
     * 抠图 Key 存哪：**桥进程的 HOME** 下的 koukoutu.json —— 也就是 `<rt>/home/koukoutu.json`。
     * 桥每次调用 `maker_remove_bg` 都现读这个文件，所以设置页存完**立刻生效，不用重启桥**。
     * （环境变量 HEXORA_KOUKOUTU_KEY 只是启动时按同一份文件注入的兜底值。）
     */
    fun kouKeyFile(ctx: Context): File = File(File(rtDir(ctx), "home"), "koukoutu.json")

    /** 读当前抠图 Key（没有就是空串） */
    fun koukoutuKey(ctx: Context): String = runCatching {
        val f = kouKeyFile(ctx)
        if (!f.isFile) return@runCatching ""
        val j = org.json.JSONObject(f.readText())
        val k = j.optString("key").ifBlank { j.optString("apiKey") }
        k.trim()
    }.getOrDefault("")

    /** 存抠图 Key（空串 = 清空）。返回是否写成功 */
    fun saveKoukoutuKey(ctx: Context, key: String): Boolean = runCatching {
        val f = kouKeyFile(ctx)
        f.parentFile?.mkdirs()
        f.writeText(org.json.JSONObject().put("key", key.trim()).toString())
        true
    }.getOrDefault(false)

    private fun stampFile(ctx: Context) = File(rtDir(ctx), ".stamp")

    private fun loader(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libmuslrt.so")

    fun ready(ctx: Context): Boolean {
        val s = stampFile(ctx)
        return s.isFile && s.readText().trim() == STAMP && File(rtDir(ctx), "node").isFile
    }

    fun running(): Boolean = process?.isAlive == true

    fun health(): Boolean = try {
        val c = URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 1500
        val code = c.responseCode
        c.disconnect()
        code == 200
    } catch (t: Throwable) {
        false
    }

    /** 解包运行时（阻塞，必须放子线程）。返回 null 表示成功 */
    fun extract(ctx: Context, log: (String) -> Unit): String? {
        val gdir = rtDir(ctx)
        // git（musl/aarch64）是后加的：老设备 hexrt 早就解包过（ready=true），
        // ensureGitrt 挂在下面「首次解压」分支里就永远轮不到 —— 症状就是
        // init 一直报 spawnSync git ENOENT。所以这里先单独补一步（幂等）。
        if (gdir.isDirectory) ensureGitrt(ctx, gdir)
        if (ready(ctx)) return null
        return try {
            val dir = rtDir(ctx)
            // 重建运行时**绝不能连 home/ 一起删**：pat.json（Maker 授权）、koukoutu.json（抠图 Key）
            // 都住在 <rt>/home 下，被 deleteRecursively 带走的话，已装设备每次升版本都要重新授权。
            // 做法：删之前把 home 挪到 cache 备份，解包 + 写 stamp 之后再挪回来。
            val __home = File(dir, "home")
            val __bak = File(ctx.cacheDir, "rt_home_bak")
            if (__home.isDirectory) {
                __bak.deleteRecursively()
                if (!__home.renameTo(__bak)) __home.copyRecursively(__bak, overwrite = true)
            }
            dir.deleteRecursively()
            dir.mkdirs()
            val tar = File(dir, "rt.tar")
            log("首次运行：正在释放运行时（约 60MB，只做一次）…")
            ctx.assets.open("rt.tar").use { ins ->
                FileOutputStream(tar).use { ins.copyTo(it, 1 shl 16) }
            }
            log("正在解包…")
            extractTar(tar, dir)
            tar.delete()
            stampFile(ctx).writeText(STAMP)
            if (__bak.isDirectory) {
                __home.deleteRecursively()
                if (!__bak.renameTo(__home)) __bak.copyRecursively(__home, overwrite = true)
            }
            File(dir, "node").setExecutable(true, false)
            ensureGitrt(ctx, dir)
            log("运行时就绪")
            null
        } catch (t: Throwable) {
            "解包失败：${t.message}"
        }
    }

    /**
     * 确保「自带 git」已释放到 <rt>/gitrt。
     *
     * 为什么这么做：Maker CLI 的 init / clone / push 全是 spawnSync("git", …)，
     * 而手机上根本没有 git。这里塞的是一套 Alpine 的 musl/aarch64 git，
     * 由 libmuslrt.so 加载（Android 上没有 /lib/ld-musl-aarch64.so.1），
     * 外面套一层 shell wrapper（gitrt/git），所以能被当成普通 git 直接调用。
     *
     * 返回 null 表示就绪。
     */
    fun ensureGitrt(ctx: Context, dir: File): String? {
        val g = File(dir, "gitrt/git")
        // 「存在」不等于「能用」：Android 10+ 起，untrusted_app 对私有目录
        // （app_data_file）里的文件一律禁止 execve（avc denied execute_no_trans），
        // 旧版那种「脚本直接放在 gitrt 里」的做法在真机上必然 EACCES。
        // 现在：git 是软链 → 指向 APK 的 nativeLibraryDir/libhexgit.so
        // （apk_data_file，允许执行）。所以必须「已是可执行软链」才跳过。
        if (g.isFile && g.canExecute() && g.absolutePath != g.canonicalPath) return null
        return try {
            File(dir, "gitrt").deleteRecursively()
            val tar = File(dir, "gitrt.tar")
            ctx.assets.open("gitrt.tar").use { ins ->
                FileOutputStream(tar).use { ins.copyTo(it, 1 shl 16) }
            }
            extractTar(tar, dir)
            tar.delete()
            linkGit(ctx, dir)
            null
        } catch (t: Throwable) {
            "git 释放失败：${t.message}"
        }
    }

    /**
     * 把 <rt>/gitrt/git 做成软链接，指向 nativeLibraryDir/libhexgit.so。
     *
     * 为什么必须这样：从 Android 10 开始，untrusted_app 域对「自己私有目录」
     * （SELinux 标签 app_data_file）里的文件禁止 execve —— 真机 logcat 原文：
     *   avc: denied { execute_no_trans } for
     *     path="/data/data/<pkg>/files/hexrt/gitrt/git"
     *     tcontext=u:object_r:app_data_file:s0 tclass=file
     * 而 APK 的 lib 目录（nativeLibraryDir）是 apk_data_file，允许执行。
     * 所以在私有目录里放一个软链指向那边，execve 会跟着软链落到允许的位置。
     * （Operit 的 Linux 终端用的就是这招：usr/bin/busybox -> libbusybox.so）
     *
     * libhexgit.so 是个静态链接的小程序，负责把调用转交给
     * <rt>/libmuslrt.so（musl 加载器）去跑 <rt>/gitrt/git.bin。
     */
    private fun linkGit(ctx: Context, dir: File) {
        val launcher = File(ctx.applicationInfo.nativeLibraryDir, "libhexgit.so")
        launcher.setExecutable(true, false)
        val link = File(dir, "gitrt/git")
        runCatching { link.delete() }
        val ok = runCatching { Os.symlink(launcher.absolutePath, link.absolutePath) }.isSuccess
        if (!ok) throw IllegalStateException("git 软链创建失败: " + link.absolutePath)
    }

    /** 拉起服务；返回 null 表示成功（阻塞，放子线程） */
    fun start(ctx: Context, log: (String) -> Unit): String? {
        if (running() && health()) return null
        if (!busy.compareAndSet(false, true)) return "启动中，请稍候"
        return try {
            val dir = rtDir(ctx)
            val ld = loader(ctx)
            val node = File(dir, "node")
            val server = File(dir, "package/dist/server.js")
            if (!ld.isFile) return "缺少 libmuslrt.so（本包只支持 arm64 设备）"
            if (!node.isFile || !server.isFile) return "运行时未解包"
            stop()
            val home = File(dir, "home").apply { mkdirs() }
            writeDnsMap(ctx)
            val pb = ProcessBuilder(
                ld.absolutePath, "--library-path", dir.absolutePath,
                node.absolutePath,
                // Android 沙箱里没有 /etc/ssl/certs，用 node 内置的 Mozilla CA
                "--use-bundled-ca",
                // musl 只读 /etc/resolv.conf（Android 沙箱里没有），改用 node 自己的 UDP 查询
                "-r", File(dir, "dnsfix.js").absolutePath,
                server.absolutePath
            )
            pb.directory(File(dir, "package"))
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["HOME"] = home.absolutePath
            env["TMPDIR"] = home.absolutePath
            env["PATH"] = dir.absolutePath
            env["TAPTAP_MCP_TRANSPORT"] = "http"
            env["TAPTAP_MCP_PORT"] = PORT.toString()
            log("正在启动 TapTap MCP 服务…")
            val p = pb.start()
            process = p
            // 吃掉输出，否则管道写满会卡住子进程
            // 把 node 输出全部落盘（保留尾部），下次「服务没了」时能看到它到底怎么死的
            val logFile = File(dir, "mcp.log")
            runCatching { if (logFile.length() > 200_000) logFile.writeText("") }
            Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (line.contains("error", true) || line.contains("✅")) log(line.take(200))
                        runCatching {
                            if (logFile.length() > 200_000) logFile.writeText("")
                            logFile.appendText(line + "\n")
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                if (health()) {
                    log("服务已就绪（79 个工具）")
                    return null
                }
                if (!p.isAlive) return "服务启动失败（退出码 ${p.exitValue()}）"
                Thread.sleep(600)
            }
            "服务启动超时"
        } catch (t: Throwable) {
            "启动失败：${t.message}"
        } finally {
            busy.set(false)
        }
    }

    /**
     * 用 Android 自己的解析器（走 netd，沙箱里一定可用）预先解析关键域名，
     * 写成 dns.map 给 dnsfix.js 兜底：万一 UDP DNS 被墙/被劫持，还有这条路。
     */
    private fun writeDnsMap(ctx: Context) {
        val hosts = listOf(
            "agent.tapapis.cn", "accounts.tapapis.cn", "www.taptap.cn",
            "api.taptap.cn", "openapi.taptap.cn", "developer.taptap.cn"
        )
        val obj = org.json.JSONObject()
        for (h in hosts) {
            runCatching { obj.put(h, java.net.InetAddress.getByName(h).hostAddress) }
        }
        runCatching { File(rtDir(ctx), "dns.map").writeText(obj.toString()) }
    }

    // ==================== Maker（本地制造开发）：stdio → HTTP 桥 ====================

    /**
     * 桥的健康检查（守护巡检直接用这个）。
     *
     * 只看 HTTP 200 是不够的：bridge 进程活着、但它 spawn 的 Maker 子进程死了时，
     * /health 依旧回 200。那时 App 会一直认为「Maker 已就绪」，于是永不重启，
     * Maker 工具数永远是 0（症状：授权过了，AI 却始终没有 generate_image）。
     * 所以这里必须把 ok 字段（=子进程是否可用）一起判掉。
     */
    fun makerHealth(): Boolean = try {
        val c = URL("http://127.0.0.1:$MAKER_PORT/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 1500
        val code = c.responseCode
        val body = if (code == 200) {
            runCatching { c.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
        } else ""
        c.disconnect()
        code == 200 && body.contains("\"ok\":true")
    } catch (t: Throwable) {
        false
    }

    /**
     * 把 assets/ui-kits（预制 UI 风格包）同步到 rt 目录，返回目录本身。
     * 每次启动桥都覆盖一遍：主题内容改了，已装设备不用重新解包运行时就能生效。
     */
    private fun syncUiKits(ctx: Context, dir: File): File {
        val dst = File(dir, "ui-kits")
        runCatching { copyAssetDir(ctx, "ui-kits", dst) }
        return dst
    }

    /** 递归把 assets 下的一个目录（或文件）复制到 dst */
    private fun copyAssetDir(ctx: Context, assetPath: String, dst: File) {
        val children = ctx.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            // assets.list 对「文件」返回空数组、对「不存在」返回 null，所以这里就是文件
            dst.parentFile?.mkdirs()
            runCatching {
                ctx.assets.open(assetPath).use { ins -> FileOutputStream(dst).use { ins.copyTo(it) } }
            }
            return
        }
        dst.mkdirs()
        for (c in children) copyAssetDir(ctx, "$assetPath/$c", File(dst, c))
    }

    /** 桥是否已在为「这个项目」服务（换项目就得重启，路径写在启动参数里） */
    fun makerReady(projectDir: String): Boolean =
        makerProcess?.isAlive == true && makerHealth() && makerProject == projectDir

    /**
     * 启动 Maker（阻塞，放子线程）。
     *
     * projectDir = 当前工程根目录：生成的美术 / 音乐 / 音效素材会直接落进这个工程，
     * 不用你手动搬来搬去（桥那边把 target_dir 自动补上了）。
     */
    fun startMaker(ctx: Context, log: (String) -> Unit, projectDir: String): String? {
        if (makerReady(projectDir)) return null
        if (!makerBusy.compareAndSet(false, true)) return "Maker 启动中，请稍候"
        return try {
            val dir = rtDir(ctx)
            val ld = loader(ctx)
            val node = File(dir, "node")
            val bridge = File(dir, "bridge.js")
            val maker = File(dir, "maker/dist/maker.js")
            if (!ld.isFile) return "缺少 libmuslrt.so（本包只支持 arm64 设备）"
            if (!maker.isFile) return "Maker 未随运行时解包（需重新安装本版本）"
            // 桥脚本随 APK 热更新：已装设备不用重新解包 65MB 运行时，
            // 每次启动前从 assets 覆盖一份，保证跑的一定是带「子进程自愈」的新桥。
            runCatching {
                ctx.assets.open("bridge.js").use { ins ->
                    FileOutputStream(File(dir, "bridge.js")).use { ins.copyTo(it) }
                }
            }
            stopMaker()
            // 兜底：每次起 Maker 都确认 git 已释放（幂等，存在就直接返回）。
            runCatching { ensureGitrt(ctx, dir) }
            val home = File(dir, "home").apply { mkdirs() }
            writeDnsMap(ctx)
            val pb = ProcessBuilder(
                ld.absolutePath, "--library-path", dir.absolutePath,
                node.absolutePath,
                "--use-bundled-ca",
                "-r", File(dir, "dnsfix.js").absolutePath,
                bridge.absolutePath,
                maker.absolutePath,
                MAKER_PORT.toString(),
                "--cwd", projectDir,
                "--project", projectDir
            )
            pb.directory(dir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["HOME"] = home.absolutePath
            env["TMPDIR"] = home.absolutePath
            env["PATH"] = dir.absolutePath + ":" + File(dir, "gitrt").absolutePath
            // ---- 自带 git（musl/aarch64）：Maker 的 init / clone / push 全是 spawnSync("git") ----
            // 手机上没有 git，靠 <rt>/gitrt 那套「loader 前缀 + shell wrapper」顶上。
            env["HEXORA_RT"] = dir.absolutePath
            env["HEXORA_LD"] = ld.absolutePath
            env["GIT_EXEC_PATH"] = File(dir, "gitrt/git-core").absolutePath
            env["TAPTAP_MAKER_GIT_BIN"] = File(dir, "gitrt/git").absolutePath
            env["GIT_SSL_CAINFO"] = File(dir, "gitrt/cacert.pem").absolutePath
            env["GIT_TEMPLATE_DIR"] = File(dir, "gitrt/templates").absolutePath
            env["GIT_TERMINAL_PROMPT"] = "0"
            env["GIT_CONFIG_NOSYSTEM"] = "1"
            // Maker 自己的家目录也塞私有目录，免得它往沙箱外写
            env["TAPTAP_MAKER_HOME"] = File(home, "maker").apply { mkdirs() }.absolutePath
            // UI 风格包（预制主题）：每次启动从 assets 同步一份到 rt 目录 —— 主题改了，
            // 已装设备不用重新解包 65MB 运行时就能生效。桥按这个路径列主题 / 落地主题。
            env["HEXORA_UI_KITS"] = syncUiKits(ctx, dir).absolutePath
            // 抠图（去背景）的 API Key 兜底值：设置页把它写在 <rt>/home/koukoutu.json，
            // 桥每次调用现读那份文件（所以换 Key 不用重启桥）；这里注入一份防文件被误删。
            runCatching { koukoutuKey(ctx) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { env["HEXORA_KOUKOUTU_KEY"] = it }
            // 关键：Maker 本体是桥 spawn 出来的**另一个** node 进程，它不继承我们给桥的启动参数。
            // 不给它带上 CA / DNS 修正，它自己发起的网络请求（生图、音乐、配音都要联网）
            // 会因为 musl 读不到 Android DNS、找不到 CA 而全部失败。
            env["MAKER_CHILD_ARGS"] = "--use-bundled-ca -r " + File(dir, "dnsfix.js").absolutePath
            // 更要命的一条：hexrt/node 是 musl 链接的（PT_INTERP = ld-musl-aarch64.so.1，
            // Android 上没有这个文件），**直接 exec 它必然失败** —— 症状就是桥的 /health 一直报
            // child 退出 code=1、然后无限重启，Maker 工具永远是 0 个。
            // App 自己能跑 node 是靠 libmuslrt.so 这层加载器，所以子进程也得套同一套前缀。
            env["MAKER_CHILD_PREFIX"] =
                ld.absolutePath + " --library-path " + dir.absolutePath + " " + node.absolutePath
            log("正在启动 Maker（本地制造开发）…")
            val p = pb.start()
            makerProcess = p
            makerProject = projectDir
            val logFile = File(dir, "maker.log")
            runCatching { if (logFile.length() > 200_000) logFile.writeText("") }
            Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        runCatching {
                            if (logFile.length() > 200_000) logFile.writeText("")
                            logFile.appendText(line + "\n")
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                if (makerHealth()) {
                    log("Maker 已就绪（18 个工具 · 素材直接落进当前项目）")
                    return null
                }
                if (!p.isAlive) return "Maker 启动失败（退出码 ${p.exitValue()}）"
                Thread.sleep(600)
            }
            "Maker 启动超时"
        } catch (t: Throwable) {
            "Maker 启动失败：${t.message}"
        } finally {
            makerBusy.set(false)
        }
    }

    fun stopMaker() {
        runCatching { makerProcess?.destroy() }
        makerProcess = null
        makerProject = ""
        killStaleOnPort(MAKER_PORT)
    }

    fun stop() {
        runCatching { process?.destroy() }
        process = null
        killStaleOnPort(PORT)
    }

    /**
     * 把「占着端口的僵尸服务」清掉。
     *
     * 真实场景：App 被系统回收后，旧桥变成孤儿进程继续占着 3011，而它内部 spawn 的
     * Maker 子进程早就死了（旧版桥不会重启子进程）。这时新 App 起来想重新拉桥，
     * listen 直接撞 EADDRINUSE 退出，而旧桥只会一直回「Maker 子进程未运行」——
     * 结果就是：授权过了、服务看着"在"，但 Maker 工具数永远是 0。
     *
     * 桥的 /health 会把自己的 pid 报出来；同一 uid 下可以直接 kill。
     */
    private fun killStaleOnPort(port: Int) {
        val pid = runCatching {
            val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            c.connectTimeout = 800
            c.readTimeout = 800
            val body = if (c.responseCode == 200) {
                c.inputStream.bufferedReader().use { it.readText() }
            } else ""
            c.disconnect()
            Regex("\"pid\":(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
        }.getOrNull() ?: return
        runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        // 给它一点时间真正退出，否则紧接着 bind 还是可能撞 EADDRINUSE
        for (i in 0 until 20) {
            val alive = runCatching { Os.kill(pid, 0); true }.getOrDefault(false)
            if (!alive) break
            runCatching { Thread.sleep(100) }
        }
    }

    // ==================== tar 解包（ustar + GNU longname + pax 跳过） ====================

    private fun extractTar(tar: File, outDir: File) {
        RandomAccessFile(tar, "r").use { raf ->
            val header = ByteArray(512)
            var longName: String? = null
            while (true) {
                if (raf.read(header) != 512) break
                if (header.all { it == 0.toByte() }) break
                val rawName = str(header, 0, 100)
                val sizeStr = str(header, 124, 12).trim()
                val flag = header[156].toInt().toChar()
                val fmode = str(header, 100, 8).trim().toIntOrNull(8) ?: 0
                val prefix = str(header, 345, 155)
                val linkName = str(header, 157, 100)
                val size = sizeStr.toLongOrNull(8) ?: 0L
                val pad = ((512 - (size % 512)) % 512).toInt()
                if (flag == 'L') {                       // GNU 长文件名
                    val b = ByteArray(size.toInt())
                    raf.readFully(b)
                    longName = String(b, Charsets.UTF_8).trimEnd('\u0000', '\n')
                    if (pad > 0) raf.skipBytes(pad)
                    continue
                }
                if (flag == 'x' || flag == 'g') {        // pax 扩展头：跳过
                    raf.skipBytes(size.toInt() + pad)
                    continue
                }
                val full = (longName ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName)
                    .removePrefix("./")
                longName = null
                if (full.isEmpty()) {
                    if (size > 0) raf.skipBytes(size.toInt() + pad)
                    continue
                }
                val target = File(outDir, full)
                when (flag) {
                    '5' -> { target.mkdirs(); target.setExecutable(true, false) }
                    '2' -> {
                        target.parentFile?.mkdirs()
                        if (!target.exists()) runCatching { Os.symlink(linkName, target.absolutePath) }
                    }
                    '0', '\u0000', '7' -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(1 shl 16)
                            var left = size
                            while (left > 0) {
                                val n = raf.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                left -= n
                            }
                        }
                        // 还原权限位：tar 里的可执行位如果丢了，
                        // git / git.bin 就会变成不可执行（spawnSync git EACCES）。
                        if (fmode and 0b001_001_001 != 0) {
                            target.setReadable(true, false)
                            target.setExecutable(true, false)
                        }
                        if (pad > 0) raf.skipBytes(pad)
                    }
                    else -> if (size > 0) raf.skipBytes(size.toInt() + pad)
                }
            }
        }
    }

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val lim = off + len
        while (end < lim && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }
}