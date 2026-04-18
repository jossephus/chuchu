package com.jossephus.chuchu.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

@Composable
fun ChuTheme(
    themeName: String? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val palette = themeName
        ?.let { GhosttyThemeRegistry.getTheme(context, it) }
        ?.toChuColorPalette()
        ?: ChuDarkColors

    CompositionLocalProvider(
        LocalChuColors provides palette,
        LocalChuTypography provides ChuDefaultTypography,
        content = content,
    )
}