package com.jossephus.chuchu.plugin.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface PluginHost {
    /** Main-thread scope; an uncaught exception in it disables the plugin. */
    val scope: CoroutineScope

    val log: PluginLogger

    val sessions: SessionRegistry

    val terminals: TerminalFactory

    val appVisible: StateFlow<Boolean>

    val settings: PluginSettings

    /** Returns false when chuchu can't post notifications. */
    fun notify(notification: PluginNotification): Boolean

    /** Shown under Settings › plugins. At most once. */
    fun registerSettings(schema: SettingsSchema)

    /** Shown in the host editor; read with `settings.forHost(hostId)`. At most once. */
    fun registerHostSettings(schema: SettingsSchema)

    fun registerSessionView(provider: SessionViewProvider)

    /** Throws if another plugin already registered the same id. */
    fun registerMultiplexer(provider: MultiplexerProvider)

    /** Throws if another plugin already registered the same id. */
    fun registerTransport(provider: TransportProvider)
}

interface PluginLogger {
    fun debug(message: String)

    fun info(message: String)

    fun warn(message: String, error: Throwable? = null)

    fun error(message: String, error: Throwable? = null)
}
