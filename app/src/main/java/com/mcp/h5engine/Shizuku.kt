package com.mcp.h5engine

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.HexoraProcessFactory
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.TimeUnit

/**
 * Shizuku 接入 —— 借 ADB 级权限突破 scoped storage。
 *
 * ## 为什么非要它
 *
 * App 自己的进程是 `untrusted_app`。Android 10+ 的 scoped storage 会把它
 * 对 `/sdcard` 的**文件级**访问整个过滤掉：目录名看得见，里面的文件看不见。
 *
 * 实测（用户真机，Android 16 / API 36）：
 *   · `ls -la /storage/emulated/0`      → 103 个目录、**0 个文件**
 *   · `/storage/emulated/0/DCIM/Camera` → 只有子目录，一张照片都看不到
 *   · `cat 任意文件`                    → **Permission denied**
 *   · `/storage/emulated/0/Android/data` → Permission denied
 *
 * 这不是「文件是空的」，是**权限过滤**。Shizuku 让我们的代码以
 * shell（uid 2000）身份跑，就绕开了这层过滤 —— 跟 Operit 一个路子。
 *
 * ## 用户侧前置条件
 *
 * Shizuku 必须**用户自己装并启动**（我们只能引导，不能代劳）：
 *   1. 装 Shizuku（Play / GitHub）；
 *   2. 开发者选项 → 无线调试 → 配对，启动 Shizuku 服务；
 *   3. 回到本 App，点授权。
 *
 * 没有 Shizuku 时一切**优雅降级**：所有能力退回 App 自身权限，
 * 不崩、不报错，只是读不到受保护目录。
 *
 * ## 权限模型
 *
 * Shizuku 的授权不是 Android 运行时权限，而是通过 binder 向 Shizuku 服务
 * 申请（`requestPermission`），结果走 `OnRequestPermissionResultListener` 回调。
 */
object Shizuku2 {

    private const val TAG = "hexoraShizuku"

    /** Shizuku 官方包名（判断装没装） */
    const val PACKAGE = "moe.shizuku.privileged.api"

    /** 申请权限用的 requestCode，回调里对一下 */
    private const val REQ_CODE = 4242

    /** 有没有装 Shizuku */
    fun isInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Shizuku 服务在不在跑（binder 活着） */
    fun isServiceRunning(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    /**
     * 是否已获得 Shizuku 授权。
     *
     * 注意：没装 / 没启动时 `checkSelfPermission` 会抛，所以整段包在 runCatching 里，
     * 任何异常都当成「没授权」—— 调用方只需要知道能不能用。
     */
    fun hasPermission(): Boolean = runCatching {
        if (!isServiceRunning()) return false
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 整体可用 = 服务在跑 + 已授权 */
    fun isReady(): Boolean = isServiceRunning() && hasPermission()

    /**
     * 拿当前权限等级（shell / root），给 UI 显示用。
     *
     * ⚠️ 判断顺序很重要：**先看 binder 活不活**，最后才看「装没装」。
     *
     * 因为 `isInstalled` 依赖 PackageManager，而 Android 11+ 有包可见性限制 ——
     * 就算我们在 manifest 里声明了 `<queries>`，个别 ROM 上仍可能查不到。
     * 但 binder 是**运行时事实**：它活着就说明 Shizuku 一定装了、也在跑，
     * 没必要再去问 PackageManager（用户真机上「明明开了却说未安装」就是这个）。
     */
    fun describe(): String = when {
        isServiceRunning() && hasPermission() -> "已授权（ADB 级权限）"
        isServiceRunning() -> "等待授权"
        appCtx?.let { isInstalled(it) } != true -> "未安装 Shizuku"
        else -> "Shizuku 没在运行（装好了但服务没启动）"
    }

    /**
     * 详细诊断（排障用）。用户报「明明开了却没用」时，把这段给他看/发回来，
     * 就能立刻分清是「包看不见」还是「服务没起」还是「没授权」。
     */
    fun diagnose(): String = buildString {
        append("安装: ").append(appCtx?.let { isInstalled(it) }?.let { if (it) "是" else "否（也可能是包可见性被挡）" } ?: "上下文未就绪").append('\n')
        append("binder 活着: ").append(if (isServiceRunning()) "是" else "否").append('\n')
        append("已授权: ").append(if (hasPermission()) "是" else "否").append('\n')
        append("整体可用: ").append(if (isReady()) "是" else "否").append('\n')
        append("权限等级: ").append(describe())
    }

    /** App 上下文，由 MainActivity 在 onCreate 时注入（Shizuku 回调里也要用） */
    @Volatile
    private var appCtx: Context? = null

    fun attach(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    // ---- 监听器只注册一次，重复注册 Shizuku 会抛 ----
    private var binderListenerAdded = false
    private var permListenerAdded = false

    /** 状态变化回调（UI 用来刷新） */
    @Volatile
    var onStateChanged: (() -> Unit)? = null

    /**
     * 初始化：注册 binder / 权限监听。
     *
     * 必须**只注册一次** —— Shizuku 对重复添加监听器会抛异常。
     */
    fun init(ctx: Context) {
        attach(ctx)
        runCatching {
            if (!binderListenerAdded) {
                Shizuku.addBinderReceivedListenerSticky { notifyChanged() }
                Shizuku.addBinderDeadListener { notifyChanged() }
                binderListenerAdded = true
            }
            if (!permListenerAdded) {
                // ⚠️ 回调签名是 (requestCode: Int, grantResult: Int) ——
                // grantResult 是 PackageManager 的常量，**不是 Boolean**（猜错过，编译报
                // "inferred type is Int but Boolean was expected"）。
                Shizuku.addRequestPermissionResultListener { code, grantResult ->
                    if (code == REQ_CODE) {
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        Log.i(TAG, "Shizuku 授权结果: $granted (raw=$grantResult)")
                        // 转给等待中的调用方（见 request / deliverPermission）
                        deliverPermission(granted)
                        notifyChanged()
                    }
                }
                permListenerAdded = true
            }
        }.onFailure { Log.w(TAG, "Shizuku 初始化失败（没装就是正常的）: ${it.message}") }
    }

    private fun notifyChanged() {
        runCatching { onStateChanged?.invoke() }
    }

    /**
     * 申请 Shizuku 授权。已经授权过就直接回调 true。
     *
     * @param onResult 结果回调（在主线程）
     */
    fun request(onResult: (Boolean) -> Unit) {
        if (hasPermission()) {
            onResult(true)
            return
        }
        if (!isServiceRunning()) {
            onResult(false)
            return
        }
        runCatching {
            pendingResult = onResult
            Shizuku.requestPermission(REQ_CODE)
        }.onFailure {
            Log.w(TAG, "申请 Shizuku 权限失败: ${it.message}")
            pendingResult = null
            onResult(false)
        }
    }

    /** 待回调（Shizuku 的 listener 是全局单例，只能这样接一下） */
    @Volatile
    private var pendingResult: ((Boolean) -> Unit)? = null

    /** 由 init 里注册的 listener 转发到这里（见 [deliverPermission]） */
    private fun deliverPermission(granted: Boolean) {
        val cb = pendingResult
        pendingResult = null
        cb?.invoke(granted)
    }

    // ==================== 执行 ====================

    /**
     * 拿到 IShizukuService 的远程代理。
     *
     * `moe.shizuku.server.IShizukuService` 由 `dev.rikka.shizuku:aidl` 提供，
     * 而它是 `dev.rikka.shizuku:api` 的**传递依赖** —— 也就是说加了 api 就自动有了，
     * 不用自己 vendor 一份（我一开始 vendor 过，结果 dex 里类重复、构建失败：
     *   `Type moe.shizuku.server.IRemoteProcess is defined multiple times`）。
     *
     * 之前这里用 `Class.forName("...IShizukuService$Stub")` 反射 —— 那条路本来就绕，
     * 现在直接静态引用，编译期就能查错。
     */
    private fun serviceProxy(): IShizukuService? = runCatching {
        val binder = Shizuku.getBinder() ?: return null
        IShizukuService.Stub.asInterface(binder)
    }.getOrElse {
        Log.w(TAG, "拿 Shizuku 服务失败: ${it.javaClass.simpleName}: ${it.message}")
        null
    }

    /**
     * 起一个远程进程（以 shell 身份）。
     *
     * `newProcess(cmd, env, dir)` 返回 [IRemoteProcess]，
     * 它在客户端侧被包成 [ShizukuRemoteProcess]（可当普通 Process 用）。
     */
    private fun newProcess(cmd: List<String>): ShizukuRemoteProcess? = runCatching {
        val svc = serviceProxy() ?: return null
        val remote: IRemoteProcess = svc.newProcess(cmd.toTypedArray(), null, null)
        // 走同包工厂：ShizukuRemoteProcess 的构造是包级私有，
        // 直接 new 编译不过（见 HexoraProcessFactory 的说明）
        HexoraProcessFactory.create(remote)
    }.getOrElse {
        Log.w(TAG, "newProcess 失败: ${it.javaClass.simpleName}: ${it.message}")
        null
    }

    /**
     * 以 shell 身份跑一条命令，返回 (退出码, 输出)。
     *
     * 没授权时返回 (null, 错误说明) —— 调用方据此决定要不要提示用户去授权。
     *
     * 实现要点：
     *   · 经 `IShizukuService.newProcess()` 起进程，身份是 shell；
     *   · stdout / stderr 都要抽干，否则输出一多管道写满、进程卡死；
     *   · 超时就 destroy。
     */
    fun exec(cmd: List<String>, timeoutMs: Long = 20_000): Pair<Int?, String> {
        if (!isReady()) return null to "Shizuku 未就绪（${describe()}）"
        val p = newProcess(cmd) ?: return null to "拿不到 Shizuku 服务（反射 newProcess 失败）"
        return runCatching {
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
            val t2 = Thread { runCatching { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
            t1.isDaemon = true; t2.isDaemon = true
            t1.start(); t2.start()

            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                runCatching { p.destroy() }
                return@runCatching null to (out.toString() + err.toString() + "\n[超时 ${timeoutMs}ms，已终止]")
            }
            t1.join(600); t2.join(600)
            val text = buildString {
                append(out.toString().trimEnd())
                if (err.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append("[stderr] ").append(err.toString().trimEnd())
                }
            }
            p.exitValue() to text
        }.getOrElse { e ->
            Log.w(TAG, "Shizuku exec 失败: ${e.javaClass.simpleName}: ${e.message}")
            null to "执行失败：${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** 跑一条 shell 命令串（经 sh -c 解释） */
    fun sh(command: String, timeoutMs: Long = 20_000): Pair<Int?, String> =
        exec(listOf("sh", "-c", command), timeoutMs)

    /**
     * 以 shell 身份读一个文件的字节。读不到返回 null。
     *
     * 用 `cat` 而不是直接 File 读 —— 后者会被 scoped storage 拦下。
     */
    fun readFile(path: String, maxBytes: Int = 8 shl 20): ByteArray? {
        if (!isReady()) return null
        val p = newProcess(listOf("cat", path)) ?: return null
        return runCatching {
            val bytes = p.inputStream.use { ins ->
                // 只读到 maxBytes 为止，避免把超大文件整个吞进内存
                val buf = ByteArray(maxBytes)
                var off = 0
                while (off < maxBytes) {
                    val n = ins.read(buf, off, maxBytes - off)
                    if (n <= 0) break
                    off += n
                }
                buf.copyOf(off)
            }
            p.waitFor(10, TimeUnit.SECONDS)
            runCatching { p.destroy() }
            if (bytes.isEmpty()) null else bytes
        }.getOrNull()
    }

    /** 以 shell 身份列一个目录（`ls -la`），返回原始文本 */
    fun listDir(path: String): String? {
        val (code, out) = sh("ls -la ${shellQuote(path)}", 15_000)
        return if (code == 0) out else null
    }

    /** 路径加单引号，防止空格 / 特殊字符把命令拆坏 */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 给用户看的启动指引 */
    fun setupHint(): String = when {
        appCtx?.let { isInstalled(it) } != true ->
            "① 先装 Shizuku（应用商店搜「Shizuku」，或从 GitHub 下 rikka 的 Shizuku）\n" +
                "② 装好后按它的引导用「无线调试」启动服务\n" +
                "③ 回来点「授权 Shizuku」"
        !isServiceRunning() ->
            "Shizuku 已装但没在运行。\n" +
                "去「设置 → 开发者选项 → 无线调试」配对后，用 Shizuku 的快捷开关启动它，再回来授权。"
        !hasPermission() -> "Shizuku 在运行，点下面按钮授权即可。"
        else -> "Shizuku 已就绪。"
    }
}
