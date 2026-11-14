package com.jossephus.chuchu.plugins.rex

import com.jossephus.chuchu.plugin.api.BrowserGroup
import com.jossephus.chuchu.plugin.api.BrowserItem
import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.HostBrowser
import com.jossephus.chuchu.plugin.api.HostBrowserFactory
import com.jossephus.chuchu.plugin.api.HostBrowserState
import com.jossephus.chuchu.plugin.api.PairingPrompt
import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.TransportBehavior
import com.jossephus.chuchu.plugin.api.TransportConnectRequest
import com.jossephus.chuchu.plugin.api.TransportConnection
import com.jossephus.chuchu.plugin.api.TransportHostForm
import com.jossephus.chuchu.plugin.api.TransportProvider
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
        val count = RexServer.probeSessionCount(endpointFor(address))
        return "Connected · $count ${if (count == 1) "session" else "sessions"}"
    }

    override suspend fun connect(request: TransportConnectRequest): TransportConnection =
        RexPane.open(endpointFor(request.address), RexTargets.decode(request.target), request.size)

    override fun duplicateTarget(target: String?): String? {
        val sessionId =
            when (val request = RexTargets.decode(target)) {
                is RexOpenRequest.Block -> request.pane.sessionId
                is RexOpenRequest.NewWindow -> request.sessionId
                else -> null
            }
        return sessionId?.let(RexTargets::newWindow) ?: RexTargets.newSession()
    }

    private fun endpointFor(address: String): RexEndpoint =
        RexEndpoint.parse(address) ?: throw IllegalArgumentException("Not a Rex server address: $address")
}

/** A view of the server's shared session list; every browser on one server reads the same hub. */
private class RexHostBrowser(endpoint: RexEndpoint) : HostBrowser {
    private val server = RexServer.acquire(endpoint)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Server events keep the list current.
    override val updatesLive: Boolean = true

    override val state: StateFlow<HostBrowserState> =
        server.browser.state
            .map(::toBrowserState)
            .stateIn(scope, SharingStarted.Eagerly, HostBrowserState.Connecting)

    override suspend fun start() = server.awaitReady()

    override fun close() {
        scope.cancel()
        RexServer.release(server)
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
                        title = paneName(pane),
                        detail = pane.cwd?.let(::folderName),
                    )
                },
            newItemTarget = RexTargets.newWindow(session.sessionId),
        )
}

/**
 * Names a pane the way the official Rex clients do: a window name the user set, then
 * the live terminal title (e.g. Claude's task summary), then Rex's "Window <id>".
 */
internal fun paneName(pane: RexPaneSummary): String =
    pane.label?.takeIf { pane.labelIsCustom }
        ?: pane.title
        ?: pane.label
        ?: pane.program
        ?: "terminal"

/**
 * The last folder of a pane's working directory. The row is too narrow for the whole
 * path, and truncating it from the end hid the part that tells panes apart.
 */
internal fun folderName(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return "/"
    return trimmed.substringAfterLast('/')
}

sealed interface RexOpenRequest {
    data object Focused : RexOpenRequest

    data object NewSession : RexOpenRequest

    data class NewWindow(val sessionId: String) : RexOpenRequest

    data class Block(val pane: RexAttachment) : RexOpenRequest
}

internal object RexTargets {
    private const val PANE = "pane"
    private const val WINDOW = "window"
    private const val SESSION = "session"

    fun pane(pane: RexAttachment): String = "$PANE:${escape(pane.sessionId)}:${escape(pane.blockId)}"

    fun newWindow(sessionId: String): String = "$WINDOW:${escape(sessionId)}"

    fun newSession(): String = SESSION

    fun decode(target: String?): RexOpenRequest {
        val parts = target?.split(':') ?: return RexOpenRequest.Focused
        return when {
            parts == listOf(SESSION) -> RexOpenRequest.NewSession
            parts.size == 2 && parts[0] == WINDOW -> RexOpenRequest.NewWindow(unescape(parts[1]))
            parts.size == 3 && parts[0] == PANE -> RexOpenRequest.Block(RexAttachment(unescape(parts[1]), unescape(parts[2])))
            else -> RexOpenRequest.Focused
        }
    }

    private fun escape(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun unescape(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
}
