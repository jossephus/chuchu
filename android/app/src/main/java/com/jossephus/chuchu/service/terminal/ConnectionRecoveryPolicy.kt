package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.Transport

internal enum class ReadLoopExitAction {
    Disconnect,
    RequireManualReconnect,
    AutomaticReconnect,
}

internal fun readLoopExitAction(transport: Transport?): ReadLoopExitAction =
    when (transport) {
        Transport.LocalShell -> ReadLoopExitAction.Disconnect
        Transport.Mosh -> ReadLoopExitAction.RequireManualReconnect
        else -> ReadLoopExitAction.AutomaticReconnect
    }

internal class MoshContactTracker {
    private companion object {
        // Mosh starts warning about lost contact after roughly three seconds; matching that keeps the
        // terminal responsive without waiting for its intentionally long failure timeout.
        const val LOST_CONTACT_THRESHOLD_MS = 3_000L
    }

    private var lastPendingOutbound = 0
    private var lastAckProgressAtMs: Long? = null

    fun reset() {
        lastPendingOutbound = 0
        lastAckProgressAtMs = null
    }

    fun isContactLost(pendingOutbound: Int, nowMs: Long): Boolean {
        val previousPending = lastPendingOutbound
        lastPendingOutbound = pendingOutbound
        val lastProgress = lastAckProgressAtMs
        if (
            lastProgress == null ||
                previousPending == 0 ||
                pendingOutbound == 0 ||
                pendingOutbound < previousPending
        ) {
            lastAckProgressAtMs = nowMs
            return false
        }
        return nowMs - lastProgress >= LOST_CONTACT_THRESHOLD_MS
    }
}
