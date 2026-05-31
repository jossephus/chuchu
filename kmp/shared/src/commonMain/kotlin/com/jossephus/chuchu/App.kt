package com.jossephus.chuchu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.ui.screens.AddServer.AddServerForm
import com.jossephus.chuchu.ui.screens.AddServer.AddServerScreen
import com.jossephus.chuchu.ui.screens.AddServer.ConnectionTestState
import com.jossephus.chuchu.ui.screens.ServerList.ServerListScreen
import com.jossephus.chuchu.ui.navigation.AppRoute
import com.jossephus.chuchu.ui.navigation.AppShellState
import com.jossephus.chuchu.ui.theme.ChuTheme
import com.jossephus.chuchu.ui.theme.ThemeMode

@Composable
fun App() {
    val shellState = remember { AppShellState() }
    val hosts = remember { mutableStateListOf<HostProfile>() }
    var nextId by remember { mutableStateOf(1L) }
    var themeName by remember { mutableStateOf("Catppuccin Mocha") }
    var fontName by remember { mutableStateOf("jetbrains_mono") }
    var lightThemeName by remember { mutableStateOf("Catppuccin Latte") }
    var themeMode by remember { mutableStateOf(ThemeMode.Dark) }

    ChuTheme(themeName = themeName, fontName = fontName) {
        when (shellState.route) {
            AppRoute.Servers -> {
                var searchQuery by remember { mutableStateOf("") }
                ServerListScreen(
                    hosts = hosts,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    onAddServer = { shellState.navigateTo(AppRoute.Terminal) },
                    onEditServer = {},
                    onConnectServer = {},
                    onDeleteServer = { id -> hosts.removeAll { it.id == id } },
                    onOpenSettings = { shellState.navigateTo(AppRoute.Settings) },
                )
            }
            AppRoute.Terminal -> {
                var form by remember { mutableStateOf(AddServerForm()) }
                val testState = remember { ConnectionTestState() }
                val keys = remember { emptyList<com.jossephus.chuchu.model.SshKey>() }
                AddServerScreen(
                    form = form,
                    testState = testState,
                    keys = keys,
                    onUpdateName = { form = form.copy(name = it) },
                    onUpdateHost = { form = form.copy(host = it) },
                    onUpdatePort = { form = form.copy(port = it) },
                    onUpdateUsername = { form = form.copy(username = it) },
                    onUpdatePassword = { form = form.copy(password = it) },
                    onUpdateTransport = { form = form.copy(transport = it) },
                    onUpdateAuthMethod = { form = form.copy(authMethod = it) },
                    onUpdateKeyPassphrase = { form = form.copy(keyPassphrase = it) },
                    onUpdateRequireAuthOnConnect = { form = form.copy(requireAuthOnConnect = it) },
                    onUpdatePostConnectCommand = { form = form.copy(postConnectCommand = it) },
                    onGenerateKey = {},
                    onCopyPublicKey = {},
                    onTestConnection = {},
                    onSave = {
                        val id = nextId++
                        hosts.add(
                            HostProfile(
                                id = id,
                                name = form.name.trim(),
                                host = form.host.trim(),
                                port = form.port.toIntOrNull() ?: 22,
                                username = form.username.trim(),
                                password = form.password,
                                transport = form.transport,
                                authMethod = form.authMethod,
                                requireAuthOnConnect = form.requireAuthOnConnect,
                                postConnectCommand = form.postConnectCommand.trim().ifBlank { null },
                            )
                        )
                        shellState.navigateTo(AppRoute.Servers)
                    },
                    onBack = { shellState.navigateTo(AppRoute.Servers) },
                )
            }
            AppRoute.Settings -> com.jossephus.chuchu.ui.screens.Settings.SettingsScreen(
                currentTheme = themeName,
                currentFont = fontName,
                appLockEnabled = false,
                requireAuthOnConnect = false,
                currentAccessoryLayoutIds = emptyList(),
                accessoryBarSingleRow = false,
                currentTerminalCustomKeyGroups = emptyList(),
                themeMode = themeMode,
                lightThemeName = lightThemeName,
                onThemeSelected = { themeName = it },
                onThemeModeChanged = { themeMode = it },
                onLightThemeSelected = { lightThemeName = it },
                onFontSelected = { fontName = it },
                onAppLockEnabledChanged = {},
                onRequireAuthOnConnectChanged = {},
                onAccessoryLayoutChanged = {},
                onAccessoryBarSingleRowChanged = {},
                onTerminalCustomActionsChanged = {},
                onBack = { shellState.navigateTo(AppRoute.Servers) },
            )
        }
    }
}
