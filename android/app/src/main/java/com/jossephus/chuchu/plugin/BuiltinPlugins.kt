package com.jossephus.chuchu.plugin

import com.jossephus.chuchu.plugin.builtin.TmuxPlugin
import com.jossephus.chuchu.plugin.builtin.ZmxPlugin

object BuiltinPlugins {
    fun entries(): List<PluginEntry> =
        listOf(
            PluginEntry(PluginDescriptor("tmux", "tmux")) { TmuxPlugin() },
            PluginEntry(PluginDescriptor("zmx", "zmx")) { ZmxPlugin() },
        )
}
