package com.jossephus.chuchu.plugin

import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.plugin.api.ByteStreamEvent
import com.jossephus.chuchu.plugin.api.ExecChannel
import com.jossephus.chuchu.plugin.api.HostInfo
import com.jossephus.chuchu.plugin.api.PluginSession
import com.jossephus.chuchu.plugin.api.PtySize
import com.jossephus.chuchu.plugin.api.SessionEvent
import com.jossephus.chuchu.plugin.api.SessionRegistry
import com.jossephus.chuchu.plugin.api.SessionStatus as ApiSessionStatus
import com.jossephus.chuchu.plugin.api.SessionTransport
import com.jossephus.chuchu.service.terminal.SessionState
import com.jossephus.chuchu.service.terminal.SessionStatus
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.service.terminal.TabSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Exposes [TerminalSessionRepository][com.jossephus.chuchu.service.terminal.TerminalSessionRepository]
 * tabs to plugins.
 *
 * Cost when no plugin is listening matters, because every chuchu user runs this: the only
 * always-on work is one collector on the tab list (which changes rarely). Per-tab state
 * watching for [events] starts only once something collects [events].
 */
class PluginSessionRegistry(
    tabs: StateFlow<List<TabSession>>,
    private val scope: CoroutineScope,
) : SessionRegistry {
    private val _events =
        MutableSharedFlow<SessionEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val _sessions = MutableStateFlow<List<PluginSession>>(emptyList())
    override val sessions: StateFlow<List<PluginSession>> = _sessions.asStateFlow()

    // Touched only from the tab-list collector, so no locking.
    private val adapters = LinkedHashMap<String, TabPluginSession>()
    private val watchers = HashMap<String, Job>()
    private val watching = CompletableDeferred<Unit>()

    init {
        scope.launch {
            _events.subscriptionCount.first { it > 0 }
            watching.complete(Unit)
        }
        scope.launch { tabs.collect(::sync) }
    }

    private fun sync(tabs: List<TabSession>) {
        val liveIds = tabs.mapTo(HashSet()) { it.id }
        val closed = adapters.keys.filter { it !in liveIds }
        closed.forEach { id ->
            watchers.remove(id)?.cancel()
            val session = adapters.remove(id) ?: return@forEach
            session.closed.complete(Unit)
            _events.tryEmit(SessionEvent.Closed(session))
        }
        val opened = ArrayList<TabPluginSession>()
        val ordered = tabs.map { tab ->
            adapters.getOrPut(tab.id) { TabPluginSession(tab).also(opened::add) }
        }
        _sessions.value = ordered
        opened.forEach { session ->
            watchers[session.id] = scope.launch { watch(session) }
            _events.tryEmit(SessionEvent.Opened(session))
        }
    }

    private suspend fun watch(session: TabPluginSession) {
        watching.await()
        // Baseline at watch start: collect's first emission equals it, so no spurious events.
        var previous = session.tab.sessionState.value
        session.tab.sessionState.collect { next ->
            sessionEventsBetween(session, previous, next).forEach(_events::tryEmit)
            previous = next
        }
    }

    private companion object {
        // Events are rare (lifecycle, title, bell); this only overflows for a stuck collector.
        const val EVENT_BUFFER = 64
    }
}

/** Events implied by a session state transition, in a stable order. */
internal fun sessionEventsBetween(
    session: PluginSession,
    previous: SessionState,
    next: SessionState,
): List<SessionEvent> {
    val events = ArrayList<SessionEvent>(2)
    val newAttempt =
        next.status == SessionStatus.Reconnecting && next.reconnectAttempt != previous.reconnectAttempt
    if (next.status != previous.status || newAttempt) {
        val attempt = if (next.status == SessionStatus.Reconnecting) next.reconnectAttempt else 0
        events.add(SessionEvent.StatusChanged(session, next.status.toApi(), next.error, attempt))
    }
    if (next.title != previous.title) events.add(SessionEvent.TitleChanged(session, next.title))
    if (next.pwd != previous.pwd) events.add(SessionEvent.PwdChanged(session, next.pwd))
    val bells = next.bellTotal - previous.bellTotal
    if (bells > 0) events.add(SessionEvent.Bell(session, bells.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
    return events
}

internal class TabPluginSession(val tab: TabSession) : PluginSession {
    override val id: String = tab.id

    /** Completed by the registry when the tab closes; ends [output]/[input] collection. */
    val closed = CompletableDeferred<Unit>()

    override val host: HostInfo
        get() = tab.spec.toHostInfo()

    override val status: StateFlow<ApiSessionStatus> = tab.sessionState.derive { it.status.toApi() }
    override val title: StateFlow<String?> = tab.sessionState.derive { it.title }
    override val pwd: StateFlow<String?> = tab.sessionState.derive { it.pwd }

    override fun output(capacity: Int): Flow<ByteStreamEvent> =
        tab.engine.outputTap.subscribe(capacity).completeWhen(closed)

    override fun input(capacity: Int): Flow<ByteStreamEvent> =
        tab.engine.inputTap.subscribe(capacity).completeWhen(closed)

    override fun write(bytes: ByteArray) = tab.engine.writeBytes(bytes.copyOf())

    override fun writeText(text: String) = tab.engine.writeBytes(text.toByteArray(Charsets.UTF_8))

    override suspend fun exec(command: String, pty: PtySize?): ExecChannel? =
        if (closed.isCompleted) null else tab.engine.openExec(command, pty)
}

/**
 * Ends collection normally once [signal] completes. A [ByteTap] subscription otherwise
 * waits forever, which would leak a coroutine per closed tab in every plugin that forgot
 * to cancel it.
 */
internal fun <T> Flow<T>.completeWhen(signal: Deferred<Unit>): Flow<T> =
    channelFlow {
        if (signal.isCompleted) return@channelFlow
        val forward = launch { collect { send(it) } }
        signal.await()
        forward.cancel()
    }.buffer(Channel.RENDEZVOUS)

/**
 * A read-only projection of a [StateFlow] that does no work until read or collected.
 * `stateIn` would need a live collector per projection to keep `value` fresh, which every
 * tab would pay for even with no plugins installed.
 */
private class DerivedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R
        get() = transform(source.value)

    override val replayCache: List<R>
        get() = listOf(value)

    // The source is a StateFlow, so this never completes, as StateFlow requires.
    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        error("StateFlow source completed")
    }
}

private fun <T, R> StateFlow<T>.derive(transform: (T) -> R): StateFlow<R> = DerivedStateFlow(this, transform)

internal fun SessionStatus.toApi(): ApiSessionStatus =
    when (this) {
        SessionStatus.Disconnected -> ApiSessionStatus.Disconnected
        SessionStatus.Connecting -> ApiSessionStatus.Connecting
        SessionStatus.Reconnecting -> ApiSessionStatus.Reconnecting
        SessionStatus.Connected -> ApiSessionStatus.Connected
        SessionStatus.Error -> ApiSessionStatus.Error
    }

internal fun TabSpec.toHostInfo(): HostInfo =
    HostInfo(
        hostId = hostId,
        name = tabLabel,
        host = host,
        port = port,
        username = username,
        transport =
            when (transport) {
                Transport.SSH -> SessionTransport.Ssh
                Transport.TailscaleSSH -> SessionTransport.TailscaleSsh
                Transport.Mosh -> SessionTransport.Mosh
                Transport.LocalShell -> SessionTransport.LocalShell
            },
        multiplexer = multiplexer?.id,
        multiplexerSession = multiplexerSessionName,
    )
