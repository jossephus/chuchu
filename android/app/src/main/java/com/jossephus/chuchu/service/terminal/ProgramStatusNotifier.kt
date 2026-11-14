package com.jossephus.chuchu.service.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jossephus.chuchu.MainActivity
import com.jossephus.chuchu.R
import com.jossephus.chuchu.plugin.api.ProgramState
import com.jossephus.chuchu.plugin.api.ProgramStatus

class ProgramStatusNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun notify(tabId: String, label: String, status: ProgramStatus) {
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val tapIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val tap =
            PendingIntent.getActivity(
                context,
                notificationId(tabId),
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val icon = ProgramIcons.forName(status.app)
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon?.drawable ?: R.drawable.ic_notification)
                .apply { icon?.brandColor?.takeIf { it != BLACK }?.let { setColor(it.toInt()) } }
                .setContentTitle(label)
                .setContentText(describe(status))
                .setAutoCancel(true)
                .setCategory(if (status.state == ProgramState.Error) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(tap)
                .build()
        runCatching { manager.notify(notificationId(tabId), notification) }
    }

    fun cancel(tabId: String) {
        manager.cancel(notificationId(tabId))
    }

    private fun describe(status: ProgramStatus): String {
        status.message?.let { return it }
        val program = status.app ?: status.title ?: context.getString(R.string.program_status_default_program)
        val action =
            when (status.state) {
                ProgramState.Blocked ->
                    when (status.kind) {
                        "permission" -> R.string.program_status_needs_permission
                        "question" -> R.string.program_status_has_question
                        "auth" -> R.string.program_status_needs_sign_in
                        else -> R.string.program_status_needs_input
                    }
                ProgramState.Done -> R.string.program_status_done
                ProgramState.Error -> R.string.program_status_failed
                ProgramState.Working, ProgramState.Idle -> R.string.program_status_working
            }
        return context.getString(action, program)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.program_status_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        channel.description = context.getString(R.string.program_status_channel_description)
        system.createNotificationChannel(channel)
    }

    private fun notificationId(tabId: String): Int = NOTIFICATION_ID_BASE + (tabId.hashCode() and 0xffff)

    private companion object {
        const val CHANNEL_ID = "program_status"
        const val NOTIFICATION_ID_BASE = 0x7501_0000
        const val BLACK = 0xFF000000
    }
}
