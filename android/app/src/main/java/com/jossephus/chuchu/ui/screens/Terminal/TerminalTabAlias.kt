package com.jossephus.chuchu.ui.screens.Terminal

import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.terminal.TabSession
import kotlin.math.abs

internal fun terminalTabDisplayLabel(tab: TabSession): String =
    if (tab.spec.transport == Transport.LocalShell) tab.spec.tabLabel else terminalTabAlias(tab)

internal fun terminalTabEndpointLabel(tab: TabSession): String =
    if (tab.spec.transport == Transport.LocalShell) {
        "this device · app sandbox"
    } else {
        "${tab.spec.username}@${tab.spec.host}:${tab.spec.port}"
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
