package com.jossephus.chuchu.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.jossephus.chuchu.shared.R

actual val ChuSymbolsFontFamily: FontFamily = FontFamily(
    Font(R.font.symbols_nerd_font_mono_regular, FontWeight.Normal),
)

@Composable
actual fun monoFamily(option: ChuFontOption): FontFamily {
    val resId = getFontResourceId(option)
    return FontFamily(
        Font(resId, FontWeight.Normal),
        Font(resId, FontWeight.Medium),
        Font(resId, FontWeight.SemiBold),
    )
}

actual fun getFontResourceId(option: ChuFontOption): Int = when (option) {
    ChuFontOption.JetBrainsMono -> R.font.jetbrains_mono_regular
    ChuFontOption.FiraCode -> R.font.fira_code_regular
    ChuFontOption.Hack -> R.font.hack_regular
    ChuFontOption.GeistMono -> R.font.geist_mono_regular
}
