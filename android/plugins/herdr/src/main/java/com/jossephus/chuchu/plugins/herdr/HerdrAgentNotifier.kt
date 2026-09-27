package com.jossephus.chuchu.plugins.herdr

import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.PluginNotification
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrAgentStatus
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrSnapshot

/**
 * Notifies when a herdr agent finishes or blocks while chuchu is in the background.
 * Adapted from chuchu PR #69; posting now goes through [PluginHost.notify].
 */
internal class HerdrAgentNotifier(
    private val host: PluginHost,
    private val enabled: () -> Boolean,
) {
    private data class PaneKey(val sessionId: String, val paneId: String)

    private val lastStatusByPane = mutableMapOf<PaneKey, HerdrAgentStatus>()
    private val lastPostedAtByPane = mutableMapOf<PaneKey, Long>()

    @Synchronized
    fun onSnapshot(
        sessionId: String,
        tabLabel: String,
        snapshot: HerdrSnapshot,
        now: Long = System.currentTimeMillis(),
    ) {
        val visibleKeys = snapshot.panes.mapTo(mutableSetOf()) { PaneKey(sessionId, it.paneId) }
        lastStatusByPane.keys.removeAll { it.sessionId == sessionId && it !in visibleKeys }
        lastPostedAtByPane.keys.removeAll { it.sessionId == sessionId && it !in visibleKeys }

        snapshot.panes.forEach { pane ->
            val key = PaneKey(sessionId, pane.paneId)
            if (
                shouldNotify(
                    previousStatus = lastStatusByPane[key],
                    newStatus = pane.agentStatus,
                    foreground = host.appVisible.value,
                    enabled = enabled(),
                    lastPostedAt = lastPostedAtByPane[key],
                    now = now,
                )
            ) {
                val subject = pane.agent?.takeIf { it.isNotBlank() } ?: "pane"
                val action = if (pane.agentStatus == HerdrAgentStatus.Blocked) "blocked" else "finished"
                val posted =
                    host.notify(
                        PluginNotification(
                            key = "$sessionId:${pane.paneId}",
                            title = "$subject $action",
                            text = pane.terminalTitleStripped?.takeIf { it.isNotBlank() } ?: tabLabel,
                            sessionId = sessionId,
                        ),
                    )
                if (posted) lastPostedAtByPane[key] = now
            }
            lastStatusByPane[key] = pane.agentStatus
        }
    }

    @Synchronized
    fun forget(sessionId: String) {
        lastStatusByPane.keys.removeAll { it.sessionId == sessionId }
        lastPostedAtByPane.keys.removeAll { it.sessionId == sessionId }
    }

    companion object {
        // One notification per pane per window, so a flapping agent doesn't spam.
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
