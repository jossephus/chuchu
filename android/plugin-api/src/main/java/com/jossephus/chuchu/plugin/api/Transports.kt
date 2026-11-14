package com.jossephus.chuchu.plugin.api

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TransportProvider {
    val id: String

    val displayName: String

    val hostForm: TransportHostForm

    val behavior: TransportBehavior
        get() = TransportBehavior()

    val hostBrowser: HostBrowserFactory?
        get() = null

    fun normalizeAddress(raw: String): String?

    fun addressFromPairingCode(code: String): String? = null

    suspend fun test(address: String): String

    suspend fun connect(request: TransportConnectRequest): TransportConnection

    /** Target for a duplicated tab, given the current tab's attached target. */
    fun duplicateTarget(target: String?): String? = null
}

class TransportHostForm(
    val addressLabel: String,
    val addressPlaceholder: String = "",
    val description: String? = null,
    val pairing: PairingPrompt? = null,
)

class PairingPrompt(
    val title: String,
    val hint: String,
)

class TransportBehavior(
    /** Drop the local emulator's replies to terminal queries. */
    val remoteAnswersQueries: Boolean = false,
    /** Keep only the selected tab of a host connected. */
    val activeTabOnly: Boolean = false,
    /** Mirror the browser group holding the active tab's target as tabs. */
    val tabsFollowBrowserGroup: Boolean = false,
)

class TransportConnectRequest(
    val address: String,
    val target: String?,
    val size: PtySize,
)

/** Pixel sizes are optional (0 = unknown). */
data class PtySize(val cols: Int, val rows: Int, val widthPx: Int = 0, val heightPx: Int = 0)

interface TransportConnection : AutoCloseable {
    /** What this connection attached to; passed back as the target on reconnect. */
    val target: String?

    val frames: Flow<TransportFrame>

    fun write(bytes: ByteArray)

    fun resize(size: PtySize)

    /**
     * The user touched this connection's terminal. Multi-client transports use it to
     * take the terminal back from another client (e.g. so their size applies again);
     * the default does nothing.
     */
    fun onUserInteraction() {}

    /**
     * Whether this connection's terminal is the one on screen. A host may keep a few
     * neighbouring tabs connected so switching to them is instant; a multi-client
     * transport should only claim its terminal (and apply this device's size) while
     * active, so preloading never takes a pane from another client. Starts inactive.
     */
    fun setActive(active: Boolean) {}

    override fun close()
}

sealed interface TransportFrame {
    class Output(val bytes: ByteArray) : TransportFrame

    class Snapshot(val bytes: ByteArray) : TransportFrame

    data class Resize(val cols: Int, val rows: Int) : TransportFrame
}

/** Thrown from [TransportConnection.frames] to end the tab with [message] instead of reconnecting. */
class TransportEndedException(message: String) : IOException(message)

fun interface HostBrowserFactory {
    fun open(address: String): HostBrowser
}

interface HostBrowser : AutoCloseable {
    val state: StateFlow<HostBrowserState>

    /** True when [state] follows the host on its own, so the list needs no refresh button. */
    val updatesLive: Boolean
        get() = false

    suspend fun start()

    override fun close()
}

sealed interface HostBrowserState {
    data object Connecting : HostBrowserState

    data class Ready(
        val groups: List<BrowserGroup>,
        val newGroupTarget: String? = null,
    ) : HostBrowserState

    data class Failed(val message: String) : HostBrowserState
}

data class BrowserGroup(
    val id: String,
    val label: String,
    val items: List<BrowserItem>,
    val newItemTarget: String? = null,
)

data class BrowserItem(
    val target: String,
    val title: String,
    val detail: String? = null,
    val status: ProgramStatus? = null,
)
