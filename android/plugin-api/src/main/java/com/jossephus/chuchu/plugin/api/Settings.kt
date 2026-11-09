package com.jossephus.chuchu.plugin.api

import kotlinx.coroutines.flow.Flow

/** [key] is where the value is stored; keep it stable across releases. */
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

interface PluginSettings {
    operator fun get(field: ToggleField): Boolean

    fun forHost(hostId: Long): PluginSettings

    /** Keys of changed settings. */
    val changes: Flow<String>
}
