package com.jossephus.chuchu.ui.screens.SessionBrowser

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBrowserRoutingTest {
    private val tmuxHost =
        HostProfile(name = "box", host = "box", username = "me", multiplexer = MultiplexerType.Tmux)

    @Test
    fun onlyHostsWhoseSessionsCanBeListedBeforeConnectingOpenTheBrowser() {
        val cases =
            listOf(
                tmuxHost to true,
                tmuxHost.copy(multiplexer = MultiplexerType.Zmx) to true,
                tmuxHost.copy(transport = Transport.TailscaleSSH) to true,
                tmuxHost.copy(multiplexer = null) to false,
                tmuxHost.copy(multiplexer = MultiplexerType.Zellij) to false,
                tmuxHost.copy(transport = Transport.Mosh) to false,
                tmuxHost.copy(authMethod = AuthMethod.KeyWithPassphrase, keyPassphrase = "") to false,
                tmuxHost.copy(authMethod = AuthMethod.KeyWithPassphrase, keyPassphrase = "saved") to true,
            )
        cases.forEach { (host, expected) ->
            assertEquals("$host", expected, host.opensSessionBrowser())
        }
    }
}
