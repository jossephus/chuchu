package com.jossephus.chuchu.ui.screens.SessionBrowser

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.plugin.PluginManager
import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.HostBrowserFactory
import com.jossephus.chuchu.plugin.api.TransportConnectRequest
import com.jossephus.chuchu.plugin.api.TransportConnection
import com.jossephus.chuchu.plugin.api.TransportHostForm
import com.jossephus.chuchu.plugin.api.TransportProvider
import com.jossephus.chuchu.plugin.builtin.TmuxMultiplexer
import com.jossephus.chuchu.plugin.builtin.ZmxMultiplexer
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBrowserRoutingTest {
    private val tmuxHost =
        HostProfile(name = "box", host = "box", username = "me", multiplexer = MultiplexerType.Tmux)

    private class FakeTransport(override val id: String, browsable: Boolean) : TransportProvider {
        override val displayName = id
        override val hostForm = TransportHostForm(addressLabel = "address")
        override val hostBrowser: HostBrowserFactory? =
            if (browsable) HostBrowserFactory { error("not opened in routing tests") } else null

        override fun normalizeAddress(raw: String): String? = raw

        override suspend fun test(address: String): String = "ok"

        override suspend fun connect(request: TransportConnectRequest): TransportConnection = error("unused")
    }

    private val plugins =
        PluginManager { _, error -> throw error }.apply {
            load(
                mapOf(
                    "test" to
                        ChuchuPlugin {
                            it.registerMultiplexer(TmuxMultiplexer)
                            it.registerMultiplexer(ZmxMultiplexer)
                            it.registerTransport(FakeTransport("routing-browsable", browsable = true))
                            it.registerTransport(FakeTransport("routing-plain", browsable = false))
                        },
                ),
            )
        }

    @Test
    fun browserStyleOpensItOnlyForHostsWhoseSessionsCanBeListedBeforeConnecting() {
        val cases =
            listOf(
                tmuxHost to true,
                tmuxHost.copy(multiplexer = MultiplexerType("zmx")) to true,
                tmuxHost.copy(transport = Transport.TailscaleSSH) to true,
                tmuxHost.copy(multiplexer = null) to false,
                tmuxHost.copy(multiplexer = MultiplexerType("zellij")) to false,
                tmuxHost.copy(transport = Transport.Mosh) to false,
                tmuxHost.copy(authMethod = AuthMethod.KeyWithPassphrase, keyPassphrase = "") to false,
                tmuxHost.copy(authMethod = AuthMethod.KeyWithPassphrase, keyPassphrase = "saved") to true,
            )
        cases.forEach { (host, expected) ->
            assertEquals("$host", expected, host.opensSessionBrowser(SessionPickerStyle.Browser, plugins))
        }
        assertEquals(false, tmuxHost.opensSessionBrowser(SessionPickerStyle.Panel, plugins))
    }

    @Test
    fun pluginTransportHostsOpenItWhenTheirTransportHasABrowserWhateverTheStyle() {
        val pluginHost = HostProfile(name = "rex", host = "https://rex", username = "", transport = Transport.Plugin)

        assertEquals(true, pluginHost.copy(transportId = "routing-browsable").opensSessionBrowser(SessionPickerStyle.Panel, plugins))
        assertEquals(false, pluginHost.copy(transportId = "routing-plain").opensSessionBrowser(SessionPickerStyle.Browser, plugins))
        assertEquals(false, pluginHost.copy(transportId = "routing-missing").opensSessionBrowser(SessionPickerStyle.Browser, plugins))
    }
}
