package com.jossephus.chuchu.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryLayoutStore
import com.jossephus.chuchu.ui.terminal.TerminalCustomActionStore
import com.jossephus.chuchu.ui.terminal.TerminalCustomKeyGroup
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

    private val _terminalCustomKeyGroups = MutableStateFlow(loadTerminalCustomKeyGroups())
    val terminalCustomKeyGroups: StateFlow<List<TerminalCustomKeyGroup>> = _terminalCustomKeyGroups.asStateFlow()

    private val _appLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_APP_LOCK_ENABLED, false))
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _requireAuthOnConnect = MutableStateFlow(prefs.getBoolean(KEY_REQUIRE_AUTH_ON_CONNECT, false))
    val requireAuthOnConnect: StateFlow<Boolean> = _requireAuthOnConnect.asStateFlow()

    fun setTheme(name: String) {
        prefs.edit().putString(KEY_THEME, name).apply()
        _themeName.value = name
    }

    fun setAccessoryLayoutIds(ids: List<String>) {
        val normalized = TerminalAccessoryLayoutStore.normalizeIds(ids)
        prefs.edit().putString(KEY_ACCESSORY_LAYOUT, normalized.joinToString(separator = ",")).apply()
        _accessoryLayoutIds.value = normalized
    }

    fun setTerminalCustomKeyGroups(groups: List<TerminalCustomKeyGroup>) {
        val normalized = TerminalCustomActionStore.normalize(groups)
        val serialized = TerminalCustomActionStore.serialize(normalized)
        prefs.edit().putString(KEY_TERMINAL_CUSTOM_ACTIONS, serialized).apply()
        _terminalCustomKeyGroups.value = normalized
    }

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
        _appLockEnabled.value = enabled
    }

    fun setRequireAuthOnConnect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE_AUTH_ON_CONNECT, enabled).apply()
        _requireAuthOnConnect.value = enabled
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

    private fun loadTerminalCustomKeyGroups(): List<TerminalCustomKeyGroup> {
        val stored = prefs.getString(KEY_TERMINAL_CUSTOM_ACTIONS, null)
        return TerminalCustomActionStore.parse(stored)
    }

    companion object {
        private const val KEY_THEME = "theme_name"
        private const val KEY_ACCESSORY_LAYOUT = "terminal_accessory_layout"
        private const val KEY_TERMINAL_CUSTOM_ACTIONS = "terminal_custom_actions"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_REQUIRE_AUTH_ON_CONNECT = "require_auth_on_connect"
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
