package com.jossephus.chuchu.ui.terminal

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.jossephus.chuchu.ui.theme.ChuTheme
import com.jossephus.chuchu.ui.theme.GhosttyThemeRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class KeyboardAccessoryBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `removing keys from the two-row bar does not crash`() {
        val context = ApplicationProvider.getApplicationContext<Application>().applicationContext
        GhosttyThemeRegistry.init(context)
        val all = TerminalAccessoryLayoutStore.allCatalogEntries()
        var entries by mutableStateOf(all)

        composeRule.setContent {
            ChuTheme(themeName = "Catppuccin Mocha") {
                KeyboardAccessoryBar(
                    entries = entries,
                    modifierState = ModifierState(),
                    onAction = {},
                    useSingleRow = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        composeRule.waitForIdle()

        entries = all.take(3)
        composeRule.waitForIdle()
    }
}
