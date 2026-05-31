package com.jossephus.chuchu.ui.screens.Files

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuSymbolsFontFamily
import com.jossephus.chuchu.ui.theme.ChuTypography

private typealias IconMap = Map<String, Pair<String, Color>>

private data class YaziIcons(val dirs: IconMap, val files: IconMap, val exts: IconMap)

// Matches one TOML entry: { name = "foo", text = "X", fg = "#aabbcc" }
private val ENTRY_RE = Regex(
    """name\s*=\s*"([^"]+)"\s*,\s*text\s*=\s*"([^"]+)"\s*,\s*fg\s*=\s*"#([0-9a-fA-F]{6})""""
)

private val cache = mutableMapOf<String, YaziIcons>()

@Synchronized
private fun loadIcons(context: Context, asset: String): YaziIcons = cache.getOrPut(asset) {
    val raw = runCatching {
        context.assets.open(asset).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    fun section(key: String): IconMap {
        val header = Regex("""\n$key\s*=\s*\[""").find(raw) ?: return emptyMap()
        val start = header.range.last + 1
        val end = raw.indexOf("\n]", start).takeIf { it > 0 } ?: return emptyMap()
        return ENTRY_RE.findAll(raw.substring(start, end)).associate { m ->
            m.groupValues[1].lowercase() to
                (m.groupValues[2] to Color(m.groupValues[3].toLong(16) or 0xFF000000))
        }
    }

    YaziIcons(section("dirs"), section("files"), section("exts"))
}

private val DEFAULT_DIR = "" to Color(0xFF00BCD4)
private val DEFAULT_FILE = "" to Color(0xFF6D8086)
private val DEFAULT_LINK = "" to Color(0xFF00BCD4)

@Composable
fun FileEntryIcon(entry: FileBrowserEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = ChuColors.current
    val background = palette.background
    val asset = remember(palette.name, background) {
        val nameSuggestsLight = palette.name.contains("light", ignoreCase = true)
        if (nameSuggestsLight || background.luminance() >= 0.5f) {
            "yazi-icons/theme-light.toml"
        } else {
            "yazi-icons/theme-dark.toml"
        }
    }
    val icons = remember(context, asset) { loadIcons(context.applicationContext, asset) }
    val name = entry.name.lowercase()
    val ext = name.substringAfterLast('.', "")

    val (glyph, color) = when (entry.type) {
        FileEntryType.Directory -> icons.dirs[name] ?: DEFAULT_DIR
        FileEntryType.File -> icons.files[name] ?: icons.exts[ext] ?: DEFAULT_FILE
        FileEntryType.Symlink -> icons.files[name] ?: icons.exts[ext] ?: DEFAULT_LINK
        FileEntryType.Other -> icons.exts[ext] ?: DEFAULT_FILE
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        ChuText(
            text = glyph,
            modifier = Modifier.fillMaxSize(),
            style = ChuTypography.current.body.copy(
                fontFamily = ChuSymbolsFontFamily,
                fontSize = 15.sp,
                lineHeight = 16.sp,
            ),
            color = color,
        )
    }
}
