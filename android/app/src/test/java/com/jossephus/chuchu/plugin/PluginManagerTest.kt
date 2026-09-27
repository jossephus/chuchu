package com.jossephus.chuchu.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jossephus.chuchu.plugin.api.ByteStreamEvent
import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.ExecChannel
import com.jossephus.chuchu.plugin.api.HostInfo
import com.jossephus.chuchu.plugin.api.PluginApi
import com.jossephus.chuchu.plugin.api.PluginCommand
import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.PluginSession
import com.jossephus.chuchu.plugin.api.PtySize
import com.jossephus.chuchu.plugin.api.SessionEvent
import com.jossephus.chuchu.plugin.api.SessionRegistry
import com.jossephus.chuchu.plugin.api.SessionStatus
import com.jossephus.chuchu.plugin.api.SessionTransport
import com.jossephus.chuchu.plugin.api.SessionViewProvider
import com.jossephus.chuchu.plugin.api.SettingsSchema
import com.jossephus.chuchu.plugin.api.ToggleField
import com.jossephus.chuchu.plugin.api.provideService
import com.jossephus.chuchu.plugin.api.service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManagerTest {
    private interface Greeter {
        fun greet(): String
    }

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
    fun registersPluginAndExposesHostIdentity() {
        val plugin = RecordingPlugin()
        manager.load(listOf(entry("alpha") { plugin }))

        assertEquals(PluginStatus.Active, status("alpha"))
        assertEquals("alpha", plugin.host.pluginId)
        assertEquals(PluginApi.VERSION, plugin.host.apiVersion)
        assertSame(registry, plugin.host.sessions)
    }

    @Test
    fun servicesAreSharedAcrossPlugins() {
        val greeter = object : Greeter {
            override fun greet() = "hi"
        }
        val consumer = RecordingPlugin()
        manager.load(
            listOf(
                entry("provider") { RecordingPlugin { it.provideService<Greeter>(greeter) } },
                entry("consumer") { consumer },
            ),
        )

        assertSame(greeter, consumer.host.service<Greeter>())
    }

    @Test
    fun registerFailureDisablesPluginAndRollsBackItsServices() {
        val consumer = RecordingPlugin()
        val failing = RecordingPlugin {
            it.provideService<Greeter>(object : Greeter {
                override fun greet() = "half-registered"
            })
            error("boom")
        }
        manager.load(listOf(entry("failing") { failing }, entry("consumer") { consumer }))

        assertEquals(PluginStatus.Failed, status("failing"))
        assertTrue(manager.plugins.value.first().error!!.contains("boom"))
        assertEquals(1, failing.unloadCount)
        assertFalse(failing.host.scope.isActive)
        assertNull(consumer.host.service<Greeter>())
        assertEquals(PluginStatus.Active, status("consumer"))
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
    fun disabledPluginCannotProvideServices() {
        val crashing = RecordingPlugin()
        manager.load(listOf(entry("crashing") { crashing }))
        crashing.host.scope.launch { error("background crash") }

        val result = runCatching {
            crashing.host.provideService<Greeter>(object : Greeter {
                override fun greet() = "late"
            })
        }

        assertTrue(result.isFailure)
        assertNull(crashing.host.service<Greeter>())
    }

    @Test
    fun duplicateServiceFailsTheSecondProvider() {
        fun provider() = RecordingPlugin {
            it.provideService<Greeter>(object : Greeter {
                override fun greet() = "hi"
            })
        }
        manager.load(listOf(entry("first") { provider() }, entry("second") { provider() }))

        assertEquals(PluginStatus.Active, status("first"))
        assertEquals(PluginStatus.Failed, status("second"))
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

    @Test
    fun commandsAreRegisteredAndRunWithNoActiveSession() {
        var ranWith: Any? = "not run"
        manager.load(
            listOf(entry("cmds") { RecordingPlugin { it.registerCommand(PluginCommand("hello", "hello", 'h') { ctx -> ranWith = ctx.session }) } }),
        )

        val registered = manager.commands.value.single()
        assertEquals("cmds.hello", registered.qualifiedId)
        manager.runCommand(registered, activeTabId = null)
        assertNull(ranWith)
    }

    @Test
    fun throwingCommandDisablesPluginAndRemovesItsCommands() {
        manager.load(
            listOf(entry("buggy") { RecordingPlugin { it.registerCommand(PluginCommand("boom", "boom") { error("bad") }) } }),
        )

        manager.runCommand(manager.commands.value.single(), activeTabId = null)

        assertEquals(PluginStatus.Failed, status("buggy"))
        assertTrue(manager.commands.value.isEmpty())
    }

    @Test
    fun duplicateCommandIdFailsRegistration() {
        manager.load(
            listOf(
                entry("dupe") {
                    RecordingPlugin {
                        it.registerCommand(PluginCommand("same", "a") {})
                        it.registerCommand(PluginCommand("same", "b") {})
                    }
                },
            ),
        )

        assertEquals(PluginStatus.Failed, status("dupe"))
        assertTrue(manager.commands.value.isEmpty())
    }

    private class FakeSession(multiplexer: String?) : PluginSession {
        override val id = "tab-1"
        override val host =
            HostInfo(1, "box", "box.local", 22, "me", SessionTransport.Ssh, multiplexer)
        override val status = MutableStateFlow(SessionStatus.Connected)
        override val title = MutableStateFlow<String?>(null)
        override val pwd = MutableStateFlow<String?>(null)
        override fun output(capacity: Int): Flow<ByteStreamEvent> = emptyFlow()
        override fun input(capacity: Int): Flow<ByteStreamEvent> = emptyFlow()
        override fun write(bytes: ByteArray) = Unit
        override fun writeText(text: String) = Unit
        override suspend fun exec(command: String, pty: PtySize?): ExecChannel? = null
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
}
