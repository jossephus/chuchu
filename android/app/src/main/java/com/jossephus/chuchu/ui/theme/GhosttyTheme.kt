package com.jossephus.chuchu.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

data class GhosttyTheme(
    val name: String,
    val background: Color,
    val foreground: Color,
    val cursorColor: Color,
    val cursorText: Color,
    val selectionBackground: Color,
    val selectionForeground: Color,
    val palette: List<Color>,
) {
    companion object {
        fun parse(name: String, content: String): GhosttyTheme {
            val props = mutableMapOf<String, String>()
            val paletteColors = arrayOfNulls<Color>(16)

            content.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val eqIndex = line.indexOf('=')
                    if (eqIndex < 0) return@forEach
                    val key = line.substring(0, eqIndex).trim()
                    val value = line.substring(eqIndex + 1).trim()

                    if (key == "palette") {
                        val sepIndex = value.indexOf('=')
                        if (sepIndex >= 0) {
                            val idx = value.substring(0, sepIndex).toIntOrNull() ?: return@forEach
                            if (idx in 0..15) {
                                paletteColors[idx] = parseHexColor(value.substring(sepIndex + 1))
                            }
                        }
                    } else {
                        props[key] = value
                    }
            }

            val bg = parseHexColor(props["background"] ?: "#000000")
            val fg = parseHexColor(props["foreground"] ?: "#ffffff")

            return GhosttyTheme(
                name = name,
                background = bg,
                foreground = fg,
                cursorColor = parseHexColor(props["cursor-color"] ?: props["foreground"] ?: "#ffffff"),
                cursorText = parseHexColor(props["cursor-text"] ?: props["background"] ?: "#000000"),
                selectionBackground = parseHexColor(props["selection-background"] ?: props["foreground"] ?: "#ffffff"),
                selectionForeground = parseHexColor(props["selection-foreground"] ?: props["background"] ?: "#000000"),
                palette = List(16) { paletteColors[it] ?: defaultTerminalPaletteColor(it) },
            )
        }

        private fun parseHexColor(hex: String): Color {
            val h = hex.trimStart('#')
            val argb = when (h.length) {
                6 -> h.toLongOrNull(16)?.let { 0xFF000000 or it }
                8 -> h.toLongOrNull(16)
                else -> null
            }
            return Color((argb ?: 0xFF000000).toInt())
        }
    }
}

private fun Color.mix(other: Color, fraction: Float): Color {
    val inv = 1f - fraction
    return Color(
        red = this.red * inv + other.red * fraction,
        green = this.green * inv + other.green * fraction,
        blue = this.blue * inv + other.blue * fraction,
        alpha = this.alpha * inv + other.alpha * fraction,
    )
}

private fun Color.luminance(): Float =
    0.299f * red + 0.587f * green + 0.114f * blue

fun GhosttyTheme.toChuColorPalette(): ChuColorPalette {
    val isDark = background.luminance() < 0.5f
    val white = Color(0xFFFFFFFF)
    val black = Color(0xFF000000)

    val surface = if (isDark) background.mix(white, 0.15f) else background.mix(black, 0.15f)
    val surfaceVariant = if (isDark) background.mix(black, 0.15f) else background.mix(white, 0.15f)
    val border = background.mix(surface, 0.5f)

    val textPrimary = foreground
    val textSecondary = foreground.mix(background, 0.3f)
    val textMuted = foreground.mix(background, 0.55f)

    val accent = palette[4]
    val accentSecondary = palette[6]
    val onAccent = if (accent.luminance() > 0.5f) background else white

    val disabledSurface = surface.mix(background, 0.5f)
    val disabledText = textMuted

    return ChuColorPalette(
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        border = border,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textMuted = textMuted,
        accent = accent,
        accentSecondary = accentSecondary,
        error = palette[1],
        success = palette[2],
        warning = palette[3],
        onAccent = onAccent,
        disabledSurface = disabledSurface,
        disabledText = disabledText,
    )
}

fun GhosttyTheme.toTerminalPaletteBytes(): ByteArray {
    val colors = MutableList(256, ::defaultTerminalPaletteColor)
    for (index in palette.indices) {
        colors[index] = palette[index]
    }

    return ByteArray(colors.size * 3).also { bytes ->
        colors.forEachIndexed { index, color ->
            val offset = index * 3
            bytes[offset] = colorChannelToByte(color.red)
            bytes[offset + 1] = colorChannelToByte(color.green)
            bytes[offset + 2] = colorChannelToByte(color.blue)
        }
    }
}

fun Color.toRgbIntArray(): IntArray = intArrayOf(
    (red * 255f).roundToInt().coerceIn(0, 255),
    (green * 255f).roundToInt().coerceIn(0, 255),
    (blue * 255f).roundToInt().coerceIn(0, 255),
)

private fun colorChannelToByte(value: Float): Byte =
    (value * 255f).roundToInt().coerceIn(0, 255).toByte()

private fun defaultTerminalPaletteColor(index: Int): Color {
    if (index in DEFAULT_ANSI_COLORS.indices) {
        return DEFAULT_ANSI_COLORS[index]
    }

    return when (index) {
        in 16..231 -> {
            val cubeIndex = index - 16
            val red = XTERM_CUBE_LEVELS[cubeIndex / 36]
            val green = XTERM_CUBE_LEVELS[(cubeIndex / 6) % 6]
            val blue = XTERM_CUBE_LEVELS[cubeIndex % 6]
            Color(red = red / 255f, green = green / 255f, blue = blue / 255f)
        }

        in 232..255 -> {
            val level = 8 + (index - 232) * 10
            Color(red = level / 255f, green = level / 255f, blue = level / 255f)
        }

        else -> Color(0xFF000000)
    }
}

private val DEFAULT_ANSI_COLORS = listOf(
    Color(0xFF000000),
    Color(0xFF800000),
    Color(0xFF008000),
    Color(0xFF808000),
    Color(0xFF000080),
    Color(0xFF800080),
    Color(0xFF008080),
    Color(0xFFC0C0C0),
    Color(0xFF808080),
    Color(0xFFFF0000),
    Color(0xFF00FF00),
    Color(0xFFFFFF00),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFF00FFFF),
    Color(0xFFFFFFFF),
)

private val XTERM_CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)
