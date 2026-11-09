package com.jossephus.chuchu.plugin.api

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** Provided as [LocalPluginTheme] inside session views and plugin terminals. */
class PluginTheme(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val body: TextStyle,
    val label: TextStyle,
    val small: TextStyle,
)

val LocalPluginTheme =
    staticCompositionLocalOf<PluginTheme> {
        error("LocalPluginTheme is only provided inside chuchu-hosted plugin composables")
    }

class PluginNotification(
    /** Posting the same key again replaces the notification. */
    val key: String,
    val title: String,
    val text: String,
    /** Tab to open when tapped. */
    val sessionId: String? = null,
)
