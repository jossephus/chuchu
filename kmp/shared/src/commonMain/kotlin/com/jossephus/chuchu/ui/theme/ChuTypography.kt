package com.jossephus.chuchu.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinproject.shared.generated.resources.*

@Immutable
data class ChuTypeScale(
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val labelSmall: TextStyle,
)

enum class ChuFontOption(
    val id: String,
    val label: String,
) {
    JetBrainsMono("jetbrains_mono", "JetBrains Mono"),
    FiraCode("fira_code", "Fira Code"),
    Hack("hack", "Hack"),
    GeistMono("geist_mono", "Geist Mono"),
    ;

    companion object {
        val default: ChuFontOption = JetBrainsMono

        fun fromId(id: String?): ChuFontOption {
            if (id.isNullOrBlank()) return default
            return entries.firstOrNull { it.id == id } ?: default
        }
    }
}

expect val ChuSymbolsFontFamily: FontFamily

@Composable
expect fun monoFamily(option: ChuFontOption): FontFamily

expect fun getFontResourceId(option: ChuFontOption): Int

@Composable
fun monoFamilyFromResources(option: ChuFontOption): FontFamily {
    val fontResource = when (option) {
        ChuFontOption.JetBrainsMono -> Res.font.jetbrains_mono_regular
        ChuFontOption.FiraCode -> Res.font.fira_code_regular
        ChuFontOption.Hack -> Res.font.hack_regular
        ChuFontOption.GeistMono -> Res.font.geist_mono_regular
    }
    return FontFamily(
        org.jetbrains.compose.resources.Font(fontResource, FontWeight.Normal),
        org.jetbrains.compose.resources.Font(fontResource, FontWeight.Medium),
        org.jetbrains.compose.resources.Font(fontResource, FontWeight.SemiBold),
    )
}

fun chuTypographyFor(fontFamily: FontFamily): ChuTypeScale {
    return ChuTypeScale(
        headline = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
        ),
        title = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        body = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        ),
        label = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        ),
    )
}

val ChuDefaultTypography: ChuTypeScale = chuTypographyFor(FontFamily.Default)

val LocalChuTypography = staticCompositionLocalOf { ChuDefaultTypography }
val LocalChuFont = staticCompositionLocalOf { ChuFontOption.default }

object ChuTypography {
    val current: ChuTypeScale
        @Composable
        @ReadOnlyComposable
        get() = LocalChuTypography.current
}
