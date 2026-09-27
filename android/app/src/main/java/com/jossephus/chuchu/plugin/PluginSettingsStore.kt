package com.jossephus.chuchu.plugin

import android.content.Context
import android.content.SharedPreferences
import com.jossephus.chuchu.plugin.api.ChoiceField
import com.jossephus.chuchu.plugin.api.PluginSettings
import com.jossephus.chuchu.plugin.api.PluginStorage
import com.jossephus.chuchu.plugin.api.SettingField
import com.jossephus.chuchu.plugin.api.TextField
import com.jossephus.chuchu.plugin.api.ToggleField
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * One plugin's persisted strings. A separate store per plugin keeps plugins from reading
 * each other's values by accident and lets a plugin's data be wiped in one go.
 *
 * Key layout: `s.<key>` app-wide settings, `h.<hostId>.<key>` host settings, `d.<key>`
 * [PluginStorage] data.
 */
interface PluginPrefs {
    fun get(key: String): String?

    fun put(key: String, value: String?)

    fun keys(): Set<String>

    val changes: Flow<String>
}

fun interface PluginPrefsFactory {
    fun open(pluginId: String): PluginPrefs
}

class SharedPreferencesPluginPrefs(private val prefs: SharedPreferences) : PluginPrefs {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    override fun keys(): Set<String> = prefs.all.keys

    override val changes: Flow<String> =
        callbackFlow {
            // SharedPreferences holds listeners weakly; this local reference keeps it alive
            // for as long as the flow is collected.
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != null) trySend(key) }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

    companion object {
        fun factory(context: Context) = PluginPrefsFactory { pluginId ->
            SharedPreferencesPluginPrefs(
                context.applicationContext.getSharedPreferences("plugin_$pluginId", Context.MODE_PRIVATE),
            )
        }
    }
}

/** For tests and previews. */
class InMemoryPluginPrefs : PluginPrefs {
    private val values = LinkedHashMap<String, String>()
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun get(key: String): String? = synchronized(values) { values[key] }

    override fun put(key: String, value: String?) {
        synchronized(values) { if (value == null) values.remove(key) else values[key] = value }
        _changes.tryEmit(key)
    }

    override fun keys(): Set<String> = synchronized(values) { values.keys.toSet() }

    override val changes: Flow<String> = _changes
}

/**
 * Settings values under one key prefix: app-wide (`s.`) or one host's (`h.<id>.`). Also the
 * write side used by chuchu's settings UI, which edits fields as raw strings.
 */
class PluginSettingValues internal constructor(
    private val prefs: PluginPrefs,
    private val prefix: String,
) : PluginSettings {
    override fun get(field: ToggleField): Boolean = prefs.get(prefix + field.key)?.toBooleanStrictOrNull() ?: field.default

    override fun get(field: TextField): String = prefs.get(prefix + field.key) ?: field.default

    override fun get(field: ChoiceField): String =
        prefs.get(prefix + field.key)?.takeIf { value -> field.options.any { it.value == value } } ?: field.default

    override fun forHost(hostId: Long): PluginSettings = hostValues(prefs, hostId)

    override val changes: Flow<String> =
        prefs.changes.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }

    /** The field's current value as the UI edits it ("true"/"false" for toggles). */
    fun raw(field: SettingField): String =
        when (field) {
            is ToggleField -> get(field).toString()
            is TextField -> get(field)
            is ChoiceField -> get(field)
        }

    fun set(field: SettingField, raw: String) = prefs.put(prefix + field.key, raw)

    internal companion object {
        fun global(prefs: PluginPrefs) = PluginSettingValues(prefs, "s.")

        fun hostValues(prefs: PluginPrefs, hostId: Long) = PluginSettingValues(prefs, "h.$hostId.")

        fun removeHost(prefs: PluginPrefs, hostId: Long) {
            val prefix = "h.$hostId."
            prefs.keys().filter { it.startsWith(prefix) }.forEach { prefs.put(it, null) }
        }
    }
}

internal class PluginStorageImpl(private val prefs: PluginPrefs) : PluginStorage {
    override fun get(key: String): String? = prefs.get("d.$key")

    override fun put(key: String, value: String?) = prefs.put("d.$key", value)
}
