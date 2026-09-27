package com.jossephus.chuchu.plugin.api

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * Chuchu's current colors and text styles, so plugin UI matches the user's theme and font.
 * Read `LocalPluginTheme.current` inside plugin composables (session views and plugin
 * terminals); chuchu provides it there and updates it when the theme changes.
 */
class PluginTheme(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    /** Content drawn on [accent]. */
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    /** In the user's chosen monospace font. */
    val body: TextStyle,
    val label: TextStyle,
    val small: TextStyle,
)

val LocalPluginTheme =
    staticCompositionLocalOf<PluginTheme> {
        error("LocalPluginTheme is only provided inside chuchu-hosted plugin composables")
    }

/**
 * A notification posted through [PluginHost.notify], under chuchu's own "plugins" channel
 * and icon so it looks like the rest of chuchu's notifications.
 */
class PluginNotification(
    /** Unique within the plugin; posting the same key again replaces the notification. */
    val key: String,
    val title: String,
    val text: String,
    /** Session to bring to the front when the notification is tapped; null just opens chuchu. */
    val sessionId: String? = null,
)
