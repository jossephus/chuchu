package com.jossephus.chuchu.ui.screens.Settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuSwitch
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import com.jossephus.chuchu.ui.theme.GhosttyTheme
import com.jossephus.chuchu.ui.theme.GhosttyThemeRegistry

@Composable
internal fun ThemeSelectorSection(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val context = LocalContext.current
    val availableThemes = remember { GhosttyThemeRegistry.availableThemeNames }
    var themeQuery by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val themeListState = rememberLazyListState()
    val filteredThemes = remember(availableThemes, themeQuery) {
        val query = themeQuery.trim()
        if (query.isEmpty()) availableThemes else availableThemes.filter { it.contains(query, ignoreCase = true) }
    }
    val previewTheme = remember(context, currentTheme) {
        GhosttyThemeRegistry.getTheme(context, currentTheme)
    }
    var showPreview by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(expanded, filteredThemes) {
        if (expanded) {
            val selectedIndex = filteredThemes.indexOf(currentTheme)
            if (selectedIndex >= 0) themeListState.scrollToItem(selectedIndex)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChuText("Theme", style = typography.title)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChuText("Show preview", style = typography.label, color = colors.textSecondary)
                ChuSwitch(checked = showPreview, onCheckedChange = { showPreview = it })
            }
        }

        if (showPreview && previewTheme != null) ThemePreview(theme = previewTheme, name = currentTheme)

        ChuButton(
            onClick = { expanded = !expanded },
            variant = ChuButtonVariant.Outlined,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChuText(currentTheme, style = typography.label)
                ChuText(if (expanded) "▲" else "▼", style = typography.labelSmall, color = colors.textMuted)
            }
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChuTextField(
                    value = themeQuery,
                    onValueChange = { themeQuery = it },
                    label = "Search themes",
                    placeholder = "Type to filter",
                    singleLine = true,
                    autoFocus = false,
                )

                LazyColumn(
                    state = themeListState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                ) {
                    items(filteredThemes) { themeName ->
                        val isSelected = themeName == currentTheme
                        val rowTheme = remember(themeName) { GhosttyThemeRegistry.getTheme(context, themeName) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) colors.surface else colors.surfaceVariant)
                                .clickable { onThemeSelected(themeName) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    if (rowTheme != null) PaletteSwatchStrip(theme = rowTheme)
                                    ChuText(
                                        themeName,
                                        style = typography.label,
                                        color = if (isSelected) colors.accent else colors.textPrimary,
                                    )
                                }
                                if (isSelected) ChuText("✓", style = typography.label, color = colors.accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemePreview(theme: GhosttyTheme, name: String) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surface).padding(1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.accent),
                )
                ChuText("PREVIEW", style = typography.labelSmall, color = colors.textSecondary)
            }
            ChuText(name, style = typography.labelSmall, color = colors.textMuted)
        }

        ThemePreviewBody(theme = theme)
    }
}

@Composable
private fun ThemePreviewBody(theme: GhosttyTheme) {
    val typography = ChuTypography.current
    val mono = typography.body.copy(fontSize = 9.sp, lineHeight = 12.sp)

    Column(
        modifier = Modifier.fillMaxWidth().background(theme.background).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChuText("user", style = mono, color = theme.palette[2])
            ChuText("@", style = mono, color = theme.foreground)
            ChuText("host", style = mono, color = theme.palette[3])
            ChuText(" ", style = mono, color = theme.foreground)
            ChuText("~/code", style = mono, color = theme.palette[4])
            ChuText(" $ ", style = mono, color = theme.foreground)
            ChuText("ls -la", style = mono, color = theme.foreground)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChuText("README.md", style = mono, color = theme.foreground)
            ChuText("src/", style = mono, color = theme.palette[4])
            ChuText("build.zig", style = mono, color = theme.palette[2])
        }
        ChuText("error: undefined symbol", style = mono, color = theme.palette[1])
        ChuText("warning: implicit conversion", style = mono, color = theme.palette[3])

        Row(verticalAlignment = Alignment.CenterVertically) {
            ChuText("→ ", style = mono, color = theme.palette[2])
            ChuText("bat ", style = mono, color = theme.palette[4])
            ChuText("main.zig", style = mono.copy(textDecoration = TextDecoration.Underline), color = theme.palette[6])
        }
        BatPreview(theme = theme, style = mono)

        Row(verticalAlignment = Alignment.CenterVertically) {
            ChuText("$ ", style = mono, color = theme.foreground)
            Box(modifier = Modifier.height(10.dp).width(5.dp).background(theme.cursorColor))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            theme.palette.forEach { paletteColor ->
                Box(modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(2.dp)).background(paletteColor))
            }
        }
    }
}

@Composable
private fun BatPreview(theme: GhosttyTheme, style: TextStyle) {
    val chromeColor = theme.palette[8]
    val rule = "─".repeat(40)

    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        BasicText(text = "───────┬$rule", style = style.copy(color = chromeColor))
        BasicText(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = chromeColor)) { append("       │ ") }
                withStyle(SpanStyle(color = theme.foreground)) { append("File: ") }
                withStyle(SpanStyle(color = theme.foreground, fontWeight = FontWeight.Bold)) { append("main.zig") }
            },
            style = style,
        )
        BasicText(text = "───────┼$rule", style = style.copy(color = chromeColor))
        ZigPreviewLines.forEach { (lineNo, segments) ->
            BasicText(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = chromeColor)) { append(String.format("%4d   │ ", lineNo)) }
                    segments.forEach { seg -> withStyle(SpanStyle(color = seg.role.color(theme))) { append(seg.text) } }
                },
                style = style,
            )
        }
        BasicText(text = "───────┴$rule", style = style.copy(color = chromeColor))
    }
}

private enum class TokenRole { Standard, Keyword, Type, String, Number, Function;
    fun color(theme: GhosttyTheme): Color = when (this) {
        Standard -> theme.foreground
        Keyword -> theme.palette[5]
        Type -> theme.palette[12]
        String -> theme.palette[10]
        Number -> theme.palette[4]
        Function -> theme.palette[2]
    }
}

private data class Seg(val text: String, val role: TokenRole)

private fun s(text: String) = Seg(text, TokenRole.Standard)
private fun k(text: String) = Seg(text, TokenRole.Keyword)
private fun t(text: String) = Seg(text, TokenRole.Type)
private fun str(text: String) = Seg(text, TokenRole.String)
private fun n(text: String) = Seg(text, TokenRole.Number)
private fun fn(text: String) = Seg(text, TokenRole.Function)

private val ZigPreviewLines: List<Pair<Int, List<Seg>>> = listOf(
    1 to listOf(k("const"), s(" std "), k("= @import"), s("("), str("\"std\""), s(");")),
    2 to emptyList(),
    3 to listOf(k("pub "), t("fn "), fn("main"), s("(n: "), t("u32"), s(") "), t("void"), s(" {")),
    4 to listOf(k("    var "), s("i: "), t("u32"), s(" = "), n("0"), s(";")),
    5 to listOf(k("    while "), s("(i < n) : (i "), k("+= "), n("1"), s(") {")),
    6 to listOf(s("        std.debug.print("), str("\"zig"), t("\\n"), str("\""), s(", .{});")),
    7 to listOf(s("    }")),
    8 to listOf(s("}")),
)

private val SwatchIndices = intArrayOf(1, 2, 3, 4, 5, 6)

@Composable
private fun PaletteSwatchStrip(theme: GhosttyTheme) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(theme.background).padding(horizontal = 3.dp, vertical = 3.dp),
    ) {
        SwatchIndices.forEach { idx ->
            val color: Color = theme.palette.getOrNull(idx) ?: theme.foreground
            Box(modifier = Modifier.width(8.dp).height(12.dp).clip(RoundedCornerShape(1.dp)).background(color))
        }
    }
}
