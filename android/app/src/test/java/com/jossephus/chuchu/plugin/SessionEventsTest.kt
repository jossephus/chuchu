package com.jossephus.chuchu.plugin

import com.jossephus.chuchu.plugin.api.ByteStreamEvent
import com.jossephus.chuchu.plugin.api.ExecChannel
import com.jossephus.chuchu.plugin.api.PtySize
import com.jossephus.chuchu.plugin.api.HostInfo
import com.jossephus.chuchu.plugin.api.PluginSession
import com.jossephus.chuchu.plugin.api.SessionEvent
import com.jossephus.chuchu.plugin.api.SessionStatus as ApiSessionStatus
import com.jossephus.chuchu.service.terminal.SessionState
import com.jossephus.chuchu.service.terminal.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventsTest {
    // Only identity matters to the diff; none of these members are called.
    private val session = object : PluginSession {
        override val id = "tab-1"
        override val host: HostInfo get() = error("unused")
        override val status: StateFlow<ApiSessionStatus> get() = error("unused")
        override val title: StateFlow<String?> get() = error("unused")
        override val pwd: StateFlow<String?> get() = error("unused")
        override fun output(capacity: Int): Flow<ByteStreamEvent> = error("unused")
        override fun input(capacity: Int): Flow<ByteStreamEvent> = error("unused")
        override fun write(bytes: ByteArray) = Unit
        override fun writeText(text: String) = Unit
        override suspend fun exec(command: String, pty: PtySize?): ExecChannel? = null
    }

    @Test
    fun snapshotOnlyChangesProduceNoEvents() {
        val base = SessionState(status = SessionStatus.Connected, title = "vim")
        assertTrue(sessionEventsBetween(session, base, base.copy(bellCount = 0)).isEmpty())
    }

    @Test
    fun statusChangeCarriesErrorAndNoAttemptOutsideReconnecting() {
        val events =
            sessionEventsBetween(
                session,
                SessionState(status = SessionStatus.Connected),
                SessionState(status = SessionStatus.Error, error = "Connection lost", reconnectAttempt = 3),
            )
        assertEquals(
            listOf(SessionEvent.StatusChanged(session, ApiSessionStatus.Error, "Connection lost", 0)),
            events,
        )
    }

    @Test
    fun eachReconnectAttemptIsReported() {
        val events =
            sessionEventsBetween(
                session,
                SessionState(status = SessionStatus.Reconnecting, reconnectAttempt = 1),
                SessionState(status = SessionStatus.Reconnecting, reconnectAttempt = 2),
            )
        assertEquals(
            listOf(SessionEvent.StatusChanged(session, ApiSessionStatus.Reconnecting, null, 2)),
            events,
        )
    }

    @Test
    fun titleAndPwdChangesAreReportedInOrder() {
        val events =
            sessionEventsBetween(
                session,
                SessionState(title = "zsh", pwd = "/home"),
                SessionState(title = "vim", pwd = "/src"),
            )
        assertEquals(
            listOf(SessionEvent.TitleChanged(session, "vim"), SessionEvent.PwdChanged(session, "/src")),
            events,
        )
    }

    @Test
    fun bellsCountFromTheRunningTotalNotThePerSnapshotDrain() {
        val afterBells = SessionState(bellCount = 2, bellTotal = 2)
        assertEquals(
            listOf(SessionEvent.Bell(session, 2)),
            sessionEventsBetween(session, SessionState(), afterBells),
        )
        // A later status update copies bellCount forward; it must not ring again.
        val statusUpdate = afterBells.copy(status = SessionStatus.Connected)
        assertTrue(sessionEventsBetween(session, afterBells, statusUpdate).none { it is SessionEvent.Bell })
    }
}
