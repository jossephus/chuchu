package com.jossephus.chuchu.plugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jossephus.chuchu.MainActivity
import com.jossephus.chuchu.R
import com.jossephus.chuchu.plugin.api.PluginNotification
import com.jossephus.chuchu.plugin.api.PluginTheme
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

/** Chuchu's current theme as the plugin-facing [PluginTheme]. */
@Composable
fun rememberPluginTheme(): PluginTheme {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    return remember(colors, typography) {
        PluginTheme(
            isDark = colors.background.luminance() < 0.5f,
            background = colors.background,
            surface = colors.surface,
            border = colors.border,
            textPrimary = colors.textPrimary,
            textSecondary = colors.textSecondary,
            textMuted = colors.textMuted,
            accent = colors.accent,
            onAccent = colors.onAccent,
            success = colors.success,
            warning = colors.warning,
            error = colors.error,
            body = typography.body,
            label = typography.label,
            small = typography.labelSmall,
        )
    }
}

/**
 * Posts plugin notifications on one "plugins" channel with chuchu's icon. A plugin can't
 * reliably do this itself: the small icon and channel must belong to chuchu, whose process
 * the plugin runs in.
 */
class PluginNotifier(private val context: Context) {
    fun post(pluginId: String, pluginName: String, notification: PluginNotification): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel()
        val id = notificationId(pluginId, notification.key)
        val tap =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                notification.sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            }
        val built =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notification.title)
                .setContentText(notification.text)
                .setSubText(pluginName)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        id,
                        tap,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
        return try {
            manager.notify(id, built)
            true
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+).
            false
        }
    }

    fun cancel(pluginId: String, key: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(pluginId, key))
    }

    private fun notificationId(pluginId: String, key: String): Int = "plugin:$pluginId:$key".hashCode()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Plugins", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        /** Intent extra naming the session (tab) a plugin notification belongs to. */
        const val EXTRA_SESSION_ID = "chuchu.plugin.session_id"
        private const val CHANNEL_ID = "chuchu_plugins"
    }
}
