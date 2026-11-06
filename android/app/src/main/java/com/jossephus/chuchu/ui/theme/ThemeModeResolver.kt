package com.jossephus.chuchu.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.jossephus.chuchu.R

/**
 * Theme mode: always dark, always light, or follow the system setting.
 */
enum class ThemeMode(@StringRes val labelRes: Int) {
    Dark(R.string.settings_theme_dark),
    Light(R.string.settings_theme_light),
    System(R.string.settings_theme_system),
}

/**
 * Resolves the active terminal theme name based on the current mode and
 * system dark/light state.
 *
 * - [Dark]   → [darkThemeName]
 * - [Light]  → [lightThemeName]
 * - [System] → [darkThemeName] when the device is in dark mode,
 *              [lightThemeName] otherwise
 */
@Composable
fun resolveActiveThemeName(
    themeMode: ThemeMode,
    darkThemeName: String,
    lightThemeName: String,
): String = when (themeMode) {
    ThemeMode.Dark -> darkThemeName
    ThemeMode.Light -> lightThemeName
    ThemeMode.System -> if (isSystemInDarkTheme()) darkThemeName else lightThemeName
}
