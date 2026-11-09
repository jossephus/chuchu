package com.jossephus.chuchu.plugin

import android.content.Context
import com.jossephus.chuchu.plugin.api.ChuchuPlugin

enum class PluginSource {
    Builtin,

    External,
}

data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val source: PluginSource,
    val packageName: String? = null,
)

class PluginEntry(
    val descriptor: PluginDescriptor,
    val context: Context? = null,
    val create: () -> ChuchuPlugin,
)
