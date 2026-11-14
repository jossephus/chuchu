package com.jossephus.chuchu.plugin

import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.TransportConnectRequest
import com.jossephus.chuchu.plugin.api.TransportConnection
import com.jossephus.chuchu.plugin.api.TransportHostForm
import com.jossephus.chuchu.plugin.api.TransportProvider
import com.jossephus.chuchu.plugin.builtin.TmuxMultiplexer
import com.jossephus.chuchu.plugin.builtin.ZmxMultiplexer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PluginManagerTest {
    private val failures = ArrayList<String>()
    private val manager = PluginManager { message, _ -> failures += message }

    private class FakeTransport(override val id: String) : TransportProvider {
        override val displayName = id
        override val hostForm = TransportHostForm(addressLabel = "address")

        override fun normalizeAddress(raw: String): String? = raw

        override suspend fun test(address: String): String = "ok"

        override suspend fun connect(request: TransportConnectRequest): TransportConnection = error("unused")
    }

    @Test
    fun registerFailureRollsBackOnlyThatPluginsContributions() {
        manager.load(
            mapOf(
                "failing" to
                    ChuchuPlugin {
                        it.registerTransport(FakeTransport("half-registered"))
                        it.registerMultiplexer(ZmxMultiplexer)
                        error("boom")
                    },
                "healthy" to ChuchuPlugin { it.registerTransport(FakeTransport("healthy")) },
            ),
        )

        assertNull(manager.transports["half-registered"])
        assertNull(manager.multiplexers["zmx"])
        assertEquals(listOf("healthy"), manager.transports.items.value.map { it.id })
        assertEquals(1, failures.size)
    }

    @Test
    fun duplicateIdKeepsTheFirstProviderAndRollsBackTheSecondPlugin() {
        val first = FakeTransport("dup")
        manager.load(
            mapOf(
                "first" to ChuchuPlugin { it.registerTransport(first) },
                "second" to
                    ChuchuPlugin {
                        it.registerMultiplexer(TmuxMultiplexer)
                        it.registerTransport(FakeTransport("dup"))
                    },
            ),
        )

        assertSame(first, manager.transports["dup"])
        assertNull(manager.multiplexers["tmux"])
    }

    @Test
    fun builtinPluginsProvideTmuxAndZmx() {
        manager.load(BuiltinPlugins.all)

        assertSame(TmuxMultiplexer, manager.multiplexers["tmux"])
        assertSame(ZmxMultiplexer, manager.multiplexers["zmx"])
        assertEquals(emptyList<String>(), failures)
    }
}
