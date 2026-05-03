package com.jossephus.chuchu.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
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
import com.jossephus.chuchu.ui.screens.AddServer.AddServerScreen
import com.jossephus.chuchu.ui.screens.AddServer.AddServerViewModel
import com.jossephus.chuchu.ui.screens.ServerList.ServerListScreen
import com.jossephus.chuchu.ui.screens.ServerList.ServerListViewModel
import com.jossephus.chuchu.ui.screens.Settings.SettingsScreen
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
            )
        }
        composable("settings") {
            val settingsRepo = SettingsRepository.getInstance(application)
            val themeName by settingsRepo.themeName.collectAsStateWithLifecycle()
            val appLockEnabled by settingsRepo.appLockEnabled.collectAsStateWithLifecycle()
            val requireAuthOnConnect by settingsRepo.requireAuthOnConnect.collectAsStateWithLifecycle()
            val accessoryLayoutIds by settingsRepo.accessoryLayoutIds.collectAsStateWithLifecycle()
            val customKeyGroups by settingsRepo.terminalCustomKeyGroups.collectAsStateWithLifecycle()
            SettingsScreen(
                currentTheme = themeName,
                appLockEnabled = appLockEnabled,
                requireAuthOnConnect = requireAuthOnConnect,
                currentAccessoryLayoutIds = accessoryLayoutIds,
                currentTerminalCustomKeyGroups = customKeyGroups,
                onThemeSelected = settingsRepo::setTheme,
                onAppLockEnabledChanged = settingsRepo::setAppLockEnabled,
                onRequireAuthOnConnectChanged = settingsRepo::setRequireAuthOnConnect,
                onAccessoryLayoutChanged = settingsRepo::setAccessoryLayoutIds,
                onTerminalCustomActionsChanged = settingsRepo::setTerminalCustomKeyGroups,
                onBack = { navController.popBackStack() },
            )
        }
        composable("servers/add") {
            val vm: AddServerViewModel = viewModel(factory = AddServerViewModel.factory(application, null))
            AddServerScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "servers/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            val vm: AddServerViewModel = viewModel(factory = AddServerViewModel.factory(application, id))
            AddServerScreen(
                vm = vm,
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
