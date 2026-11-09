package com.jossephus.chuchu.ui.screens.Terminal

import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.terminal.TabSession
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jossephus.chuchu.R

internal fun terminalTabDisplayLabel(tab: TabSession): String =
    tab.titleOverride.value?.takeIf(String::isNotBlank)
        ?: if (tab.spec.transport == Transport.LocalShell) tab.spec.tabLabel else terminalTabAlias(tab)

@Composable
internal fun terminalTabEndpointLabel(tab: TabSession): String =
    if (tab.spec.transport == Transport.LocalShell) {
        stringResource(R.string.terminal_endpoint_local_shell)
    } else {
        tab.spec.transport.endpoint(tab.spec.username, tab.spec.host, tab.spec.port)
    }

internal fun terminalTabAlias(tab: TabSession): String {
    val adjectives =
        listOf(
            "amber",
            "brisk",
            "cedar",
            "delta",
            "ember",
            "frost",
            "golden",
            "hazel",
            "indigo",
            "jade",
            "kilo",
            "lunar",
            "mango",
            "nova",
            "onyx",
            "pluto",
        )
    val nouns =
        listOf(
            "otter",
            "falcon",
            "pine",
            "river",
            "comet",
            "harbor",
            "meadow",
            "quartz",
            "signal",
            "orbit",
            "anchor",
            "summit",
            "thunder",
            "voyager",
            "willow",
            "zenith",
        )
    val seed = tab.id.hashCode().let { if (it == Int.MIN_VALUE) 0 else abs(it) }
    return "${adjectives[seed % adjectives.size]}-${nouns[(seed / adjectives.size) % nouns.size]}"
}
