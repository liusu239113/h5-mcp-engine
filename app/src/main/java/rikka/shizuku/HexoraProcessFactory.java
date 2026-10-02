package rikka.shizuku;

import moe.shizuku.server.IRemoteProcess;

/**
 * 把 {@link ShizukuRemoteProcess} 的**包级构造**暴露出来。
 *
 * ## 为什么需要这个类
 *
 * Shizuku 官方的 `Shizuku.newProcess(...)` 是 **private**，而
 * `ShizukuRemoteProcess(IRemoteProcess)` 的构造是 **package-private**
 * —— 两者都不对外，从 `com.mcp.h5engine` 包里调不到。
 *
 * 但我们要的就是「以 shell 身份起一个进程」这一件事（读受保护文件全靠它），
 * 而 `IShizukuService.newProcess()` 本身是 AIDL 公开接口。
 * 所以：把 stub 和这个工厂都放进源码树，让它们和 Shizuku 的 api 在**同一个包**里编译，
 * 就能合法拿到那个构造。
 *
 * ⚠️ 这个类必须留在 `rikka.shizuku` 包下 —— 换个包就失去包级访问权，编译不过。
 */
public final class HexoraProcessFactory {

    private HexoraProcessFactory() {
    }

    /** 把 AIDL 的远程进程包成可当普通 Process 用的对象 */
    public static ShizukuRemoteProcess create(IRemoteProcess remote) {
        return new ShizukuRemoteProcess(remote);
    }
}
