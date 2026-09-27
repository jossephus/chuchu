package com.jossephus.chuchu.plugin

import com.jossephus.chuchu.plugin.api.PluginApi
import com.jossephus.chuchu.plugin.builtin.SessionProbePlugin
import com.jossephus.chuchu.plugin.builtin.TmuxPlugin
import com.jossephus.chuchu.plugin.builtin.ZmxPlugin

/**
 * Plugins compiled into chuchu. They are written against the same `plugin-api` a third-party
 * APK uses and load through the same [PluginManager] path; if a built-in can't be expressed
 * that way, the API is missing something.
 */
object BuiltinPlugins {
    private val tmux = PluginEntry(builtin("tmux", "tmux")) { TmuxPlugin() }
    private val zmx = PluginEntry(builtin("zmx", "zmx")) { ZmxPlugin() }
    private val sessionProbe =
        PluginEntry(builtin("session_probe", "Session probe")) { SessionProbePlugin() }

    fun entries(debuggable: Boolean): List<PluginEntry> =
        buildList {
            add(tmux)
            add(zmx)
            if (debuggable) add(sessionProbe)
        }

    private fun builtin(id: String, name: String) =
        PluginDescriptor(
            id = id,
            name = name,
            version = "builtin",
            apiVersion = PluginApi.VERSION,
            source = PluginSource.Builtin,
        )
}
