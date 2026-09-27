package com.jossephus.chuchu.plugin.api

import kotlinx.coroutines.flow.Flow

/**
 * One user-editable setting. Chuchu renders these for you (fizzy-style declarative
 * settings), so plugins never hand-roll settings screens and never need database
 * migrations.
 *
 * [key] must be stable across releases: it's where the value is stored.
 */
sealed interface SettingField {
    val key: String
    val title: String
    val description: String?
}

class ToggleField(
    override val key: String,
    override val title: String,
    override val description: String? = null,
    val default: Boolean = false,
) : SettingField

class TextField(
    override val key: String,
    override val title: String,
    override val description: String? = null,
    val default: String = "",
    val placeholder: String = "",
) : SettingField

class ChoiceField(
    override val key: String,
    override val title: String,
    override val description: String? = null,
    val options: List<Option>,
    /** Must be one of [options]' values. */
    val default: String = options.first().value,
) : SettingField {
    init {
        require(options.isNotEmpty()) { "ChoiceField '$key' needs at least one option" }
        require(options.any { it.value == default }) { "ChoiceField '$key' default is not an option" }
    }

    class Option(val value: String, val label: String)
}

class SettingsSchema(val fields: List<SettingField>) {
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

    operator fun get(field: TextField): String

    /** Falls back to the default if the stored value is no longer one of the options. */
    operator fun get(field: ChoiceField): String

    /** Values of the host-settings schema for one saved host profile ([HostInfo.hostId]). */
    fun forHost(hostId: Long): PluginSettings

    /** Emits the key of each setting that changes, from any source. */
    val changes: Flow<String>
}

/**
 * Private key-value storage for plugin state that isn't a user setting (caches, last-seen
 * markers, ...). Separate from [PluginSettings] so the two can't collide. Not backed up.
 */
interface PluginStorage {
    fun get(key: String): String?

    /** Stores [value], or removes the key when [value] is null. */
    fun put(key: String, value: String?)
}
