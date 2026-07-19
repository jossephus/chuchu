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
import com.jossephus.chuchu.service.multiplexer.HerdrAgentStatus
import com.jossephus.chuchu.service.multiplexer.HerdrSnapshot

internal class HerdrAgentNotifier(
    private val context: Context,
) {
    private data class PaneKey(val tabSessionId: String, val paneId: String)

    private val lastStatusByPane = mutableMapOf<PaneKey, HerdrAgentStatus>()
    private val lastPostedAtByPane = mutableMapOf<PaneKey, Long>()

    fun onSnapshot(
        tabSessionId: String,
        tabLabel: String,
        snapshot: HerdrSnapshot,
        foreground: Boolean,
        enabled: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val visibleKeys = snapshot.panes.mapTo(mutableSetOf()) { pane ->
            PaneKey(tabSessionId, pane.paneId)
        }
        prunePanes(tabSessionId, visibleKeys)

        snapshot.panes.forEach { pane ->
            val key = PaneKey(tabSessionId, pane.paneId)
            val previous = lastStatusByPane[key]
            val lastPostedAt = lastPostedAtByPane[key]
            if (
                shouldNotify(
                    previousStatus = previous,
                    newStatus = pane.agentStatus,
                    foreground = foreground,
                    enabled = enabled,
                    lastPostedAt = lastPostedAt,
                    now = now,
                ) && NotificationManagerCompat.from(context).areNotificationsEnabled()
            ) {
                postNotification(
                    tabSessionId,
                    pane.paneId,
                    pane.tabId,
                    pane.agent,
                    pane.agentStatus,
                    pane.terminalTitleStripped,
                    tabLabel,
                )
                lastPostedAtByPane[key] = now
            }
            lastStatusByPane[key] = pane.agentStatus
        }
    }

    fun removeTab(tabSessionId: String) {
        lastStatusByPane.keys.removeAll { it.tabSessionId == tabSessionId }
        lastPostedAtByPane.keys.removeAll { it.tabSessionId == tabSessionId }
    }

    private fun prunePanes(tabSessionId: String, visibleKeys: Set<PaneKey>) {
        lastStatusByPane.keys.removeAll { it.tabSessionId == tabSessionId && it !in visibleKeys }
        lastPostedAtByPane.keys.removeAll { it.tabSessionId == tabSessionId && it !in visibleKeys }
    }

    private fun postNotification(
        tabSessionId: String,
        paneId: String,
        herdrTabId: String,
        agent: String?,
        status: HerdrAgentStatus,
        terminalTitle: String?,
        tabLabel: String,
    ) {
        ensureNotificationChannel()
        val notificationId = "$tabSessionId:$paneId".hashCode()
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TAB_SESSION_ID, tabSessionId)
            putExtra(EXTRA_HERDR_TAB_ID, herdrTabId)
        }
        val tapPending = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val subject = agent?.takeIf { it.isNotBlank() } ?: "pane"
        val action = if (status == HerdrAgentStatus.Blocked) "blocked" else "finished"
        val body = terminalTitle?.takeIf { it.isNotBlank() } ?: tabLabel
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$subject $action")
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Herdr agents", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val EXTRA_TAB_SESSION_ID = "herdr_tab_session_id"
        const val EXTRA_HERDR_TAB_ID = "herdr_tab_id"
        private const val CHANNEL_ID = "chuchu_herdr_agents"
        private const val THROTTLE_MS = 30_000L

        internal fun shouldNotify(
            previousStatus: HerdrAgentStatus?,
            newStatus: HerdrAgentStatus,
            foreground: Boolean,
            enabled: Boolean,
            lastPostedAt: Long?,
            now: Long,
        ): Boolean =
            previousStatus != newStatus &&
                (newStatus == HerdrAgentStatus.Blocked || newStatus == HerdrAgentStatus.Done) &&
                !foreground &&
                enabled &&
                (lastPostedAt == null || now - lastPostedAt >= THROTTLE_MS)
    }
}
