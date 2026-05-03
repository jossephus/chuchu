package com.jossephus.chuchu.ui.screens.Settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.jossephus.chuchu.ui.terminal.TerminalCustomKeyGroup
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

enum class SettingsCategory(val label: String) {
    General("General"),
    Terminal("Terminal"),
}

@Composable
fun SettingsScreen(
    currentTheme: String,
    appLockEnabled: Boolean,
    requireAuthOnConnect: Boolean,
    currentAccessoryLayoutIds: List<String>,
    currentTerminalCustomKeyGroups: List<TerminalCustomKeyGroup>,
    onThemeSelected: (String) -> Unit,
    onAppLockEnabledChanged: (Boolean) -> Unit,
    onRequireAuthOnConnectChanged: (Boolean) -> Unit,
    onAccessoryLayoutChanged: (List<String>) -> Unit,
    onTerminalCustomActionsChanged: (List<TerminalCustomKeyGroup>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    var selectedCategory by remember { mutableStateOf(SettingsCategory.General) }
    var showAccessoryEditor by remember { mutableStateOf(false) }
    var showCustomActionEditor by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChuText("Settings", style = typography.headline)
                ChuButton(
                    onClick = onBack,
                    variant = ChuButtonVariant.Ghost,
                    contentPadding = PaddingValues(8.dp),
                ) {
                    ChuText("Close", style = typography.label, color = colors.textMuted)
                }
            }
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
                    appLockEnabled = appLockEnabled,
                    requireAuthOnConnect = requireAuthOnConnect,
                    onAppLockEnabledChanged = onAppLockEnabledChanged,
                    onRequireAuthOnConnectChanged = onRequireAuthOnConnectChanged,
                )
                SettingsCategory.Terminal -> TerminalSettings(
                    currentAccessoryLayoutIds = currentAccessoryLayoutIds,
                    onEditAccessoryLayout = { showAccessoryEditor = true },
                    currentTerminalCustomKeyGroups = currentTerminalCustomKeyGroups,
                    onEditCustomActions = { showCustomActionEditor = true },
                )
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
    }
}

@Composable
private fun GeneralSettings(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    appLockEnabled: Boolean,
    requireAuthOnConnect: Boolean,
    onAppLockEnabledChanged: (Boolean) -> Unit,
    onRequireAuthOnConnectChanged: (Boolean) -> Unit,
) {
    val typography = ChuTypography.current
    val colors = ChuColors.current
    ThemeSelectorSection(
        currentTheme = currentTheme,
        onThemeSelected = onThemeSelected,
    )
    Spacer(modifier = Modifier.height(16.dp))
    ChuText("Security", style = typography.title)
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChuText("Lock app on open", style = typography.label)
        ChuSwitch(checked = appLockEnabled, onCheckedChange = onAppLockEnabledChanged)
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChuText("Verify before server connect", style = typography.label)
        ChuSwitch(checked = requireAuthOnConnect, onCheckedChange = onRequireAuthOnConnectChanged)
    }
    Spacer(modifier = Modifier.height(4.dp))
    ChuText("Use biometrics or device PIN/pattern.", style = typography.bodySmall, color = colors.textMuted)
}
