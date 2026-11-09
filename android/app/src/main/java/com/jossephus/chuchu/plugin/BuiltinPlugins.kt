package com.jossephus.chuchu.plugin

import com.jossephus.chuchu.plugin.api.PluginApi
import com.jossephus.chuchu.plugin.builtin.TmuxPlugin
import com.jossephus.chuchu.plugin.builtin.ZmxPlugin

object BuiltinPlugins {
    private val tmux = PluginEntry(builtin("tmux", "tmux")) { TmuxPlugin() }
    private val zmx = PluginEntry(builtin("zmx", "zmx")) { ZmxPlugin() }

    fun entries(): List<PluginEntry> = listOf(tmux, zmx)

    private fun builtin(id: String, name: String) =
        PluginDescriptor(
            id = id,
            name = name,
            version = "builtin",
            apiVersion = PluginApi.VERSION,
            source = PluginSource.Builtin,
        )
}
