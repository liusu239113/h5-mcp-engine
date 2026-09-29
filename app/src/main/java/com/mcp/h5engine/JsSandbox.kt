package com.mcp.h5engine

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable

/**
 * 内嵌 JS 沙箱（C3）。
 *
 * 用 Rhino（纯 JVM 实现）：不依赖 native so、不拉起子进程、无 ABI 适配问题，
 * 也不受 SELinux「禁止 execve 私有目录文件」限制（对照项目里 node/musl 那套 native 方案）。
 * 给插件体系（D1）与工作流的脚本节点提供受限执行环境：
 *   - 只暴露显式注入的宿主变量（bind），默认没有 require / process / 文件 IO；
 *   - 执行限时：超时抛错，防死循环拖死线程。
 * 将来若要极致性能，可在此处替换为 QuickJS native 实现，接口保持不变。
 */
object JsSandbox {

    private class TimeoutError(msg: String) : RuntimeException(msg)

    /**
     * 执行一段 JS，返回最后一个表达式 / return 的值（字符串化）。
     * bind：注入到全局作用域的宿主变量。
     */
    fun eval(code: String, bind: Map<String, Any?> = emptyMap(), timeoutMs: Long = 3000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val factory = object : ContextFactory() {
            override fun makeContext(): Context {
                val cx = super.makeContext()
                cx.optimizationLevel = -1
                cx.languageVersion = Context.VERSION_ES6
                cx.instructionObserverThreshold = 10_000
                return cx
            }

            override fun observeInstructionCount(cx: Context, count: Int) {
                if (System.currentTimeMillis() > deadline) throw TimeoutError("脚本执行超时（>${timeoutMs}ms）")
            }
        }
        val cx = factory.enterContext()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            bind.forEach { (k, v) -> scope.put(k, scope, Context.javaToJS(v, scope)) }
            val r = cx.evaluateString(scope as Scriptable, code, "sandbox", 1, null)
            return Context.toString(r)
        } finally {
            Context.exit()
        }
    }
}