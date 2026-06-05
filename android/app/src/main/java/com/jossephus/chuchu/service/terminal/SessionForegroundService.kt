package com.jossephus.chuchu.service.terminal

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jossephus.chuchu.MainActivity
import com.jossephus.chuchu.R
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.ui.theme.GhosttyThemeRegistry

class SessionForegroundService : Service() {
    private var currentLabel: String = "Active session"

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

            ACTION_DISCONNECT -> {
                val app = applicationContext as Application
                runCatching {
                    TerminalSessionRepository.getInstance(app).disconnect()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                val label = intent?.getStringExtra(EXTRA_LABEL)
                if (!label.isNullOrBlank()) {
                    currentLabel = label
                }
                val type =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        0
                    }
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(currentLabel),
                    type,
                )
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

    private fun buildNotification(label: String): Notification {
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        val disconnectIntent = Intent(this, SessionForegroundService::class.java)
            .setAction(ACTION_DISCONNECT)
        val disconnectPending = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            pendingFlags,
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Chuchu connected")
            .setContentText(label)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(tapPending)
            .addAction(0, "Disconnect", disconnectPending)

        resolveNotificationColor()?.let { argb ->
            notificationBuilder
                .setColor(argb)
                .setColorized(true)
        }

        val notification = notificationBuilder.build()
        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun resolveNotificationColor(): Int? {
        val selectedTheme = SettingsRepository.getInstance(applicationContext).themeName.value
        GhosttyThemeRegistry.init(applicationContext)
        val theme = GhosttyThemeRegistry.getTheme(applicationContext, selectedTheme) ?: return null
        return theme.background.toArgb()
    }

    companion object {
        private const val CHANNEL_ID = "chuchu_terminal_session"
        private const val NOTIFICATION_ID = 2002
        private const val ACTION_START = "com.jossephus.chuchu.action.START_SESSION_SERVICE"
        private const val ACTION_STOP = "com.jossephus.chuchu.action.STOP_SESSION_SERVICE"
        private const val ACTION_DISCONNECT = "com.jossephus.chuchu.action.DISCONNECT_SESSION"
        private const val EXTRA_LABEL = "extra_label"

        fun start(context: Context, label: String) {
            val intent = Intent(context, SessionForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LABEL, label)
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
