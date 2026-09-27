package com.jossephus.chuchu.plugin.api

import android.content.Context
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope

/** What the host hands each plugin in [ChuchuPlugin.register]. One instance per plugin. */
interface PluginHost {
    /** [PluginApi.VERSION] of the running host, which may be newer than the plugin's. */
    val apiVersion: Int

    /** This plugin's id, as declared in its manifest (or built-in entry). */
    val pluginId: String

    /**
     * The plugin's own package context, so `R.string`/`R.drawable` of a plugin APK resolve
     * against that APK. Chuchu also provides it as `LocalContext` inside the plugin's
     * composables. For built-in plugins this is chuchu's application context.
     */
    val context: Context

    /**
     * Lives as long as the plugin. An uncaught exception in it disables this plugin
     * only; other plugins and chuchu keep running.
     *
     * Dispatches on the main thread, like UI code. Parse byte streams or do I/O with
     * `launch(Dispatchers.Default)` / `Dispatchers.IO` so the terminal UI stays smooth.
     */
    val scope: CoroutineScope

    val log: PluginLogger

    /** Open terminal sessions and their events, shared by all plugins. */
    val sessions: SessionRegistry

    /** Ghostty terminals for plugin UIs (e.g. multiplexer panes). */
    val terminals: TerminalFactory

    /** Values of the schemas registered with [registerSettings] / [registerHostSettings]. */
    val settings: PluginSettings

    /** Private key-value storage for non-setting state. */
    val storage: PluginStorage

    /**
     * Declares app-wide settings, shown under Settings › plugins. Call at most once;
     * throws [IllegalStateException] on a second call.
     */
    fun registerSettings(schema: SettingsSchema)

    /**
     * Declares per-host settings, shown in the host editor. Read them with
     * `settings.forHost(hostId)`. Call at most once; throws [IllegalStateException] on a
     * second call.
     */
    fun registerHostSettings(schema: SettingsSchema)

    /**
     * Adds a chuchu-key command. Throws [IllegalStateException] if this plugin already
     * registered a command with the same [PluginCommand.id].
     */
    fun registerCommand(command: PluginCommand)

    /**
     * Adds a provider that can take over or decorate a tab's terminal area. Throws
     * [IllegalStateException] on a duplicate [SessionViewProvider.id] within this plugin.
     */
    fun registerSessionView(provider: SessionViewProvider)

    /**
     * Adds a multiplexer option for hosts. Throws [IllegalStateException] if any plugin
     * already registered [MultiplexerProvider.id].
     */
    fun registerMultiplexer(provider: MultiplexerProvider)

    /**
     * Publishes [impl] under [type] so other plugins can use it without importing this
     * plugin, the way fizzy plugins meet through `Host` services. The service type
     * itself must come from a small shared API artifact both plugins depend on.
     *
     * Throws [IllegalStateException] if another plugin already provides [type].
     */
    fun <T : Any> provideService(type: KClass<T>, impl: T)

    /** A service another plugin (or this one) provided, or null if nobody has. */
    fun <T : Any> service(type: KClass<T>): T?
}

inline fun <reified T : Any> PluginHost.provideService(impl: T) = provideService(T::class, impl)

inline fun <reified T : Any> PluginHost.service(): T? = service(T::class)

/** Logging routed to logcat under a per-plugin tag, so plugin output is attributable. */
interface PluginLogger {
    fun debug(message: String)

    fun info(message: String)

    fun warn(message: String, error: Throwable? = null)

    fun error(message: String, error: Throwable? = null)
}
