package com.jossephus.chuchu

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.shortcuts.HostShortcuts
import com.jossephus.chuchu.ui.ApplicationNavController
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTheme
import com.jossephus.chuchu.ui.theme.GhosttyThemeRegistry
import com.jossephus.chuchu.ui.theme.resolveActiveThemeName
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val launchHostId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsRepository.getInstance(this)
        // Skip on recreation, or a rotation would reopen the shortcut's server.
        if (savedInstanceState == null) {
            launchHostId.value = HostShortcuts.hostIdFrom(intent)
        }
        val hostRepository = HostRepository(AppDatabase.getInstance(this).hostProfileDao())
        lifecycleScope.launch {
            // Server names in the launcher would bypass the app lock, so hide them while it is on.
            combine(hostRepository.observeAll(), settings.appLockEnabled) { hosts, appLocked -> hosts to appLocked }
                .collect { (hosts, appLocked) -> HostShortcuts.publish(this@MainActivity, hosts, hideNames = appLocked) }
        }
        lifecycleScope.launch {
            settings.hideScreenContents.collect { hideScreenContents ->
                if (hideScreenContents) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0x00000000),
            navigationBarStyle = SystemBarStyle.dark(0x00000000),
        )
        setContent {
            AppRoot(
                launchHostId = launchHostId.value,
                onLaunchHostConsumed = { launchHostId.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A newer launch replaces a shortcut still waiting for the app lock.
        launchHostId.value = HostShortcuts.hostIdFrom(intent)
    }
}

@Composable
fun AppRoot(launchHostId: Long? = null, onLaunchHostConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    GhosttyThemeRegistry.init(context)
    val settings = SettingsRepository.getInstance(context)
    val fontName by settings.fontName.collectAsStateWithLifecycle()
    val themeName by settings.themeName.collectAsStateWithLifecycle()
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val lightThemeName by settings.lightThemeName.collectAsStateWithLifecycle()
    val resolvedThemeName = resolveActiveThemeName(
        themeMode = themeMode,
        darkThemeName = themeName,
        lightThemeName = lightThemeName,
    )

    ChuTheme(themeName = resolvedThemeName, fontName = fontName) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ChuColors.current.background),
        ) {
            ApplicationNavController(launchHostId, onLaunchHostConsumed)
        }
    }
}
