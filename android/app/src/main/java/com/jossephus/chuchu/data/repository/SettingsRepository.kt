package com.jossephus.chuchu.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryLayoutStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("chuchu_settings", Context.MODE_PRIVATE)

    private val _themeName = MutableStateFlow(prefs.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME)
    val themeName: StateFlow<String> = _themeName.asStateFlow()

    private val _accessoryLayoutIds = MutableStateFlow(loadAccessoryLayoutIds())
    val accessoryLayoutIds: StateFlow<List<String>> = _accessoryLayoutIds.asStateFlow()

    fun setTheme(name: String) {
        prefs.edit().putString(KEY_THEME, name).apply()
        _themeName.value = name
    }

    fun setAccessoryLayoutIds(ids: List<String>) {
        val normalized = TerminalAccessoryLayoutStore.normalizeIds(ids)
        prefs.edit().putString(KEY_ACCESSORY_LAYOUT, normalized.joinToString(separator = ",")).apply()
        _accessoryLayoutIds.value = normalized
    }

    private fun loadAccessoryLayoutIds(): List<String> {
        val stored = prefs.getString(KEY_ACCESSORY_LAYOUT, null)
            ?: return TerminalAccessoryLayoutStore.defaultLayoutIds()
        if (stored.isBlank()) {
            return emptyList()
        }
        return TerminalAccessoryLayoutStore.normalizeIds(
            stored.split(',').map(String::trim).filter(String::isNotEmpty),
        )
    }

    companion object {
        private const val KEY_THEME = "theme_name"
        private const val KEY_ACCESSORY_LAYOUT = "terminal_accessory_layout"
        const val DEFAULT_THEME = "Catppuccin Mocha"

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
