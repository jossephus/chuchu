package com.jossephus.chuchu.ui.screens.Settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuSwitch
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.screens.Terminal.TerminalTabMode
import com.jossephus.chuchu.ui.terminal.TerminalCustomKeyGroup
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import com.jossephus.chuchu.ui.theme.ThemeMode

enum class SettingsCategory(val label: String) {
    General("general"),
    Terminal("terminal"),
}

@Composable
fun SettingsScreen(
    currentTheme: String,
    currentFont: String,
    appLockEnabled: Boolean,
    requireAuthOnConnect: Boolean,
    currentAccessoryLayoutIds: List<String>,
    accessoryBarSingleRow: Boolean,
    currentTerminalCustomKeyGroups: List<TerminalCustomKeyGroup>,
    currentTabMode: TerminalTabMode = TerminalTabMode.Classic,
    onTabModeChanged: (TerminalTabMode) -> Unit = {},
    themeMode: ThemeMode,
    lightThemeName: String,
    onThemeSelected: (String) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onLightThemeSelected: (String) -> Unit,
    onFontSelected: (String) -> Unit,
    onAppLockEnabledChanged: (Boolean) -> Unit,
    onRequireAuthOnConnectChanged: (Boolean) -> Unit,
    onAccessoryLayoutChanged: (List<String>) -> Unit,
    onAccessoryBarSingleRowChanged: (Boolean) -> Unit,
    currentTerminalFontSize: Float = 14f,
    onTerminalFontSizeChanged: (Float) -> Unit = {},
    onTerminalCustomActionsChanged: (List<TerminalCustomKeyGroup>) -> Unit,
    backupViewModel: SettingsBackupViewModel? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    var selectedCategory by remember { mutableStateOf(SettingsCategory.General) }
    var showAccessoryEditor by remember { mutableStateOf(false) }
    var showCustomActionEditor by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            showBackupSheet -> {
                backupViewModel?.dismissSheet()
                showBackupSheet = false
            }
            showCustomActionEditor -> showCustomActionEditor = false
            showAccessoryEditor -> showAccessoryEditor = false
            else -> onBack()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChuText("$ ", style = typography.headline, color = colors.textMuted)
                        ChuText("settings", style = typography.headline)
                    }
                    ChuButton(
                        onClick = onBack,
                        variant = ChuButtonVariant.Ghost,
                        bracketed = true,
                        borderColor = colors.textMuted,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        ChuText("x", style = typography.label, color = colors.textMuted)
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsCategory.entries.forEach { category ->
                        val isSelected = category == selectedCategory
                        ChuButton(
                            onClick = { selectedCategory = category },
                            variant = ChuButtonVariant.Outlined,
                            borderColor = if (isSelected) colors.accent else colors.border,
                            backgroundColor = if (isSelected) colors.accent.copy(alpha = 0.12f) else null,
                            modifier = Modifier.weight(1f),
                        ) {
                            ChuText(
                                category.label,
                                style = typography.label,
                                color = if (isSelected) colors.accent else colors.textSecondary,
                            )
                        }
                    }
                }
            }

            item {
                when (selectedCategory) {
                    SettingsCategory.General -> GeneralSettings(
                        currentTheme = currentTheme,
                        currentFont = currentFont,
                        onThemeSelected = onThemeSelected,
                        onThemeModeChanged = onThemeModeChanged,
                        themeMode = themeMode,
                        lightThemeName = lightThemeName,
                        onLightThemeSelected = onLightThemeSelected,
                        onFontSelected = onFontSelected,
                        appLockEnabled = appLockEnabled,
                        requireAuthOnConnect = requireAuthOnConnect,
                        onAppLockEnabledChanged = onAppLockEnabledChanged,
                        onRequireAuthOnConnectChanged = onRequireAuthOnConnectChanged,
                        onOpenBackup = { showBackupSheet = true },
                    )
                    SettingsCategory.Terminal -> TerminalSettings(
                        currentAccessoryLayoutIds = currentAccessoryLayoutIds,
                        onEditAccessoryLayout = { showAccessoryEditor = true },
                        accessoryBarSingleRow = accessoryBarSingleRow,
                        onAccessoryBarSingleRowChanged = onAccessoryBarSingleRowChanged,
                        currentTerminalFontSize = currentTerminalFontSize,
                        onTerminalFontSizeChanged = onTerminalFontSizeChanged,
                        currentTerminalCustomKeyGroups = currentTerminalCustomKeyGroups,
                        onEditCustomActions = { showCustomActionEditor = true },
                        currentTabMode = currentTabMode,
                        onTabModeChanged = onTabModeChanged,
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

        TerminalCustomActionsEditorSheet(
            visible = showCustomActionEditor,
            initialGroups = currentTerminalCustomKeyGroups,
            onSave = onTerminalCustomActionsChanged,
            onDismiss = { showCustomActionEditor = false },
        )

        if (backupViewModel != null) {
            SshBackupSheet(
                visible = showBackupSheet,
                viewModel = backupViewModel,
                onDismiss = { showBackupSheet = false },
            )
        }
    }
}

@Composable
private fun GeneralSettings(
    currentTheme: String,
    currentFont: String,
    onThemeSelected: (String) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    themeMode: ThemeMode,
    lightThemeName: String,
    onLightThemeSelected: (String) -> Unit,
    onFontSelected: (String) -> Unit,
    appLockEnabled: Boolean,
    requireAuthOnConnect: Boolean,
    onAppLockEnabledChanged: (Boolean) -> Unit,
    onRequireAuthOnConnectChanged: (Boolean) -> Unit,
    onOpenBackup: () -> Unit = {},
) {
    val typography = ChuTypography.current
    val colors = ChuColors.current
    ThemeSelectorSection(
        darkThemeName = currentTheme,
        onDarkThemeSelected = onThemeSelected,
        lightThemeName = lightThemeName,
        onLightThemeSelected = onLightThemeSelected,
        themeMode = themeMode,
        onThemeModeChanged = onThemeModeChanged,
    )
    Spacer(modifier = Modifier.height(16.dp))
    FontSelectorSection(
        currentFont = currentFont,
        onFontSelected = onFontSelected,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChuText("── ", style = typography.labelSmall, color = colors.textMuted)
        ChuText("SECURITY", style = typography.labelSmall, color = colors.textMuted)
        ChuText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(modifier = Modifier.height(1.dp).background(colors.textMuted).fillMaxWidth())
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChuText("lock app on open", style = typography.label)
        ChuSwitch(checked = appLockEnabled, onCheckedChange = onAppLockEnabledChanged)
    }
    Spacer(modifier = Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChuText("verify before server connect", style = typography.label)
        ChuSwitch(checked = requireAuthOnConnect, onCheckedChange = onRequireAuthOnConnectChanged)
    }
    Spacer(modifier = Modifier.height(4.dp))
    ChuText("use biometrics or device PIN/pattern.", style = typography.bodySmall, color = colors.textMuted)
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChuText("ssh hosts & keys backup", style = typography.label)
        ChuButton(
            onClick = onOpenBackup,
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            ChuText("manage", style = typography.label)
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    ChuText("encrypted export/import.", style = typography.bodySmall, color = colors.textMuted)
}
