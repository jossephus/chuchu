package com.jossephus.chuchu

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.ui.ApplicationNavController
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTheme
import com.jossephus.chuchu.ui.theme.GhosttyThemeRegistry

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0x00000000),
            navigationBarStyle = SystemBarStyle.dark(0x00000000),
        )
        setContent {
            AppRoot()
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    GhosttyThemeRegistry.init(context)
    val settings = SettingsRepository.getInstance(context)
    val themeName by settings.themeName.collectAsStateWithLifecycle()

    ChuTheme(themeName = themeName) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ChuColors.current.background),
        ) {
            ApplicationNavController()
        }
    }
}
