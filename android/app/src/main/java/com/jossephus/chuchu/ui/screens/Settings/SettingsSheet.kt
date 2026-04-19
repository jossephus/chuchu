package com.jossephus.chuchu.ui.screens.Settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import com.jossephus.chuchu.ui.theme.GhosttyThemeRegistry

enum class SettingsCategory(val label: String) {
    General("General"),
    Terminal("Terminal"),
}

@Composable
fun SettingsSheet(
    visible: Boolean,
    currentTheme: String,
    currentAccessoryLayoutIds: List<String>,
    onThemeSelected: (String) -> Unit,
    onAccessoryLayoutChanged: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    var selectedCategory by remember { mutableStateOf(SettingsCategory.General) }
    var showAccessoryEditor by remember { mutableStateOf(false) }

    if (visible) {
        BackHandler(enabled = true) {
            if (showAccessoryEditor) {
                showAccessoryEditor = false
            } else {
                onDismiss()
            }
        }

        Box(
            modifier = modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )

            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.7f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(colors.surfaceVariant)
                        .padding(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.textMuted),
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    ChuText("Settings", style = typography.headline)
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsCategory.entries.forEach { category ->
                            val isSelected = category == selectedCategory
                            ChuButton(
                                onClick = { selectedCategory = category },
                                variant = if (isSelected) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
                            ) {
                                ChuText(
                                    category.label,
                                    style = typography.label,
                                    color = if (isSelected) colors.onAccent else colors.textSecondary,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    when (selectedCategory) {
                        SettingsCategory.General -> GeneralSettings(
                            currentTheme = currentTheme,
                            onThemeSelected = onThemeSelected,
                        )
                        SettingsCategory.Terminal -> TerminalSettings(
                            currentAccessoryLayoutIds = currentAccessoryLayoutIds,
                            onEditAccessoryLayout = { showAccessoryEditor = true },
                        )
                    }
                }
            }

            AccessoryLayoutEditorSheet(
                visible = showAccessoryEditor,
                selectedIds = currentAccessoryLayoutIds,
                onSave = {
                    onAccessoryLayoutChanged(it)
                    showAccessoryEditor = false
                },
                onDismiss = { showAccessoryEditor = false },
            )
        }
    }
}

@Composable
private fun GeneralSettings(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val availableThemes = remember { GhosttyThemeRegistry.availableThemeNames }
    var themeQuery by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val themeListState = rememberLazyListState()
    val filteredThemes = remember(availableThemes, themeQuery) {
        val query = themeQuery.trim()
        if (query.isEmpty()) {
            availableThemes
        } else {
            availableThemes.filter { it.contains(query, ignoreCase = true) }
        }
    }

    LaunchedEffect(expanded, filteredThemes) {
        if (expanded) {
            val selectedIndex = filteredThemes.indexOf(currentTheme)
            if (selectedIndex >= 0) {
                themeListState.scrollToItem(selectedIndex)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChuText("Theme", style = typography.title)

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
                ChuText(
                    if (expanded) "▲" else "▼",
                    style = typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                ) {
                    items(filteredThemes) { themeName ->
                        val isSelected = themeName == currentTheme
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) colors.surface else colors.surfaceVariant)
                                .clickable {
                                    onThemeSelected(themeName)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ChuText(
                                    themeName,
                                    style = typography.label,
                                    color = if (isSelected) colors.accent else colors.textPrimary,
                                )
                                if (isSelected) {
                                    ChuText("✓", style = typography.label, color = colors.accent)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
