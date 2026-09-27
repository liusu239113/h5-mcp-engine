package com.mcp.h5engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * MCP 守护服务。
 *
 * 内置的 TapTap MCP 是本地 node 子进程，Android 冻结/回收后台应用时会把它一起带走，
 * 表现就是「用着用着 Failed to connect to /127.0.0.1:3000」。
 * 这里挂一个常驻前台服务（进程不再被当成可回收的缓存进程）+ 每 12 秒巡检，
 * 掉了就自动拉回来，不用用户去设置里手点「重连」。
 */
class McpGuardService : Service() {

    @Volatile private var alive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            alive = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startForeground(NOTI_ID, build()) }
        startWatch()
        // 被系统回收后尽量自己回来：回来后重新巡检并把服务拉起
        return START_STICKY
    }

    private fun startWatch() {
        if (alive) return
        alive = true
        Thread {
            while (alive) {
                try {
                    Thread.sleep(12_000)
                    if (!alive) break
                    if (McpRt.health()) continue
                    // 掉了：ensure 是幂等的，会把 node 拉起来并重新挂工具
                    McpBoot.ensure(this) { }
                    runCatching { startForeground(NOTI_ID, build()) }
                } catch (t: Throwable) {
                    // 巡检自身出错不重要，下一轮继续
                }
            }
        }.apply { isDaemon = true; name = "mcp-guard" }.start()
    }

    private fun build(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "MCP 服务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "内置 TapTap 小游戏 MCP 的运行状态"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        val tools = EngineTools.mcp?.size ?: 0
        val text = if (tools > 0) {
            "已接入 $tools 个工具 · 127.0.0.1:${McpRt.PORT}"
        } else {
            "正在准备本地服务…"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hexora MCP 运行中")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "hexora_mcp"
        private const val NOTI_ID = 4202
        private const val ACTION_STOP = "com.mcp.h5engine.MCP_GUARD_STOP"

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, McpGuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, McpGuardService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}