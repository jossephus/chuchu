package com.jossephus.chuchu.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

actual val ChuSymbolsFontFamily: FontFamily = FontFamily.Default

@Composable
actual fun monoFamily(option: ChuFontOption): FontFamily = monoFamilyFromResources(option)

actual fun getFontResourceId(option: ChuFontOption): Int = 0
