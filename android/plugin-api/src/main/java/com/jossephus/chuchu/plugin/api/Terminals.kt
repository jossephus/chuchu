package com.jossephus.chuchu.plugin.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

fun interface TerminalFactory {
    /** Each terminal holds a native emulator; close it when done. */
    fun create(options: TerminalOptions): PluginTerminal
}

fun TerminalFactory.create(): PluginTerminal = create(TerminalOptions())

class TerminalOptions(
    val scrollbackLines: Int = 1000,
    /** When set, scroll gestures call this (positive = newer output) instead of scrolling locally. */
    val onScroll: ((lines: Int) -> Unit)? = null,
)

interface PluginTerminal : AutoCloseable {
    /** Thread-safe. */
    fun feed(bytes: ByteArray)

    /** User input and the emulator's query replies. Single collector. */
    val input: Flow<ByteArray>

    /** The size the view wants; resize the remote to match. */
    val size: StateFlow<PtySize>

    /** Overrides the grid until the next layout change. */
    fun setGridSize(cols: Int, rows: Int)

    /** Keep at most one terminal [focused] on screen. */
    @Composable
    fun Content(modifier: Modifier, focused: Boolean, onTap: () -> Unit)

    override fun close()
}
