package com.jossephus.chuchu.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryLayoutStore
import com.jossephus.chuchu.ui.theme.ChuFontOption
import com.jossephus.chuchu.ui.theme.ThemeMode
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

    private val _fontName = MutableStateFlow(
        ChuFontOption.fromId(prefs.getString(KEY_FONT, DEFAULT_FONT)).id,
    )
    val fontName: StateFlow<String> = _fontName.asStateFlow()

    private val _accessoryLayoutIds = MutableStateFlow(loadAccessoryLayoutIds())
    val accessoryLayoutIds: StateFlow<List<String>> = _accessoryLayoutIds.asStateFlow()

    private val _terminalCustomKeyGroups = MutableStateFlow(loadTerminalCustomKeyGroups())
    val terminalCustomKeyGroups: StateFlow<List<TerminalCustomKeyGroup>> = _terminalCustomKeyGroups.asStateFlow()

    private val _accessoryBarSingleRow = MutableStateFlow(prefs.getBoolean(KEY_ACCESSORY_BAR_SINGLE_ROW, false))
    val accessoryBarSingleRow: StateFlow<Boolean> = _accessoryBarSingleRow.asStateFlow()

    private val _appLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_APP_LOCK_ENABLED, false))
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _requireAuthOnConnect = MutableStateFlow(prefs.getBoolean(KEY_REQUIRE_AUTH_ON_CONNECT, false))
    val requireAuthOnConnect: StateFlow<Boolean> = _requireAuthOnConnect.asStateFlow()

    private val _themeMode = MutableStateFlow(parseThemeMode(prefs.getString(KEY_THEME_MODE, ThemeMode.Dark.name)))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _lightThemeName = MutableStateFlow(
        prefs.getString(KEY_LIGHT_THEME, DEFAULT_LIGHT_THEME) ?: DEFAULT_LIGHT_THEME,
    )
    val lightThemeName: StateFlow<String> = _lightThemeName.asStateFlow()

    fun setTheme(name: String) {
        prefs.edit().putString(KEY_THEME, name).apply()
        _themeName.value = name
    }

    fun setFont(name: String) {
        val normalized = ChuFontOption.fromId(name).id
        prefs.edit().putString(KEY_FONT, normalized).apply()
        _fontName.value = normalized
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

    fun setAccessoryBarSingleRow(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ACCESSORY_BAR_SINGLE_ROW, enabled).apply()
        _accessoryBarSingleRow.value = enabled
    }

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
        _appLockEnabled.value = enabled
    }

    fun setRequireAuthOnConnect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE_AUTH_ON_CONNECT, enabled).apply()
        _requireAuthOnConnect.value = enabled
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun setLightTheme(name: String) {
        prefs.edit().putString(KEY_LIGHT_THEME, name).apply()
        _lightThemeName.value = name
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
        private const val KEY_FONT = "font_name"
        private const val KEY_ACCESSORY_LAYOUT = "terminal_accessory_layout"
        private const val KEY_TERMINAL_CUSTOM_ACTIONS = "terminal_custom_actions"
        private const val KEY_ACCESSORY_BAR_SINGLE_ROW = "terminal_accessory_bar_single_row"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_REQUIRE_AUTH_ON_CONNECT = "require_auth_on_connect"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LIGHT_THEME = "light_theme_name"
        const val DEFAULT_THEME = "Catppuccin Mocha"
        const val DEFAULT_LIGHT_THEME = "Catppuccin Latte"
        const val DEFAULT_FONT = "jetbrains_mono"

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }

        private fun parseThemeMode(value: String?): ThemeMode =
            ThemeMode.entries.find { it.name == value } ?: ThemeMode.Dark
    }
}
