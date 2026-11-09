package com.jossephus.chuchu.ui.screens.SessionBrowser

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.transport.TransportRegistry

enum class SessionPickerStyle {
    Panel,
    Browser,
}

fun HostProfile.opensSessionBrowser(style: SessionPickerStyle): Boolean =
    if (transport == Transport.Plugin) {
        TransportRegistry.forId(transportId)?.hostBrowser != null
    } else {
        style == SessionPickerStyle.Browser &&
            multiplexer?.runtimeSupported == true &&
            transport != Transport.Mosh &&
            transport != Transport.LocalShell &&
            !(authMethod == AuthMethod.KeyWithPassphrase && keyPassphrase.isBlank())
    }
