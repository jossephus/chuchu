package com.jossephus.chuchu.ui.screens.SessionBrowser

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.plugin.PluginManager

enum class SessionPickerStyle {
    Panel,
    Browser,
}

fun HostProfile.opensSessionBrowser(style: SessionPickerStyle, plugins: PluginManager): Boolean =
    if (transportId != null) {
        plugins.transports[transportId]?.hostBrowser != null
    } else {
        style == SessionPickerStyle.Browser &&
            transport.hostsMultiplexer &&
            multiplexer?.let { plugins.multiplexers[it.id] } != null &&
            !(authMethod == AuthMethod.KeyWithPassphrase && keyPassphrase.isBlank())
    }
