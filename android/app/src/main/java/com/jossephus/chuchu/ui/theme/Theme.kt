package com.jossephus.chuchu.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun ChuTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalChuColors provides ChuDarkColors,
        LocalChuTypography provides ChuDefaultTypography,
        content = content,
    )
}