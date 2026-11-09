package com.jossephus.chuchu.plugin

import android.app.Application
import android.content.Context
import android.util.Log
import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.MultiplexerProvider
import com.jossephus.chuchu.plugin.api.PluginApi
import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.PluginLogger
import com.jossephus.chuchu.plugin.api.PluginNotification
import com.jossephus.chuchu.plugin.api.PluginSession
import com.jossephus.chuchu.plugin.api.PluginSettings
import com.jossephus.chuchu.plugin.api.PluginTerminal
import com.jossephus.chuchu.plugin.api.TerminalFactory
import com.jossephus.chuchu.plugin.api.SettingsSchema
import com.jossephus.chuchu.plugin.api.SessionViewProvider
import com.jossephus.chuchu.plugin.api.SessionRegistry
import com.jossephus.chuchu.plugin.api.TransportProvider
import com.jossephus.chuchu.service.multiplexer.MultiplexerRegistry
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import com.jossephus.chuchu.service.transport.TransportRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PluginStatus {
    Active,

    /** Threw during registration or from its scope; disabled until the next app start. */
    Failed,

    /** Built against an API version outside [PluginApi.isSupported]; never instantiated. */
    Incompatible,
}

data class PluginRecord(
    val descriptor: PluginDescriptor,
    val status: PluginStatus,
    val error: String? = null,
)

/**
 * A [SessionViewProvider] with the plugin that owns it. [context] is the plugin's package
 * context for external plugins, to provide as `LocalContext` around its composables.
 */
class RegisteredSessionView(
    val pluginId: String,
    val provider: SessionViewProvider,
    val context: Context? = null,
)

/** Settings schemas one plugin declared; either may be null. */
data class PluginSettingsEntry(
    val pluginId: String,
    val pluginName: String,
    val global: SettingsSchema?,
    val host: SettingsSchema?,
)

/** Log output destination, injectable so the manager runs in JVM unit tests. */
fun interface PluginLogSink {
    fun log(priority: Int, tag: String, message: String, error: Throwable?)
}

private val androidLogSink = PluginLogSink { priority, tag, message, error ->
    val text = if (error != null) "$message\n${Log.getStackTraceString(error)}" else message
    Log.println(priority, tag, text)
}

/**
 * Loads plugins and implements [PluginHost] for each of them.
 *
 * Plugins run in-process, so isolation here is about failure, not security: a plugin that
 * throws (in [ChuchuPlugin.register] or from its scope) is disabled and everything it
 * contributed is rolled back, while chuchu and other plugins keep running. There is no
 * hot unload; a disabled plugin stays down until the next app start.
 */
class PluginManager(
    private val parentScope: CoroutineScope,
    private val sessions: SessionRegistry,
    private val prefsFactory: PluginPrefsFactory,
    private val terminalFactory: TerminalFactory,
    /** Context handed to built-in plugins; null only in unit tests. */
    private val appContext: Context? = null,
    /** Null in unit tests, where notify() reports failure. */
    private val notifier: PluginNotifier? = null,
    /** Whether any chuchu activity is started; see [ChuchuApplication]. */
    private val appVisible: StateFlow<Boolean> = MutableStateFlow(true),
    private val logSink: PluginLogSink = androidLogSink,
) {
    private class LoadedPlugin(
        val descriptor: PluginDescriptor,
        val plugin: ChuchuPlugin,
        val scope: CoroutineScope,
        val prefs: PluginPrefs,
        val context: Context?,
    ) {
        // Terminals this plugin created, released with it: each holds a native emulator.
        val terminals = ArrayList<PluginTerminal>()
    }

    // Guards [active], [services] and the state flows: plugins may look up services from any
    // thread, and a scope failure disables its plugin from whichever thread it ran on.
    private val lock = Any()
    private val active = LinkedHashMap<String, LoadedPlugin>()
    private val _plugins = MutableStateFlow<List<PluginRecord>>(emptyList())
    private val _sessionViews = MutableStateFlow<List<RegisteredSessionView>>(emptyList())
    private val _settings = MutableStateFlow<List<PluginSettingsEntry>>(emptyList())

    /** Every plugin the manager has seen, including ones it refused or disabled. */
    val plugins: StateFlow<List<PluginRecord>> = _plugins.asStateFlow()

    /** Session view providers from active plugins, in registration order. */
    val sessionViews: StateFlow<List<RegisteredSessionView>> = _sessionViews.asStateFlow()

    /** Settings schemas of active plugins, in load order. */
    val settings: StateFlow<List<PluginSettingsEntry>> = _settings.asStateFlow()

    /** App-wide setting values of [pluginId], for the settings UI. */
    fun settingValues(pluginId: String): PluginSettingValues =
        PluginSettingValues.global(prefsFor(pluginId))

    /** [hostId]'s setting values for [pluginId], for the host editor. */
    fun hostSettingValues(pluginId: String, hostId: Long): PluginSettingValues =
        PluginSettingValues.hostValues(prefsFor(pluginId), hostId)

    /**
     * Drops every plugin's settings for a deleted host, so a later host reusing the id
     * doesn't inherit them. Covers plugins known this run; values of plugins that aren't
     * installed right now linger harmlessly until then.
     */
    fun removeHostSettings(hostId: Long) {
        val ids = synchronized(lock) { _plugins.value.map { it.descriptor.id } }
        ids.forEach { PluginSettingValues.removeHost(prefsFor(it), hostId) }
    }

    private fun prefsFor(pluginId: String): PluginPrefs =
        synchronized(lock) { active[pluginId]?.prefs } ?: prefsFactory.open(pluginId)

    /** The registry plugins see, so host UI can map tab ids to [PluginSession]s. */
    val sessionRegistry: SessionRegistry
        get() = sessions

    /**
     * Every one of [views] that claims [session], outermost first; empty means chuchu's
     * stock terminal. A provider whose [SessionViewProvider.claims] throws is disabled and
     * skipped.
     */
    fun sessionViewsFor(session: PluginSession, views: List<RegisteredSessionView>): List<RegisteredSessionView> =
        views.filter { view ->
            val isActive = synchronized(lock) { view.pluginId in active }
            isActive &&
                try {
                    view.provider.claims(session)
                } catch (e: Throwable) {
                    disable(view.pluginId, "session view '${view.provider.id}' claims() failed", e)
                    false
                }
        }

    /**
     * Loads [entries] in order. Ids already known to the manager are skipped, which makes
     * repeated calls (e.g. re-running discovery) harmless.
     */
    fun load(entries: List<PluginEntry>) {
        entries.forEach(::loadOne)
    }

    private fun loadOne(entry: PluginEntry) {
        val descriptor = entry.descriptor
        synchronized(lock) {
            if (_plugins.value.any { it.descriptor.id == descriptor.id }) {
                logSink.log(Log.WARN, TAG, "plugin '${descriptor.id}' already known; skipping", null)
                return
            }
        }
        if (!PluginApi.isSupported(descriptor.apiVersion)) {
            val reason =
                "built for plugin API ${descriptor.apiVersion}; this chuchu supports " +
                    "${PluginApi.MIN_SUPPORTED_VERSION}..${PluginApi.VERSION}"
            logSink.log(Log.WARN, TAG, "plugin '${descriptor.id}' $reason", null)
            publish(PluginRecord(descriptor, PluginStatus.Incompatible, reason))
            return
        }

        val plugin =
            try {
                entry.create()
            } catch (e: Throwable) {
                logSink.log(Log.ERROR, TAG, "plugin '${descriptor.id}' failed to instantiate", e)
                publish(PluginRecord(descriptor, PluginStatus.Failed, e.describe()))
                return
            }

        val loaded =
            LoadedPlugin(
                descriptor,
                plugin,
                pluginScope(descriptor.id),
                prefsFactory.open(descriptor.id),
                entry.context ?: appContext,
            )
        synchronized(lock) {
            active[descriptor.id] = loaded
            publish(PluginRecord(descriptor, PluginStatus.Active))
        }
        try {
            plugin.register(HostImpl(loaded))
            logSink.log(Log.INFO, TAG, "plugin '${descriptor.id}' ${descriptor.version} loaded", null)
        } catch (e: Throwable) {
            disable(descriptor.id, "register failed", e)
        }
    }

    private fun pluginScope(id: String): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, error -> disable(id, "uncaught error in scope", error) }
        val job = SupervisorJob(parentScope.coroutineContext[Job])
        return CoroutineScope(parentScope.coroutineContext + job + handler + CoroutineName("plugin:$id"))
    }

    private fun disable(id: String, reason: String, error: Throwable) {
        val loaded =
            synchronized(lock) {
                val loaded = active.remove(id) ?: return
                _sessionViews.value = _sessionViews.value.filterNot { it.pluginId == id }
                _settings.value = _settings.value.filterNot { it.pluginId == id }
                MultiplexerRegistry.unregisterOwner(id)
                TransportRegistry.unregisterOwner(id)
                publish(PluginRecord(loaded.descriptor, PluginStatus.Failed, "$reason: ${error.describe()}"))
                loaded
            }
        logSink.log(Log.ERROR, TAG, "plugin '$id' disabled ($reason)", error)
        loaded.scope.cancel()
        synchronized(lock) { loaded.terminals.toList().also { loaded.terminals.clear() } }
            .forEach { runCatching { it.close() } }
        try {
            loaded.plugin.onUnload()
        } catch (e: Throwable) {
            logSink.log(Log.WARN, TAG, "plugin '$id' onUnload threw", e)
        }
    }

    /** Inserts or replaces the record for the descriptor's id, preserving load order. */
    private fun publish(record: PluginRecord) {
        synchronized(lock) {
            val current = _plugins.value
            val index = current.indexOfFirst { it.descriptor.id == record.descriptor.id }
            _plugins.value =
                if (index < 0) current + record
                else current.toMutableList().also { it[index] = record }
        }
    }

    private inner class HostImpl(private val loaded: LoadedPlugin) : PluginHost {
        private val pluginId: String = loaded.descriptor.id
        override val scope: CoroutineScope = loaded.scope
        override val log: PluginLogger = LoggerImpl("$TAG:$pluginId")
        override val sessions: SessionRegistry = this@PluginManager.sessions
        override val settings: PluginSettings = PluginSettingValues.global(loaded.prefs)
        override val appVisible: StateFlow<Boolean> = this@PluginManager.appVisible

        override fun notify(notification: PluginNotification): Boolean =
            notifier?.post(pluginId, loaded.descriptor.name, notification) ?: false

        override val terminals: TerminalFactory =
            TerminalFactory { options ->
                synchronized(lock) { check(active[pluginId] === loaded) { "plugin '$pluginId' is not active" } }
                terminalFactory.create(options).also { terminal -> synchronized(lock) { loaded.terminals += terminal } }
            }

        override fun registerSettings(schema: SettingsSchema) =
            updateSettingsEntry { entry ->
                check(entry.global == null) { "plugin '$pluginId' already registered settings" }
                entry.copy(global = schema)
            }

        override fun registerHostSettings(schema: SettingsSchema) =
            updateSettingsEntry { entry ->
                check(entry.host == null) { "plugin '$pluginId' already registered host settings" }
                entry.copy(host = schema)
            }

        private fun updateSettingsEntry(update: (PluginSettingsEntry) -> PluginSettingsEntry) {
            synchronized(lock) {
                check(active[pluginId] === loaded) { "plugin '$pluginId' is not active" }
                val current = _settings.value
                val existing =
                    current.firstOrNull { it.pluginId == pluginId }
                        ?: PluginSettingsEntry(pluginId, loaded.descriptor.name, global = null, host = null)
                val next = update(existing)
                _settings.value =
                    if (existing in current) current.map { if (it.pluginId == pluginId) next else it }
                    else current + next
            }
        }

        override fun registerSessionView(provider: SessionViewProvider) {
            synchronized(lock) {
                check(active[pluginId] === loaded) { "plugin '$pluginId' is not active" }
                check(_sessionViews.value.none { it.pluginId == pluginId && it.provider.id == provider.id }) {
                    "plugin '$pluginId' already registered session view '${provider.id}'"
                }
                // Built-ins share chuchu's context, so only external plugins need an override.
                val pluginContext = loaded.context.takeIf { loaded.descriptor.source == PluginSource.External }
                _sessionViews.value = _sessionViews.value + RegisteredSessionView(pluginId, provider, pluginContext)
            }
        }

        override fun registerMultiplexer(provider: MultiplexerProvider) {
            synchronized(lock) {
                check(active[pluginId] === loaded) { "plugin '$pluginId' is not active" }
                MultiplexerRegistry.register(pluginId, provider)
            }
        }

        override fun registerTransport(provider: TransportProvider) {
            synchronized(lock) {
                check(active[pluginId] === loaded) { "plugin '$pluginId' is not active" }
                TransportRegistry.register(pluginId, provider)
            }
        }
    }

    private inner class LoggerImpl(private val tag: String) : PluginLogger {
        override fun debug(message: String) = logSink.log(Log.DEBUG, tag, message, null)

        override fun info(message: String) = logSink.log(Log.INFO, tag, message, null)

        override fun warn(message: String, error: Throwable?) = logSink.log(Log.WARN, tag, message, error)

        override fun error(message: String, error: Throwable?) = logSink.log(Log.ERROR, tag, message, error)
    }

    companion object {
        private const val TAG = "ChuchuPlugin"

        @Volatile
        private var instance: PluginManager? = null

        fun getInstance(application: Application): PluginManager =
            instance ?: synchronized(this) {
                instance ?: create(application).also { instance = it }
            }

        private fun create(application: Application): PluginManager {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val tabs = TerminalSessionRepository.getInstance(application).tabs
            return PluginManager(
                scope,
                PluginSessionRegistry(tabs, scope),
                SharedPreferencesPluginPrefs.factory(application),
                GhosttyTerminalFactory(),
                application,
                PluginNotifier(application),
                AppVisibility.visible,
            )
        }
    }
}

private fun Throwable.describe(): String = "${this::class.java.simpleName}: ${message.orEmpty()}"
