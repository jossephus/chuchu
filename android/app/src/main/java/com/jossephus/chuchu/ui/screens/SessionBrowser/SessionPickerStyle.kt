package com.jossephus.chuchu.ui.screens.SessionBrowser

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.Transport

enum class SessionPickerStyle {
    Panel,
    Browser,
}

fun HostProfile.opensSessionBrowser(): Boolean =
    multiplexer?.runtimeSupported == true &&
        transport != Transport.Mosh &&
        transport != Transport.LocalShell &&
        !(authMethod == AuthMethod.KeyWithPassphrase && keyPassphrase.isBlank())
