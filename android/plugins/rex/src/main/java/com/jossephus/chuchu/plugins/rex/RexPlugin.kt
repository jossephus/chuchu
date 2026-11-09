package com.jossephus.chuchu.plugins.rex

import com.jossephus.chuchu.plugin.api.BrowserGroup
import com.jossephus.chuchu.plugin.api.BrowserItem
import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.HostBrowser
import com.jossephus.chuchu.plugin.api.HostBrowserFactory
import com.jossephus.chuchu.plugin.api.HostBrowserState
import com.jossephus.chuchu.plugin.api.PairingPrompt
import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.PtySize
import com.jossephus.chuchu.plugin.api.TransportBehavior
import com.jossephus.chuchu.plugin.api.TransportConnectRequest
import com.jossephus.chuchu.plugin.api.TransportConnection
import com.jossephus.chuchu.plugin.api.TransportEndedException
import com.jossephus.chuchu.plugin.api.TransportFrame
import com.jossephus.chuchu.plugin.api.TransportHostForm
import com.jossephus.chuchu.plugin.api.TransportProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class RexPlugin : ChuchuPlugin {
    override fun register(host: PluginHost) {
        host.registerTransport(RexTransportProvider())
    }
}

class RexTransportProvider : TransportProvider {
    override val id: String = "rex"

    override val displayName: String = "Rex"

    override val hostForm: TransportHostForm =
        TransportHostForm(
            addressLabel = "Rex address",
            addressPlaceholder = "https://my-mac.tailnet.ts.net",
            description =
                "Connects to a Rex server and opens the pane that has focus. On the computer, turn on Rex's Tailscale (`rex server tailscale on`), then run `rex server tailscale pair` and scan the code. This phone must be on the same tailnet through the Tailscale app.",
            pairing =
                PairingPrompt(
                    title = "scan rex pairing code",
                    hint = "On the computer running Rex, run `rex server tailscale pair` and point the camera at the code.",
                ),
        )

    override val behavior: TransportBehavior =
        TransportBehavior(
            remoteAnswersQueries = true,
            activeTabOnly = true,
            tabsFollowBrowserGroup = true,
        )

    override val hostBrowser: HostBrowserFactory =
        HostBrowserFactory { address -> RexHostBrowser(endpointFor(address)) }

    override fun normalizeAddress(raw: String): String? = RexEndpoint.parse(raw)?.url

    override fun addressFromPairingCode(code: String): String? = RexEndpoint.fromPairingCode(code)?.url

    override suspend fun test(address: String): String {
        val count = RexTerminalSession.probeSessionCount(endpointFor(address))
        return "Connected · $count ${if (count == 1) "session" else "sessions"}"
    }

    override suspend fun connect(request: TransportConnectRequest): TransportConnection {
        val session = RexTerminalSession(endpointFor(request.address), RexTargets.decode(request.target))
        try {
            session.connect(request.size.toRexSize())
        } catch (e: Exception) {
            session.close()
            throw e
        }
        return RexTransportConnection(session)
    }

    override fun duplicateTarget(target: String?): String? {
        val sessionId = RexTargets.decode(target).sessionId
        return sessionId?.let(RexTargets::newWindow) ?: RexTargets.newSession()
    }

    private fun endpointFor(address: String): RexEndpoint =
        RexEndpoint.parse(address) ?: throw IllegalArgumentException("Not a Rex server address: $address")
}

private class RexTransportConnection(private val session: RexTerminalSession) : TransportConnection {
    override val target: String? =
        session.attachment?.let(RexTargets::pane)

    override val frames: Flow<TransportFrame> =
        flow {
            try {
                for (item in session.stream) {
                    when (item) {
                        is RexStreamItem.Snapshot -> emit(TransportFrame.Snapshot(item.bytes))
                        is RexStreamItem.Output -> emit(TransportFrame.Output(item.bytes))
                        is RexStreamItem.Resize ->
                            if (session.shouldApplyRemoteResize(item.columns, item.rows)) {
                                emit(TransportFrame.Resize(item.columns, item.rows))
                            }
                        is RexStreamItem.ChildExited -> Unit
                    }
                }
            } catch (e: RexPaneClosedException) {
                throw TransportEndedException("The Rex pane was closed. Reconnect to open the pane that has focus now.")
            }
        }

    override fun write(bytes: ByteArray) = session.write(bytes)

    override fun resize(size: PtySize) = session.resize(size.toRexSize())

    override fun close() = session.close()
}

private class RexHostBrowser(endpoint: RexEndpoint) : HostBrowser {
    private val browser = RexSessionBrowser(endpoint)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val state: StateFlow<HostBrowserState> =
        browser.state
            .map(::toBrowserState)
            .stateIn(scope, SharingStarted.Eagerly, HostBrowserState.Connecting)

    override suspend fun start() {
        browser.start()
    }

    override fun close() {
        browser.close()
        scope.cancel()
    }

    private fun toBrowserState(state: RexBrowserState): HostBrowserState =
        when (state) {
            RexBrowserState.Connecting -> HostBrowserState.Connecting
            is RexBrowserState.Failed -> HostBrowserState.Failed(state.message)
            is RexBrowserState.Ready ->
                HostBrowserState.Ready(
                    groups = state.sessions.map(::toGroup),
                    newGroupTarget = RexTargets.newSession(),
                )
        }

    private fun toGroup(session: RexSessionSummary): BrowserGroup =
        BrowserGroup(
            id = session.sessionId,
            label = session.label,
            items =
                session.panes.map { pane ->
                    BrowserItem(
                        target = RexTargets.pane(RexAttachment(pane.sessionId, pane.blockId)),
                        title = pane.title ?: pane.program ?: pane.label ?: "terminal",
                        detail = pane.cwd,
                    )
                },
            newItemTarget = RexTargets.newWindow(session.sessionId),
        )
}

private fun PtySize.toRexSize(): RexTerminalSize =
    RexTerminalSize(
        columns = cols,
        rows = rows,
        cellWidthPx = if (widthPx > 0 && cols > 0) widthPx / cols else 0,
        cellHeightPx = if (heightPx > 0 && rows > 0) heightPx / rows else 0,
    )
