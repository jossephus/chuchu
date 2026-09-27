package com.jossephus.chuchu.plugin.builtin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.plugin.api.ByteStreamEvent
import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.PluginCommand
import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.PluginSession
import com.jossephus.chuchu.plugin.api.PluginTerminal
import com.jossephus.chuchu.plugin.api.SessionEvent
import com.jossephus.chuchu.plugin.api.SessionViewProvider
import com.jossephus.chuchu.plugin.api.PtySize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Debug-build-only plugin that exercises the plugin API end to end on a device:
 *
 * - logs session events and output throughput (`adb logcat -s ChuchuPlugin:session_probe`);
 * - "mirror" (chuchu key + `m`, or the accessory button) splits the active tab: chuchu's
 *   own terminal on top, a plugin-owned [PluginTerminal] below fed from the tab's output
 *   stream. Typing into the mirror writes to the session.
 * - "exec" (chuchu key + `x`) runs exec-channel checks on the active SSH tab and logs them:
 *   separate stdout/stderr + exit code, a PTY channel, and a 5 MB stream, concurrently.
 *
 * It doubles as a compact example of commands, session views and plugin terminals.
 */
class SessionProbePlugin : ChuchuPlugin {
    private class Mirror(val terminal: PluginTerminal, val jobs: List<Job>)

    private lateinit var host: PluginHost
    private val mirrors = MutableStateFlow<Map<String, Mirror>>(emptyMap())

    override fun register(host: PluginHost) {
        this.host = host
        host.scope.launch {
            host.sessions.events.collect { event ->
                host.log.debug(describe(event))
                when (event) {
                    // Off the main thread: byte streams can be busy.
                    is SessionEvent.Opened -> host.scope.launch(Dispatchers.Default) { probeOutput(event.session) }
                    is SessionEvent.Closed -> stopMirror(event.session.id)
                    else -> Unit
                }
            }
        }
        host.registerCommand(
            PluginCommand(id = "mirror", title = "mirror", key = 'm', accessoryLabel = "mirror") { context ->
                val session = context.session ?: return@PluginCommand
                if (session.id in mirrors.value) stopMirror(session.id) else startMirror(session)
            },
        )
        host.registerSessionView(MirrorView())
        host.registerCommand(
            PluginCommand(id = "exec", title = "exec", key = 'x', accessoryLabel = "exec") { context ->
                val session = context.session ?: return@PluginCommand
                host.scope.launch { runExecChecks(session) }
            },
        )
    }

    private suspend fun runExecChecks(session: PluginSession) = coroutineScope {
        val log = { message: String -> host.log.debug("${session.id}: exec $message") }
        val plain = session.exec("echo out-line; echo err-line >&2; exit 3")
        if (plain == null) {
            log("unavailable (not a connected SSH session)")
            return@coroutineScope
        }
        val pty = session.exec("tty; stty size", PtySize(cols = 100, rows = 30))!!
        val bulk = session.exec("head -c 5000000 /dev/zero")!!
        launch {
            val out = async { plain.stdout.toList().joinToString("") { String(it) } }
            val err = async { plain.stderr.toList().joinToString("") { String(it) } }
            log("plain stdout=${out.await().trim()} stderr=${err.await().trim()} exit=${plain.exitCode.await()}")
        }
        launch {
            val text = pty.stdout.toList().joinToString("") { String(it) }
            log("pty output=${text.trim().replace("\r\n", " | ")} exit=${pty.exitCode.await()}")
        }
        launch(Dispatchers.Default) {
            val started = System.nanoTime()
            var bytes = 0L
            bulk.stdout.collect { bytes += it.size }
            val seconds = (System.nanoTime() - started) / 1e9
            log("bulk $bytes bytes in ${"%.2f".format(seconds)} s exit=${bulk.exitCode.await()}")
        }
    }

    override fun onUnload() {
        mirrors.value.keys.toList().forEach(::stopMirror)
    }

    private fun startMirror(session: PluginSession) {
        val terminal = host.terminals.create()
        val output = host.scope.launch(Dispatchers.Default) {
            session.output().collect { event ->
                if (event is ByteStreamEvent.Data) terminal.feed(event.bytes)
            }
        }
        val input = host.scope.launch { terminal.input.collect(session::write) }
        mirrors.value = mirrors.value + (session.id to Mirror(terminal, listOf(output, input)))
        host.log.debug("${session.id}: mirror on")
    }

    private fun stopMirror(sessionId: String) {
        val mirror = mirrors.value[sessionId] ?: return
        mirrors.value = mirrors.value - sessionId
        mirror.jobs.forEach(Job::cancel)
        mirror.terminal.close()
        host.log.debug("$sessionId: mirror off")
    }

    private inner class MirrorView : SessionViewProvider {
        override val id = "mirror"

        // Claims every tab; tabs without a mirror just render the stock terminal.
        override fun claims(session: PluginSession) = true

        @Composable
        override fun Content(session: PluginSession, modifier: Modifier, defaultTerminal: @Composable () -> Unit) {
            val current by mirrors.collectAsStateWithLifecycle()
            val mirror = current[session.id]
            if (mirror == null) {
                Box(modifier) { defaultTerminal() }
                return
            }
            var mirrorFocused by remember { mutableStateOf(false) }
            Column(modifier) {
                Box(Modifier.fillMaxWidth().weight(1f)) { defaultTerminal() }
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF7AA2F7)))
                mirror.terminal.Content(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    focused = mirrorFocused,
                    onTap = { mirrorFocused = true },
                )
            }
        }
    }

    private suspend fun probeOutput(session: PluginSession) {
        var bytes = 0L
        var chunks = 0L
        var dropped = 0L
        var lastReportMs = System.currentTimeMillis()
        // Completes when the tab closes, so this coroutine ends with it.
        session.output().collect { event ->
            when (event) {
                is ByteStreamEvent.Data -> {
                    bytes += event.bytes.size
                    chunks++
                }
                is ByteStreamEvent.Gap -> dropped += event.droppedBytes
            }
            val now = System.currentTimeMillis()
            if (now - lastReportMs >= REPORT_INTERVAL_MS) {
                host.log.debug("${session.id}: output $bytes bytes in $chunks chunks, $dropped dropped")
                lastReportMs = now
            }
        }
        host.log.debug("${session.id}: closed after $bytes bytes in $chunks chunks, $dropped dropped")
    }

    private fun describe(event: SessionEvent): String {
        val id = event.session.id
        return when (event) {
            is SessionEvent.Opened -> "$id: opened (${event.session.host.transport}, ${event.session.host.name})"
            is SessionEvent.Closed -> "$id: closed"
            is SessionEvent.StatusChanged ->
                "$id: ${event.status}" +
                    (if (event.reconnectAttempt > 0) " attempt ${event.reconnectAttempt}" else "") +
                    (event.error?.let { " ($it)" } ?: "")
            is SessionEvent.TitleChanged -> "$id: title=${event.title}"
            is SessionEvent.PwdChanged -> "$id: pwd=${event.pwd}"
            is SessionEvent.Bell -> "$id: bell x${event.count}"
        }
    }

    private companion object {
        // Throughput is logged at most this often per session; per-chunk logs would flood logcat.
        const val REPORT_INTERVAL_MS = 5_000L
    }
}
