package com.jossephus.chuchu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * Theme mode: manual selection vs following the device's dark/light mode.
 */
enum class ThemeMode(val label: String) {
    Manual("manual"),
    Auto("follow system"),
}

/**
 * Resolves the active terminal theme name based on the current mode and
 * system dark/light state.
 *
 * - [Manual] → [manualThemeName]
 * - [Auto]   → [autoDarkThemeName] when the device is in dark mode,
 *              [autoLightThemeName] otherwise
 */
@Composable
fun resolveActiveThemeName(
    themeMode: ThemeMode,
    manualThemeName: String,
    autoDarkThemeName: String,
    autoLightThemeName: String,
): String = when (themeMode) {
    ThemeMode.Manual -> manualThemeName
    ThemeMode.Auto -> {
        if (isSystemInDarkTheme()) autoDarkThemeName else autoLightThemeName
    }
}
