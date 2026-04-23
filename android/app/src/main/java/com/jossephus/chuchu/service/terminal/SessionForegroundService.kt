package com.jossephus.chuchu.service.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jossephus.chuchu.R

class SessionForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SSH sessions",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Keeps active terminal sessions alive"
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Chuchu session active")
            .setContentText("Keeping SSH session alive in background")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "chuchu_terminal_session"
        private const val NOTIFICATION_ID = 2002
        private const val ACTION_START = "com.jossephus.chuchu.action.START_SESSION_SERVICE"
        private const val ACTION_STOP = "com.jossephus.chuchu.action.STOP_SESSION_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, SessionForegroundService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            val stopIntent = Intent(context, SessionForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(stopIntent) }
            context.stopService(Intent(context, SessionForegroundService::class.java))
        }
    }
}
