package com.jossephus.chuchu.plugin

import com.jossephus.chuchu.plugin.api.ChuchuPlugin

data class PluginDescriptor(
    val id: String,
    val name: String,
)

class PluginEntry(
    val descriptor: PluginDescriptor,
    val create: () -> ChuchuPlugin,
)
