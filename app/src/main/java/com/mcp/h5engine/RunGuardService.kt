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
 * 运行守护服务。
 *
 * 为什么必须有它：Android 12+ 会把「切到后台、又没有任何前台服务」的进程直接冻结
 * （国产 ROM 更激进，几秒就把线程按停），表现就是「一切后台 AI 就不工作了」。
 * 任务期间挂一个前台服务，进程就不会被当成可回收的缓存进程，
 * 顺带通知栏里能一直看到「跑了多久 / 第几轮 / 正在干什么」。
 */
class RunGuardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "AI 正在工作…"
        runCatching { startForeground(NOTI_ID, build(text)) }
        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun build(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "运行状态", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "AI 干活时的进度与耗时"
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
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hexora 正在做游戏")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "hexora_run"
        private const val NOTI_ID = 4201
        private const val EXTRA_TEXT = "text"
        private const val ACTION_STOP = "com.mcp.h5engine.GUARD_STOP"

        fun start(ctx: Context, text: String) {
            runCatching {
                val i = Intent(ctx, RunGuardService::class.java).putExtra(EXTRA_TEXT, text)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            }
        }

        fun update(ctx: Context, text: String) = start(ctx, text)

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(
                    Intent(ctx, RunGuardService::class.java).setAction(ACTION_STOP)
                )
            }
            runCatching { ctx.stopService(Intent(ctx, RunGuardService::class.java)) }
        }
    }
}
