package com.jossephus.chuchu.plugin

import com.jossephus.chuchu.plugin.api.ExecChannel
import com.jossephus.chuchu.plugin.api.HostInfo
import com.jossephus.chuchu.plugin.api.PluginSession
import com.jossephus.chuchu.plugin.api.SessionEvent
import com.jossephus.chuchu.plugin.api.SessionStatus as ApiSessionStatus
import com.jossephus.chuchu.service.terminal.SessionState
import com.jossephus.chuchu.service.terminal.SessionStatus
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventsTest {
    private val session = object : PluginSession {
        override val id = "tab-1"
        override val host: HostInfo get() = error("unused")
        override val status: StateFlow<ApiSessionStatus> get() = error("unused")
        override suspend fun exec(command: String): ExecChannel? = null
    }

    @Test
    fun nonStatusChangesProduceNoEvents() {
        val base = SessionState(status = SessionStatus.Connected, title = "vim", pwd = "/src")
        val next = base.copy(title = "htop", pwd = "/tmp", bellCount = 2)
        assertTrue(sessionEventsBetween(session, base, next).isEmpty())
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
}
