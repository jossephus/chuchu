package com.jossephus.chuchu.plugins.herdr

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.PluginSession
import com.jossephus.chuchu.plugin.api.SessionEvent
import com.jossephus.chuchu.plugin.api.SessionStatus
import com.jossephus.chuchu.plugin.api.SessionViewProvider
import com.jossephus.chuchu.plugin.api.SettingsSchema
import com.jossephus.chuchu.plugin.api.ToggleField
import com.jossephus.chuchu.plugins.herdr.protocol.HerdrMultiplexer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * herdr for chuchu: adds herdr as a session-persistence multiplexer and, in native mode,
 * renders herdr's workspaces, tabs and split panes with chuchu's own terminals.
 *
 * This is chuchu PR #69 (by @salemsayed) rebuilt as a plugin on chuchu's plugin API.
 */
class HerdrPlugin : ChuchuPlugin {
    private val nativeSplits =
        ToggleField(
            key = "native_splits",
            title = "native splits",
            description = "render herdr's panes in chuchu; off shows herdr's own interface",
            default = true,
        )
    private val notifyAgents =
        ToggleField(
            key = "notify_agents",
            title = "notify when agents finish",
            description = "when a herdr agent finishes or needs input while chuchu is in the background",
            default = true,
        )

    private lateinit var host: PluginHost
    private lateinit var notifier: HerdrAgentNotifier

    // One controller per connected native-mode herdr tab, keyed by session id.
    private val controllers = MutableStateFlow<Map<String, HerdrController>>(emptyMap())

    override fun register(host: PluginHost) {
        this.host = host
        notifier = HerdrAgentNotifier(host) { host.settings[notifyAgents] }
        HerdrMultiplexer.nativeModeForHost = ::nativeModeFor
        host.registerMultiplexer(HerdrMultiplexer)
        host.registerHostSettings(SettingsSchema(listOf(nativeSplits)))
        host.registerSettings(SettingsSchema(listOf(notifyAgents)))
        host.registerSessionView(NativeSplitsView())
        host.scope.launch {
            host.sessions.events.collect { event ->
                when (event) {
                    is SessionEvent.StatusChanged ->
                        if (event.status == SessionStatus.Connected && isNative(event.session)) {
                            start(event.session)
                        } else {
                            // Reconnecting or gone: its exec channels are dead; restart on Connected.
                            stop(event.session.id)
                        }
                    is SessionEvent.Closed -> {
                        stop(event.session.id)
                        notifier.forget(event.session.id)
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onUnload() {
        controllers.value.keys.toList().forEach(::stop)
    }

    // Ad-hoc hosts have no saved settings; they get the default.
    private fun nativeModeFor(hostId: Long?): Boolean =
        hostId?.let { host.settings.forHost(it)[nativeSplits] } ?: nativeSplits.default

    private fun isNative(session: PluginSession): Boolean =
        session.host.multiplexer == HerdrMultiplexer.id && nativeModeFor(session.host.hostId)

    private fun start(session: PluginSession) {
        if (session.id in controllers.value) return
        val controller = HerdrController(host, session)
        controllers.update { it + (session.id to controller) }
        host.scope.launch {
            controller.state.map { it.snapshot }.filterNotNull().distinctUntilChanged().collect { snapshot ->
                notifier.onSnapshot(session.id, session.host.name, snapshot)
            }
        }
        host.log.info("${session.id}: herdr native mode started")
    }

    private fun stop(sessionId: String) {
        val controller = controllers.value[sessionId] ?: return
        controllers.update { it - sessionId }
        controller.close()
    }

    private inner class NativeSplitsView : SessionViewProvider {
        override val id = "native_splits"

        override fun claims(session: PluginSession) = isNative(session)

        @Composable
        override fun Content(session: PluginSession, modifier: Modifier, defaultTerminal: @Composable () -> Unit) {
            val current by controllers.collectAsState()
            val controller = current[session.id]
            if (controller == null) {
                // Connecting or reconnecting: chuchu's own terminal shows the connection state.
                Box(modifier) { defaultTerminal() }
            } else {
                HerdrSessionView(controller, modifier)
            }
        }
    }
}
