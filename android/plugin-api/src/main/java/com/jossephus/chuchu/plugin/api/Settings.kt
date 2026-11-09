package com.jossephus.chuchu.plugin.api

import kotlinx.coroutines.flow.Flow

/**
 * One user-editable setting. Chuchu renders these for you (fizzy-style declarative
 * settings), so plugins never hand-roll settings screens and never need database
 * migrations.
 *
 * [key] must be stable across releases: it's where the value is stored.
 */
class ToggleField(
    val key: String,
    val title: String,
    val description: String? = null,
    val default: Boolean = false,
)

class SettingsSchema(val fields: List<ToggleField>) {
    init {
        val duplicate = fields.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }?.key
        require(duplicate == null) { "duplicate setting key '$duplicate'" }
    }
}

/**
 * Current values of this plugin's settings. Reading a field that was never set returns
 * its default. Values written by the settings UI are visible immediately.
 */
interface PluginSettings {
    operator fun get(field: ToggleField): Boolean

    /** Values of the host-settings schema for one saved host profile ([HostInfo.hostId]). */
    fun forHost(hostId: Long): PluginSettings

    /** Emits the key of each setting that changes, from any source. */
    val changes: Flow<String>
}
