package com.jossephus.chuchu.plugin.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Creates terminals backed by chuchu's own Ghostty emulator, rendered with the user's
 * theme, font and keyboard handling. Plugins supply the bytes and carry the input wherever
 * it belongs, e.g. one terminal per multiplexer pane fed from an [ExecChannel].
 */
fun interface TerminalFactory {
    /** A new terminal owned by the caller. Close it when done; each holds a native emulator. */
    fun create(options: TerminalOptions): PluginTerminal
}

fun TerminalFactory.create(): PluginTerminal = create(TerminalOptions())

class TerminalOptions(
    /** Local scrollback lines. Use 0 when the program scrolls remotely (see [onScroll]). */
    val scrollbackLines: Int = 1000,
    /**
     * When set, scroll gestures call this with a line count (positive towards newer output)
     * instead of scrolling the local scrollback, e.g. to forward them to a multiplexer that
     * keeps the history on the server.
     */
    val onScroll: ((lines: Int) -> Unit)? = null,
)

interface PluginTerminal : AutoCloseable {
    /** Feeds output (from wherever the plugin gets it) into the emulator. Thread-safe. */
    fun feed(bytes: ByteArray)

    /**
     * Bytes to send to the program behind this terminal: what the user types or pastes into
     * it (terminal-encoded) plus the emulator's replies to terminal queries. Single
     * collector; nothing is dropped while uncollected.
     */
    val input: Flow<ByteArray>

    /** Grid size, updated as [Content] lays out. Resize the remote PTY to match. */
    val size: StateFlow<PtySize>

    /**
     * Sets the emulator's grid directly, for programs that render at a size they choose
     * (e.g. a multiplexer reporting each frame's dimensions). [size] keeps reporting the
     * size the view would like; the next layout change applies that again.
     */
    fun setGridSize(cols: Int, rows: Int)

    val title: StateFlow<String?>

    /**
     * Renders the terminal. Tapping calls [onTap]; while [focused] is true the soft keyboard
     * types into this terminal (keep at most one terminal focused on screen). Focus changes
     * are reported to the program on [input] when it enabled focus reporting.
     */
    @Composable
    fun Content(modifier: Modifier, focused: Boolean, onTap: () -> Unit)

    /** Releases the emulator; safe to call more than once. */
    override fun close()
}
