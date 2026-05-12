package com.jossephus.chuchu.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun ChuTheme(
    themeName: String? = null,
    fontName: String? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val palette = themeName
        ?.let { GhosttyThemeRegistry.getTheme(context, it) }
        ?.toChuColorPalette()
        ?: ChuDarkColors

    val fontOption = remember(fontName) { ChuFontOption.fromId(fontName) }
    val typography = remember(fontOption) { chuTypographyFor(fontOption) }

    CompositionLocalProvider(
        LocalChuColors provides palette,
        LocalChuFont provides fontOption,
        LocalChuTypography provides typography,
        content = content,
    )
}