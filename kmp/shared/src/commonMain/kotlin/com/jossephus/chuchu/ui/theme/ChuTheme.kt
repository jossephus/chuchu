package com.jossephus.chuchu.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

@Composable
fun ChuTheme(
    themeName: String? = null,
    fontName: String? = null,
    platformContext: Any? = null,
    content: @Composable () -> Unit,
) {
    val palette = themeName
        ?.let { ThemeRegistry.getTheme(platformContext, it) }
        ?.toChuColorPalette()
        ?: ChuDarkColors

    val fontOption = remember(fontName) { ChuFontOption.fromId(fontName) }
    val fontFamily = monoFamily(fontOption)
    val typography = remember(fontFamily) { chuTypographyFor(fontFamily) }

    CompositionLocalProvider(
        LocalChuColors provides palette,
        LocalChuFont provides fontOption,
        LocalChuTypography provides typography,
        content = content,
    )
}
