package com.jossephus.chuchu.plugin.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** What the host hands each plugin in [ChuchuPlugin.register]. One instance per plugin. */
interface PluginHost {
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

    /**
     * Whether chuchu has a visible screen. Pause polling or streams while false to save
     * battery, and use it to decide whether an event deserves a notification.
     */
    val appVisible: StateFlow<Boolean>

    /** Values of the schemas registered with [registerSettings] / [registerHostSettings]. */
    val settings: PluginSettings

    /**
     * Posts or replaces a notification. Returns false when the user has notifications for
     * chuchu turned off (or hasn't granted the permission), so the plugin can fall back.
     */
    fun notify(notification: PluginNotification): Boolean

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
     * Adds a provider that can take over or decorate a tab's terminal area. Throws
     * [IllegalStateException] on a duplicate [SessionViewProvider.id] within this plugin.
     */
    fun registerSessionView(provider: SessionViewProvider)

    /**
     * Adds a multiplexer option for hosts. Throws [IllegalStateException] if any plugin
     * already registered [MultiplexerProvider.id].
     */
    fun registerMultiplexer(provider: MultiplexerProvider)

    fun registerTransport(provider: TransportProvider)
}

/** Logging routed to logcat under a per-plugin tag, so plugin output is attributable. */
interface PluginLogger {
    fun debug(message: String)

    fun info(message: String)

    fun warn(message: String, error: Throwable? = null)

    fun error(message: String, error: Throwable? = null)
}
