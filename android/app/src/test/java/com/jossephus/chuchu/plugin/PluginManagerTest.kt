package com.jossephus.chuchu.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.ExecChannel
import com.jossephus.chuchu.plugin.api.HostInfo
import com.jossephus.chuchu.plugin.api.PluginApi
import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.PluginSession
import com.jossephus.chuchu.plugin.api.SessionEvent
import com.jossephus.chuchu.plugin.api.SessionRegistry
import com.jossephus.chuchu.plugin.api.SessionStatus
import com.jossephus.chuchu.plugin.api.SessionTransport
import com.jossephus.chuchu.plugin.api.SessionViewProvider
import com.jossephus.chuchu.plugin.api.SettingsSchema
import com.jossephus.chuchu.plugin.api.ToggleField
import com.jossephus.chuchu.plugin.api.TransportConnectRequest
import com.jossephus.chuchu.plugin.api.TransportConnection
import com.jossephus.chuchu.plugin.api.TransportHostForm
import com.jossephus.chuchu.plugin.api.TransportProvider
import com.jossephus.chuchu.service.transport.TransportRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManagerTest {
    private open class RecordingPlugin(private val onRegister: (PluginHost) -> Unit = {}) : ChuchuPlugin {
        lateinit var host: PluginHost
        var unloadCount = 0

        override fun register(host: PluginHost) {
            this.host = host
            onRegister(host)
        }

        override fun onUnload() {
            unloadCount++
        }
    }

    private val registry = object : SessionRegistry {
        override val sessions = MutableStateFlow<List<PluginSession>>(emptyList())
        override val events = MutableSharedFlow<SessionEvent>()
    }

    // Unconfined runs plugin coroutines inline, so scope failures are observable synchronously.
    // One store per plugin id, like the SharedPreferences-backed factory.
    private val stores = HashMap<String, InMemoryPluginPrefs>()

    private val manager =
        PluginManager(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            registry,
            prefsFactory = { id -> stores.getOrPut(id) { InMemoryPluginPrefs() } },
            terminalFactory = { error("no terminals in unit tests") },
        ) { _, _, _, _ -> }

    private fun entry(
        id: String,
        apiVersion: Int = PluginApi.VERSION,
        create: () -> ChuchuPlugin,
    ) = PluginEntry(PluginDescriptor(id, id, "1.0.0", apiVersion, PluginSource.Builtin), create = create)

    private fun status(id: String): PluginStatus? =
        manager.plugins.value.find { it.descriptor.id == id }?.status

    @Test
    fun registersPluginAndSharesTheSessionRegistry() {
        val plugin = RecordingPlugin()
        manager.load(listOf(entry("alpha") { plugin }))

        assertEquals(PluginStatus.Active, status("alpha"))
        assertSame(registry, plugin.host.sessions)
    }

    @Test
    fun registerFailureDisablesPluginAndRollsBackItsContributions() {
        val healthy = RecordingPlugin()
        val failing = RecordingPlugin {
            it.registerSessionView(MultiplexerView("half-registered"))
            error("boom")
        }
        manager.load(listOf(entry("failing") { failing }, entry("healthy") { healthy }))

        assertEquals(PluginStatus.Failed, status("failing"))
        assertTrue(manager.plugins.value.first().error!!.contains("boom"))
        assertEquals(1, failing.unloadCount)
        assertFalse(failing.host.scope.isActive)
        assertTrue(manager.sessionViews.value.isEmpty())
        assertEquals(PluginStatus.Active, status("healthy"))
    }

    @Test
    fun scopeFailureDisablesOnlyThatPlugin() {
        val crashing = RecordingPlugin()
        val healthy = RecordingPlugin()
        manager.load(listOf(entry("crashing") { crashing }, entry("healthy") { healthy }))

        crashing.host.scope.launch { error("background crash") }

        assertEquals(PluginStatus.Failed, status("crashing"))
        assertFalse(crashing.host.scope.isActive)
        assertEquals(PluginStatus.Active, status("healthy"))
        assertTrue(healthy.host.scope.isActive)
    }

    @Test
    fun disabledPluginCannotRegister() {
        val crashing = RecordingPlugin()
        manager.load(listOf(entry("crashing") { crashing }))
        crashing.host.scope.launch { error("background crash") }

        val result = runCatching { crashing.host.registerSessionView(MultiplexerView("late")) }

        assertTrue(result.isFailure)
        assertTrue(manager.sessionViews.value.isEmpty())
    }

    @Test
    fun unsupportedApiVersionIsNeverInstantiated() {
        var created = false
        manager.load(listOf(entry("future", apiVersion = PluginApi.VERSION + 1) { created = true; RecordingPlugin() }))

        assertEquals(PluginStatus.Incompatible, status("future"))
        assertFalse(created)
    }

    @Test
    fun constructorFailureIsRecordedAsFailed() {
        manager.load(listOf(entry("broken") { error("no constructor") }))

        assertEquals(PluginStatus.Failed, status("broken"))
    }

    @Test
    fun reloadingKnownIdsIsANoOp() {
        var instances = 0
        val alpha = entry("alpha") { instances++; RecordingPlugin() }
        manager.load(listOf(alpha))
        manager.load(listOf(alpha))

        assertEquals(1, instances)
        assertEquals(1, manager.plugins.value.size)
    }

    private class FakeSession(multiplexer: String?) : PluginSession {
        override val id = "tab-1"
        override val host =
            HostInfo(1, "box", "box.local", 22, "me", SessionTransport.Ssh, multiplexer)
        override val status = MutableStateFlow(SessionStatus.Connected)
        override suspend fun exec(command: String): ExecChannel? = null
    }

    private class MultiplexerView(private val multiplexer: String) : SessionViewProvider {
        override val id = multiplexer
        override fun claims(session: PluginSession) = session.host.multiplexer == multiplexer

        @Composable
        override fun Content(session: PluginSession, modifier: Modifier, defaultTerminal: @Composable () -> Unit) {
            defaultTerminal()
        }
    }

    @Test
    fun sessionViewsAreChosenByClaimsInLoadOrder() {
        manager.load(
            listOf(
                entry("herdr_view") { RecordingPlugin { it.registerSessionView(MultiplexerView("herdr")) } },
                entry("status_bar") { RecordingPlugin { it.registerSessionView(MultiplexerView("herdr")) } },
            ),
        )
        val views = manager.sessionViews.value

        assertEquals(
            listOf("herdr_view", "status_bar"),
            manager.sessionViewsFor(FakeSession("herdr"), views).map { it.pluginId },
        )
        assertTrue(manager.sessionViewsFor(FakeSession("tmux"), views).isEmpty())
    }

    @Test
    fun throwingClaimsDisablesPluginAndFallsThroughToNextProvider() {
        val broken = object : SessionViewProvider by MultiplexerView("herdr") {
            override fun claims(session: PluginSession): Boolean = error("claims blew up")
        }
        manager.load(
            listOf(
                entry("broken") { RecordingPlugin { it.registerSessionView(broken) } },
                entry("working") { RecordingPlugin { it.registerSessionView(MultiplexerView("herdr")) } },
            ),
        )

        val chosen = manager.sessionViewsFor(FakeSession("herdr"), manager.sessionViews.value)

        assertEquals(listOf("working"), chosen.map { it.pluginId })
        assertEquals(PluginStatus.Failed, status("broken"))
        assertTrue(manager.sessionViews.value.none { it.pluginId == "broken" })
    }

    @Test
    fun pluginReadsSettingsWrittenThroughTheManager() {
        val toggle = ToggleField("verbose", "verbose")
        val plugin = RecordingPlugin { it.registerSettings(SettingsSchema(listOf(toggle))) }
        manager.load(listOf(entry("configurable") { plugin }))

        manager.settingValues("configurable").set(toggle, "true")

        assertTrue(plugin.host.settings[toggle])
        assertEquals("configurable", manager.settings.value.single().pluginId)
    }

    @Test
    fun registeringSettingsTwiceFailsAndDisableDropsTheSchema() {
        val schema = SettingsSchema(emptyList())
        manager.load(
            listOf(
                entry("twice") {
                    RecordingPlugin {
                        it.registerSettings(schema)
                        it.registerSettings(schema)
                    }
                },
            ),
        )

        assertEquals(PluginStatus.Failed, status("twice"))
        assertTrue(manager.settings.value.isEmpty())
    }

    private class FakeTransport(override val id: String) : TransportProvider {
        override val displayName = id
        override val hostForm = TransportHostForm(addressLabel = "address")

        override fun normalizeAddress(raw: String): String? = raw.trim().ifEmpty { null }

        override suspend fun test(address: String): String = "ok"

        override suspend fun connect(request: TransportConnectRequest): TransportConnection =
            error("no connections in unit tests")
    }

    @Test
    fun registeredTransportIsResolvableById() {
        val transport = FakeTransport("test-transport-active")
        manager.load(listOf(entry("transport_owner") { RecordingPlugin { it.registerTransport(transport) } }))

        assertSame(transport, TransportRegistry.forId("test-transport-active"))
        TransportRegistry.unregisterOwner("transport_owner")
    }

    @Test
    fun registerFailureRollsBackTheTransport() {
        manager.load(
            listOf(
                entry("transport_failing") {
                    RecordingPlugin {
                        it.registerTransport(FakeTransport("test-transport-rolled-back"))
                        error("boom")
                    }
                },
            ),
        )

        assertEquals(PluginStatus.Failed, status("transport_failing"))
        assertNull(TransportRegistry.forId("test-transport-rolled-back"))
    }

    @Test
    fun duplicateTransportIdFailsTheSecondPlugin() {
        manager.load(
            listOf(
                entry("transport_first") { RecordingPlugin { it.registerTransport(FakeTransport("test-transport-dup")) } },
                entry("transport_second") { RecordingPlugin { it.registerTransport(FakeTransport("test-transport-dup")) } },
            ),
        )

        assertEquals(PluginStatus.Active, status("transport_first"))
        assertEquals(PluginStatus.Failed, status("transport_second"))
        TransportRegistry.unregisterOwner("transport_first")
    }
}
