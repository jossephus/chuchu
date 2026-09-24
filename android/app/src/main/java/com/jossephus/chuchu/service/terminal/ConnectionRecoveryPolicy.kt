package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.mosh.MoshRuntimeState
import com.jossephus.chuchu.service.mosh.MoshState

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

internal fun sessionStatusForMoshState(state: Int): SessionStatus? =
    when (state) {
        MoshState.Idle.code,
        MoshState.Disconnecting.code -> SessionStatus.Disconnected
        MoshState.Connecting.code -> SessionStatus.Connecting
        MoshState.Connected.code -> SessionStatus.Connected
        MoshState.Reconnecting.code -> SessionStatus.Reconnecting
        MoshState.Failed.code -> SessionStatus.Error
        else -> null
    }

internal class MoshConnectionStalenessTracker {
    private companion object {
        // Mosh starts warning about lost contact after roughly three seconds; matching that keeps the
        // terminal responsive without waiting for its intentionally long failure timeout.
        const val STALE_CONNECTION_THRESHOLD_MS = 3_000L
    }

    private var lastStateNumReceived: Long? = null
    private var lastStateNumSent: Long? = null
    private var lastReceivedAdvanceAtMs: Long? = null
    private var sentSinceLastReceiveAdvance = false

    fun reset() {
        lastStateNumReceived = null
        lastStateNumSent = null
        lastReceivedAdvanceAtMs = null
        sentSinceLastReceiveAdvance = false
    }

    fun observe(runtime: MoshRuntimeState, nowMs: Long): SessionStatus? {
        val previousReceived = lastStateNumReceived
        val previousSent = lastStateNumSent
        if (previousReceived == null || runtime.lastStateNumReceived < previousReceived) {
            lastStateNumReceived = runtime.lastStateNumReceived
            lastStateNumSent = runtime.lastStateNumSent
            lastReceivedAdvanceAtMs = nowMs
            sentSinceLastReceiveAdvance = false
            return null
        }

        val receivedAdvanced = runtime.lastStateNumReceived > previousReceived
        val sentAdvanced = previousSent != null && runtime.lastStateNumSent > previousSent
        lastStateNumReceived = runtime.lastStateNumReceived
        lastStateNumSent = runtime.lastStateNumSent

        if (receivedAdvanced) {
            lastReceivedAdvanceAtMs = nowMs
            sentSinceLastReceiveAdvance = false
            return SessionStatus.Connected
        }
        if (sentAdvanced) {
            sentSinceLastReceiveAdvance = true
        }

        val staleForMs = nowMs - (lastReceivedAdvanceAtMs ?: nowMs)
        return if (
            staleForMs >= STALE_CONNECTION_THRESHOLD_MS &&
                (runtime.pendingOutbound > 0 || sentSinceLastReceiveAdvance)
        ) {
            SessionStatus.Reconnecting
        } else {
            null
        }
    }
}
