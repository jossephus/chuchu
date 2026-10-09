package com.jossephus.chuchu.plugin

import android.content.Context
import com.jossephus.chuchu.plugin.api.ChuchuPlugin

/** Where a plugin's code comes from. Both kinds go through the same [PluginManager] path. */
enum class PluginSource {
    /** Compiled into chuchu; see [BuiltinPlugins]. */
    Builtin,

    /** A separately installed plugin APK. */
    External,
}

/**
 * Identity of a plugin, known before any of its code runs. External plugins declare it in
 * manifest meta-data, so the host can show and version-check a plugin it refuses to load.
 */
data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val source: PluginSource,
    /** Installed package of an external plugin; null for built-ins. */
    val packageName: String? = null,
)

/** A loadable plugin: its identity plus how to instantiate its [ChuchuPlugin] entry class. */
class PluginEntry(
    val descriptor: PluginDescriptor,
    /** The plugin's package context; null means chuchu's own (built-ins). */
    val context: Context? = null,
    val create: () -> ChuchuPlugin,
)
