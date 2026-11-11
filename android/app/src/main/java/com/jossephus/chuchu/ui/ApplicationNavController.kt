package com.jossephus.chuchu.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jossephus.chuchu.R
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.ui.screens.AddServer.AddServerScreen
import com.jossephus.chuchu.ui.screens.AddServer.AddServerViewModel
import com.jossephus.chuchu.ui.screens.Keys.KeysViewModel
import com.jossephus.chuchu.ui.screens.ServerList.ServerListScreen
import com.jossephus.chuchu.ui.screens.ServerList.ServerListViewModel
import com.jossephus.chuchu.ui.screens.SessionBrowser.SessionBrowserScreen
import com.jossephus.chuchu.ui.screens.SessionBrowser.SessionBrowserViewModel
import com.jossephus.chuchu.ui.screens.SessionBrowser.SessionPickerStyle
import com.jossephus.chuchu.ui.screens.SessionBrowser.opensSessionBrowser
import com.jossephus.chuchu.ui.screens.Settings.SettingsBackupViewModel
import com.jossephus.chuchu.ui.screens.Settings.SettingsScreen
import com.jossephus.chuchu.ui.screens.Terminal.TerminalScreen
import com.jossephus.chuchu.ui.screens.Terminal.TerminalViewModel
import com.jossephus.chuchu.ui.security.VerificationResult
import com.jossephus.chuchu.ui.security.requireUserVerification

@Composable
fun ApplicationNavController(launchHostId: Long? = null, onLaunchHostConsumed: () -> Unit = {}) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val lifecycleOwner = LocalLifecycleOwner.current
    var appUnlocked by rememberSaveable { mutableStateOf(false) }
    var unlockPromptRequested by rememberSaveable { mutableStateOf(false) }
    var appLockBlockedUntilToggle by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { source, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val isConfigChange =
                    (source as? android.app.Activity)?.isChangingConfigurations == true
                if (!isConfigChange) {
                    appUnlocked = false
                    unlockPromptRequested = false
                    appLockBlockedUntilToggle = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NavHost(navController = navController, startDestination = "servers") {
        composable("servers") {
            val vm: ServerListViewModel =
                viewModel(factory = ServerListViewModel.factory(application))
            val settingsRepo = SettingsRepository.getInstance(application)
            val requireAuthOnConnect by
                settingsRepo.requireAuthOnConnect.collectAsStateWithLifecycle()
            val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
            val sessionPickerStyle by settingsRepo.sessionPickerStyle.collectAsStateWithLifecycle()
            val hosts by vm.hosts.collectAsStateWithLifecycle()
            val searchQuery by vm.search.collectAsStateWithLifecycle()
            ServerListScreen(
                hosts = hosts,
                searchQuery = searchQuery,
                onSearchChange = vm::updateSearchQuery,
                onAddServer = { navController.navigate("servers/add") },
                localShellEnabled = localShellEnabled,
                onOpenLocalShell = localShell@{
                        if (!localShellEnabled) return@localShell
                        if (!requireAuthOnConnect) {
                            navController.navigate("terminal/local")
                        } else {
                            requireUserVerification(
                                context = context,
                                title = context.getString(R.string.verification_local_shell_title),
                                subtitle = context.getString(R.string.verification_local_shell_subtitle),
                            ) { result ->
                                if (result == VerificationResult.Success) {
                                    navController.navigate("terminal/local")
                                }
                            }
                        }
                    },
                onEditServer = { id -> navController.navigate("servers/edit/$id") },
                onCopyServer = { id -> navController.navigate("servers/copy/$id") },
                onConnectServer = { id ->
                    connectToHost(
                        context = context,
                        navController = navController,
                        id = id,
                        host = hosts.firstOrNull { it.id == id },
                        requireAuthOnConnect = requireAuthOnConnect,
                        sessionPickerStyle = sessionPickerStyle,
                    )
                },
                onDeleteServer = vm::deleteServer,
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            val settingsRepo = SettingsRepository.getInstance(application)
            val backupViewModel: SettingsBackupViewModel =
                viewModel(factory = SettingsBackupViewModel.factory(application))
            val keysViewModel: KeysViewModel =
                viewModel(factory = KeysViewModel.factory(application))
            val themeName by settingsRepo.themeName.collectAsStateWithLifecycle()
            val fontName by settingsRepo.fontName.collectAsStateWithLifecycle()
            val appLockEnabled by settingsRepo.appLockEnabled.collectAsStateWithLifecycle()
            val requireAuthOnConnect by
                settingsRepo.requireAuthOnConnect.collectAsStateWithLifecycle()
            val accessoryLayoutIds by settingsRepo.accessoryLayoutIds.collectAsStateWithLifecycle()
            val accessoryBarSingleRow by
                settingsRepo.accessoryBarSingleRow.collectAsStateWithLifecycle()
            val accessoryBarOnlyWithKeyboard by
                settingsRepo.accessoryBarOnlyWithKeyboard.collectAsStateWithLifecycle()
            val customKeyGroups by
                settingsRepo.terminalCustomKeyGroups.collectAsStateWithLifecycle()
            val showCustomActionsFab by
                settingsRepo.showCustomActionsFab.collectAsStateWithLifecycle()
            val builtinShortcuts by settingsRepo.builtinShortcuts.collectAsStateWithLifecycle()
            val tabMode by settingsRepo.terminalTabMode.collectAsStateWithLifecycle()
            val sessionPickerStyle by settingsRepo.sessionPickerStyle.collectAsStateWithLifecycle()
            val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
            val keepScreenAwake by settingsRepo.keepScreenAwake.collectAsStateWithLifecycle()
            val hideStatusBar by settingsRepo.hideStatusBar.collectAsStateWithLifecycle()
            val hideScreenContents by settingsRepo.hideScreenContents.collectAsStateWithLifecycle()
            val disableAutocorrect by settingsRepo.disableAutocorrect.collectAsStateWithLifecycle()
            val remoteClipboardWriteEnabled by
                settingsRepo.remoteClipboardWriteEnabled.collectAsStateWithLifecycle()
            val themeMode by settingsRepo.themeMode.collectAsStateWithLifecycle()
            val terminalFontSize by settingsRepo.terminalFontSize.collectAsStateWithLifecycle()
            val lightThemeName by settingsRepo.lightThemeName.collectAsStateWithLifecycle()
            SettingsScreen(
                currentTheme = themeName,
                currentFont = fontName,
                appLockEnabled = appLockEnabled,
                requireAuthOnConnect = requireAuthOnConnect,
                localShellEnabled = localShellEnabled,
                keepScreenAwake = keepScreenAwake,
                hideStatusBar = hideStatusBar,
                hideScreenContents = hideScreenContents,
                disableAutocorrect = disableAutocorrect,
                remoteClipboardWriteEnabled = remoteClipboardWriteEnabled,
                currentAccessoryLayoutIds = accessoryLayoutIds,
                accessoryBarSingleRow = accessoryBarSingleRow,
                accessoryBarOnlyWithKeyboard = accessoryBarOnlyWithKeyboard,
                currentTerminalCustomKeyGroups = customKeyGroups,
                showCustomActionsFab = showCustomActionsFab,
                onShowCustomActionsFabChanged = settingsRepo::setShowCustomActionsFab,
                builtinShortcuts = builtinShortcuts,
                onBuiltinShortcutsChanged = settingsRepo::setBuiltinShortcuts,
                currentTabMode = tabMode,
                onTabModeChanged = settingsRepo::setTerminalTabMode,
                currentSessionPickerStyle = sessionPickerStyle,
                onSessionPickerStyleChanged = settingsRepo::setSessionPickerStyle,
                themeMode = themeMode,
                lightThemeName = lightThemeName,
                onThemeSelected = settingsRepo::setTheme,
                onThemeModeChanged = settingsRepo::setThemeMode,
                onLightThemeSelected = settingsRepo::setLightTheme,
                onFontSelected = settingsRepo::setFont,
                onAppLockEnabledChanged = settingsRepo::setAppLockEnabled,
                onRequireAuthOnConnectChanged = settingsRepo::setRequireAuthOnConnect,
                onLocalShellEnabledChanged = settingsRepo::setLocalShellEnabled,
                onKeepScreenAwakeChanged = settingsRepo::setKeepScreenAwake,
                onHideStatusBarChanged = settingsRepo::setHideStatusBar,
                onHideScreenContentsChanged = settingsRepo::setHideScreenContents,
                onDisableAutocorrectChanged = settingsRepo::setDisableAutocorrect,
                onRemoteClipboardWriteEnabledChanged = settingsRepo::setRemoteClipboardWriteEnabled,
                onAccessoryLayoutChanged = settingsRepo::setAccessoryLayoutIds,
                onAccessoryBarSingleRowChanged = settingsRepo::setAccessoryBarSingleRow,
                onAccessoryBarOnlyWithKeyboardChanged = settingsRepo::setAccessoryBarOnlyWithKeyboard,
                currentTerminalFontSize = terminalFontSize,
                onTerminalFontSizeChanged = settingsRepo::setTerminalFontSize,
                onTerminalCustomActionsChanged = settingsRepo::setTerminalCustomKeyGroups,
                backupViewModel = backupViewModel,
                keysViewModel = keysViewModel,
                onBack = {
                    val currentRoute = navController.currentBackStackEntry?.destination?.route
                    if (currentRoute == "settings") {
                        navController.popBackStack()
                    }
                },
            )
        }
        composable("servers/add") { backStackEntry ->
            val vm: AddServerViewModel =
                viewModel(factory = AddServerViewModel.factory(application, null))
            ServerFormRoute(vm, backStackEntry, navController)
        }
        composable(
            route = "servers/copy/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            val vm: AddServerViewModel =
                viewModel(factory = AddServerViewModel.factory(application, null, copyFromHostId = id))
            AddServerScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(
            route = "servers/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            val vm: AddServerViewModel =
                viewModel(factory = AddServerViewModel.factory(application, id))
            ServerFormRoute(vm, backStackEntry, navController)
        }
        composable("servers/add-jump") {
            val vm: AddServerViewModel =
                viewModel(factory = AddServerViewModel.factory(application, null))
            AddServerScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                isJumpServer = true,
                onSaved = { savedId ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(NEW_JUMP_HOST_ID, savedId)
                    navController.popBackStack()
                },
            )
        }
        composable("terminal/local") {
            val settingsRepo = SettingsRepository.getInstance(application)
            val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
            if (localShellEnabled) {
                val vm: TerminalViewModel =
                    viewModel(factory = TerminalViewModel.factory(application))
                TerminalScreen(
                    vm = vm,
                    hostId = null,
                    openLocalShell = true,
                    onOpenSettings = { navController.navigate("settings") },
                    onBack = { navController.popBackStack() },
                )
            } else {
                LaunchedEffect(Unit) {
                    val popped = navController.popBackStack("servers", inclusive = false)
                    if (!popped) {
                        navController.navigate("servers") { launchSingleTop = true }
                    }
                }
            }
        }
        composable(
            route = "sessions/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: return@composable
            val vm: SessionBrowserViewModel = viewModel(factory = SessionBrowserViewModel.factory(application, id))
            SessionBrowserScreen(
                vm = vm,
                onOpenSession = { name, create ->
                    navController.navigate("terminal/$id?session=${Uri.encode(name)}&create=$create")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "terminal/{id}?session={session}&create={create}",
            arguments =
                listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("session") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("create") { type = NavType.BoolType; defaultValue = false },
                ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            val vm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(application))
            TerminalScreen(
                vm = vm,
                hostId = id,
                onOpenSettings = { navController.navigate("settings") },
                onBack = { navController.popBackStack() },
                multiplexerSession = backStackEntry.arguments?.getString("session"),
                createMultiplexerSession = backStackEntry.arguments?.getBoolean("create") == true,
            )
        }
    }

    val settingsRepo = SettingsRepository.getInstance(application)
    val appLockEnabled by settingsRepo.appLockEnabled.collectAsStateWithLifecycle()
    if (!appLockEnabled) {
        appUnlocked = false
        unlockPromptRequested = false
        appLockBlockedUntilToggle = false
    }
    LaunchedEffect(appLockEnabled, appUnlocked, unlockPromptRequested, appLockBlockedUntilToggle) {
        if (
            appLockEnabled && !appUnlocked && !unlockPromptRequested && !appLockBlockedUntilToggle
        ) {
            unlockPromptRequested = true
            requireUserVerification(
                context = context,
                title = context.getString(R.string.verification_unlock_title),
                subtitle = context.getString(R.string.verification_unlock_subtitle),
            ) { result ->
                appUnlocked = result == VerificationResult.Success
                if (result != VerificationResult.Success) {
                    appLockBlockedUntilToggle = true
                }
                unlockPromptRequested = false
            }
        }
    }

    val connectFromShortcut: (HostProfile) -> Unit = { host ->
        connectToHost(
            context = context,
            navController = navController,
            id = host.id,
            host = host,
            requireAuthOnConnect = settingsRepo.requireAuthOnConnect.value,
            sessionPickerStyle = settingsRepo.sessionPickerStyle.value,
        ) {
            // A shortcut can arrive over any screen; start from the server list, as a tap there does.
            popUpTo("servers")
            launchSingleTop = true
        }
    }
    var shortcutHostAwaitingPermission by remember { mutableStateOf<HostProfile?>(null) }
    val shortcutPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            shortcutHostAwaitingPermission?.let(connectFromShortcut)
            shortcutHostAwaitingPermission = null
        }

    LaunchedEffect(launchHostId, appLockEnabled, appUnlocked, appLockBlockedUntilToggle) {
        val id = launchHostId ?: return@LaunchedEffect
        // Wait for the app lock prompt so it does not overlap the connect prompt; drop the shortcut if unlocking failed.
        if (appLockEnabled && !appUnlocked) {
            if (appLockBlockedUntilToggle) onLaunchHostConsumed()
            return@LaunchedEffect
        }
        val host = HostRepository(AppDatabase.getInstance(application).hostProfileDao()).getById(id)
        when {
            host == null -> Toast.makeText(context, R.string.shortcut_host_missing, Toast.LENGTH_SHORT).show()
            // Ask first, as the server list does, so the session notification can show.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED -> {
                shortcutHostAwaitingPermission = host
                shortcutPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> connectFromShortcut(host)
        }
        // Consume last: clearing the key restarts this effect and would cancel the lookup above.
        onLaunchHostConsumed()
    }
}

private const val NEW_JUMP_HOST_ID = "newJumpHostId"

/** Opens a saved server from the server list or a launcher shortcut, verifying the user first when required. */
private fun connectToHost(
    context: Context,
    navController: NavHostController,
    id: Long,
    host: HostProfile?,
    requireAuthOnConnect: Boolean,
    sessionPickerStyle: SessionPickerStyle,
    navOptions: NavOptionsBuilder.() -> Unit = {},
) {
    val mustVerify = requireAuthOnConnect || host?.requireAuthOnConnect == true
    val route =
        if (sessionPickerStyle == SessionPickerStyle.Browser && host != null && host.opensSessionBrowser()) {
            "sessions/$id"
        } else {
            "terminal/$id"
        }
    if (!mustVerify) {
        navController.navigate(route, navOptions)
        return
    }
    requireUserVerification(
        context = context,
        title = context.getString(R.string.verification_connect_title),
        subtitle = context.getString(R.string.verification_connect_subtitle),
    ) { result ->
        if (result == VerificationResult.Success) {
            navController.navigate(route, navOptions)
        }
    }
}

@Composable
private fun ServerFormRoute(
    vm: AddServerViewModel,
    backStackEntry: NavBackStackEntry,
    navController: NavHostController,
) {
    val newJumpHostId by
        backStackEntry.savedStateHandle
            .getStateFlow<Long?>(NEW_JUMP_HOST_ID, null)
            .collectAsStateWithLifecycle()
    LaunchedEffect(newJumpHostId) {
        val id = newJumpHostId ?: return@LaunchedEffect
        vm.updateJumpHost(id)
        backStackEntry.savedStateHandle[NEW_JUMP_HOST_ID] = null
    }
    AddServerScreen(
        vm = vm,
        onBack = { navController.popBackStack() },
        onAddJumpServer = { navController.navigate("servers/add-jump") },
    )
}
