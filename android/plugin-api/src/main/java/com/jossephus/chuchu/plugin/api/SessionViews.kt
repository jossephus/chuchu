package com.jossephus.chuchu.plugin.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Replaces or decorates the terminal area of the tabs it claims. Every claiming provider is
 * nested in plugin load order; not calling `defaultTerminal` replaces everything inside.
 */
interface SessionViewProvider {
    val id: String

    /** Re-evaluated when the active tab changes. Throwing disables the plugin. */
    fun claims(session: PluginSession): Boolean

    /**
     * Must apply [modifier]. Exceptions thrown during composition crash chuchu, so keep
     * risky work in [PluginHost.scope].
     */
    @Composable
    fun Content(session: PluginSession, modifier: Modifier, defaultTerminal: @Composable () -> Unit)
}
