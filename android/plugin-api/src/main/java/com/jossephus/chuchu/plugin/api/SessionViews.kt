package com.jossephus.chuchu.plugin.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Replaces or decorates the terminal area of tabs it claims: the space between the tab
 * strip and the keyboard accessory bar.
 *
 * - **Replace** (e.g. native multiplexer splits): draw your own UI and don't call
 *   `defaultTerminal`. The tab's own terminal, including its keyboard input view, is then
 *   not shown, so your UI owns input for that area.
 * - **Decorate** (e.g. a status line or overlay): lay out your UI around or over a
 *   `defaultTerminal()` call.
 *
 * Every provider that claims a tab is used, nested in plugin load order: the first one's
 * `defaultTerminal` renders the second, and so on down to chuchu's own terminal. So
 * decorators from different plugins stack, and a replacing provider hides whatever is
 * nested inside it.
 */
interface SessionViewProvider {
    /** Unique within the plugin. */
    val id: String

    /**
     * Whether this provider handles [session]. Re-evaluated when the active tab or the set
     * of providers changes, so base it on stable facts (host, multiplexer). For behavior
     * that changes mid-session, claim the tab and call `defaultTerminal()` from [Content]
     * when you want the stock view back. Throwing disables the plugin.
     */
    fun claims(session: PluginSession): Boolean

    /**
     * Must apply [modifier], which sizes the terminal area. Exceptions thrown during
     * composition can't be isolated and crash chuchu, so guard risky logic outside
     * composition (e.g. in [PluginHost.scope]) and render from state.
     */
    @Composable
    fun Content(session: PluginSession, modifier: Modifier, defaultTerminal: @Composable () -> Unit)
}
