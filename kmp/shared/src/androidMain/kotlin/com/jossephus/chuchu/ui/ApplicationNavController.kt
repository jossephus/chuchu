package com.jossephus.chuchu.ui

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.service.terminal.TabDescriptor
import com.jossephus.chuchu.service.terminal.TerminalSessionRepository
import com.jossephus.chuchu.ui.screens.AddServer.AddServerScreen
import com.jossephus.chuchu.ui.screens.AddServer.AddServerViewModel
import com.jossephus.chuchu.ui.screens.ServerList.ServerListScreen
import com.jossephus.chuchu.ui.screens.ServerList.ServerListViewModel
import com.jossephus.chuchu.ui.screens.Settings.SettingsBackupViewModel
import com.jossephus.chuchu.ui.screens.Settings.SettingsScreen
import com.jossephus.chuchu.ui.screens.Settings.SshBackupSheet
import com.jossephus.chuchu.ui.screens.Terminal.TerminalScreen
import com.jossephus.chuchu.ui.screens.Terminal.TerminalViewModel
import com.jossephus.chuchu.ui.security.VerificationResult
import com.jossephus.chuchu.ui.security.requireUserVerification

@Composable
fun ApplicationNavController() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val lifecycleOwner = LocalLifecycleOwner.current
    var appUnlocked by remember { mutableStateOf(false) }
    var unlockPromptRequested by remember { mutableStateOf(false) }
    var appLockBlockedUntilToggle by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                appUnlocked = false
                unlockPromptRequested = false
                appLockBlockedUntilToggle = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NavHost(navController = navController, startDestination = "servers") {
        composable("servers") {
            val vm: ServerListViewModel = viewModel(factory = ServerListViewModel.factory(application))
            val settingsRepo = SettingsRepository.getInstance(application)
            val requireAuthOnConnect by settingsRepo.requireAuthOnConnect.collectAsStateWithLifecycle()
            val hosts by vm.hosts.collectAsStateWithLifecycle()
            val searchQuery by vm.search.collectAsStateWithLifecycle()
            val sessionRepo = remember(application) { TerminalSessionRepository.getInstance(application) }
            val connectedHostIds by sessionRepo.connectedHostIds.collectAsStateWithLifecycle()
            val rawTabs by sessionRepo.tabs.collectAsStateWithLifecycle()
            val openTabDescriptors = remember(rawTabs) { rawTabs.map { TabDescriptor(it.id, it.spec.hostId, it.spec.tabLabel) } }
            val ctx = LocalContext.current
            var pendingConnectCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { _: Boolean ->
                pendingConnectCallback?.invoke()
                pendingConnectCallback = null
            }

            ServerListScreen(
                hosts = hosts,
                searchQuery = searchQuery,
                onSearchChange = vm::updateSearchQuery,
                onAddServer = { navController.navigate("servers/add") },
                onEditServer = { id -> navController.navigate("servers/edit/$id") },
                onConnectServer = { id ->
                    val host = hosts.firstOrNull { it.id == id }
                    val hostRequiresAuth = host?.requireAuthOnConnect == true
                    val mustVerify = requireAuthOnConnect || hostRequiresAuth
                    if (!mustVerify) {
                        navController.navigate("terminal/$id")
                    } else {
                        requireUserVerification(
                            context = context,
                            title = "Verify to connect",
                            subtitle = "Authenticate to open this server session",
                        ) { result ->
                            if (result == VerificationResult.Success) {
                                navController.navigate("terminal/$id")
                            }
                        }
                    }
                },
                onDeleteServer = vm::deleteServer,
                onOpenSettings = { navController.navigate("settings") },
                connectedHostIds = connectedHostIds,
                openTabs = openTabDescriptors,
                onCloseTab = { tabId -> sessionRepo.closeTab(tabId) },
                onRequestNotificationPermission = { onGranted ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingConnectCallback = onGranted
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onGranted()
                    }
                },
                showToast = { msg ->
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                },
            )
        }
        composable("settings") {
            val settingsRepo = SettingsRepository.getInstance(application)
            val backupViewModel: SettingsBackupViewModel = viewModel(
                factory = SettingsBackupViewModel.factory(application),
            )
            val ctx = LocalContext.current
            val themeName by settingsRepo.themeName.collectAsStateWithLifecycle()
            val fontName by settingsRepo.fontName.collectAsStateWithLifecycle()
            val appLockEnabled by settingsRepo.appLockEnabled.collectAsStateWithLifecycle()
            val requireAuthOnConnect by settingsRepo.requireAuthOnConnect.collectAsStateWithLifecycle()
            val accessoryLayoutIds by settingsRepo.accessoryLayoutIds.collectAsStateWithLifecycle()
            val accessoryBarSingleRow by settingsRepo.accessoryBarSingleRow.collectAsStateWithLifecycle()
            val customKeyGroups by settingsRepo.terminalCustomKeyGroups.collectAsStateWithLifecycle()
            val themeMode by settingsRepo.themeMode.collectAsStateWithLifecycle()
            val lightThemeName by settingsRepo.lightThemeName.collectAsStateWithLifecycle()
            SettingsScreen(
                currentTheme = themeName,
                currentFont = fontName,
                appLockEnabled = appLockEnabled,
                requireAuthOnConnect = requireAuthOnConnect,
                currentAccessoryLayoutIds = accessoryLayoutIds,
                accessoryBarSingleRow = accessoryBarSingleRow,
                currentTerminalCustomKeyGroups = customKeyGroups,
                themeMode = themeMode,
                lightThemeName = lightThemeName,
                onThemeSelected = settingsRepo::setTheme,
                onThemeModeChanged = settingsRepo::setThemeMode,
                onLightThemeSelected = settingsRepo::setLightTheme,
                onFontSelected = settingsRepo::setFont,
                onAppLockEnabledChanged = settingsRepo::setAppLockEnabled,
                onRequireAuthOnConnectChanged = settingsRepo::setRequireAuthOnConnect,
                onAccessoryLayoutChanged = settingsRepo::setAccessoryLayoutIds,
                onAccessoryBarSingleRowChanged = settingsRepo::setAccessoryBarSingleRow,
                onTerminalCustomActionsChanged = settingsRepo::setTerminalCustomKeyGroups,
                onBack = {
                    val currentRoute = navController.currentBackStackEntry?.destination?.route
                    if (currentRoute == "settings") {
                        navController.popBackStack()
                    }
                },
                platformContext = ctx,
                backupSheetContent = { visible, onDismiss ->
                    SshBackupSheet(
                        visible = visible,
                        viewModel = backupViewModel,
                        onDismiss = onDismiss,
                    )
                },
            )
        }
        composable("servers/add") {
            val vm: AddServerViewModel = viewModel(factory = AddServerViewModel.factory(application, null))
            val form by vm.form.collectAsStateWithLifecycle()
            val testState by vm.testState.collectAsStateWithLifecycle()
            val keys by vm.keys.collectAsStateWithLifecycle()
            val ctx = LocalContext.current
            AddServerScreen(
                form = form,
                testState = testState,
                keys = keys,
                onUpdateName = vm::updateName,
                onUpdateHost = vm::updateHost,
                onUpdatePort = vm::updatePort,
                onUpdateUsername = vm::updateUsername,
                onUpdatePassword = vm::updatePassword,
                onUpdateTransport = vm::updateTransport,
                onUpdateAuthMethod = vm::updateAuthMethod,
                onUpdateKeyPassphrase = vm::updateKeyPassphrase,
                onUpdateRequireAuthOnConnect = vm::updateRequireAuthOnConnect,
                onUpdatePostConnectCommand = vm::updatePostConnectCommand,
                onGenerateKey = { vm.generateKey(form.name) },
                onCopyPublicKey = {
                    if (form.publicKeyOpenSsh.isBlank()) {
                        android.widget.Toast.makeText(ctx, "No public key available", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SSH Public Key", form.publicKeyOpenSsh))
                        android.widget.Toast.makeText(ctx, "Public key copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onTestConnection = vm::testConnection,
                onSave = { vm.save { navController.popBackStack() } },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "servers/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            val vm: AddServerViewModel = viewModel(factory = AddServerViewModel.factory(application, id))
            val form by vm.form.collectAsStateWithLifecycle()
            val testState by vm.testState.collectAsStateWithLifecycle()
            val keys by vm.keys.collectAsStateWithLifecycle()
            val ctx = LocalContext.current
            AddServerScreen(
                form = form,
                testState = testState,
                keys = keys,
                onUpdateName = vm::updateName,
                onUpdateHost = vm::updateHost,
                onUpdatePort = vm::updatePort,
                onUpdateUsername = vm::updateUsername,
                onUpdatePassword = vm::updatePassword,
                onUpdateTransport = vm::updateTransport,
                onUpdateAuthMethod = vm::updateAuthMethod,
                onUpdateKeyPassphrase = vm::updateKeyPassphrase,
                onUpdateRequireAuthOnConnect = vm::updateRequireAuthOnConnect,
                onUpdatePostConnectCommand = vm::updatePostConnectCommand,
                onGenerateKey = { vm.generateKey(form.name) },
                onCopyPublicKey = {
                    if (form.publicKeyOpenSsh.isBlank()) {
                        android.widget.Toast.makeText(ctx, "No public key available", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SSH Public Key", form.publicKeyOpenSsh))
                        android.widget.Toast.makeText(ctx, "Public key copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onTestConnection = vm::testConnection,
                onSave = { vm.save { navController.popBackStack() } },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "terminal/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            val vm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(application))
            TerminalScreen(
                vm = vm,
                hostId = id,
                onOpenSettings = { navController.navigate("settings") },
                onBack = { navController.popBackStack() },
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
        if (appLockEnabled && !appUnlocked && !unlockPromptRequested && !appLockBlockedUntilToggle) {
            unlockPromptRequested = true
            requireUserVerification(
                context = context,
                title = "Unlock Chuchu",
                subtitle = "Authenticate to continue",
            ) { result ->
                appUnlocked = result == VerificationResult.Success
                if (result != VerificationResult.Success) {
                    appLockBlockedUntilToggle = true
                }
                unlockPromptRequested = false
            }
        }
    }
}
