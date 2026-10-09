package com.jossephus.chuchu.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jossephus.chuchu.ui.screens.Terminal.TerminalTabMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [SettingsRepository].
 *
 * These exercise real [android.content.SharedPreferences] persistence on the JVM: setters
 * must update their flows immediately and survive a fresh repository instance reading the
 * same preference file, which is the behavior the app relies on across process restarts.
 *
 * Robolectric runs on the JVM with no device or emulator required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>().applicationContext
    }

    private fun newRepository() = SettingsRepository(context)

    private fun clearPrefs() {
        context.getSharedPreferences("chuchu_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("chuchu_terminal", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `fresh install gets the documented defaults`() = runBlocking {
        clearPrefs()
        val repo = newRepository()

        assertEquals(SettingsRepository.DEFAULT_THEME, repo.themeName.first())
        assertEquals(SettingsRepository.DEFAULT_LIGHT_THEME, repo.lightThemeName.first())
        assertEquals(SettingsRepository.DEFAULT_THEME_MODE, repo.themeMode.first())
        assertEquals(SettingsRepository.DEFAULT_TERMINAL_FONT_SIZE, repo.terminalFontSize.first())
        assertEquals(TerminalTabMode.Classic, repo.terminalTabMode.first())
        assertEquals(false, repo.appLockEnabled.first())
        assertEquals(true, repo.localShellEnabled.first())
        assertEquals(true, repo.showCustomActionsFab.first())
    }

    @Test
    fun `setters update flows and persist across repository instances`() = runBlocking {
        clearPrefs()
        val repo = newRepository()

        repo.setTheme("Dracula")
        repo.setLightTheme("Nord Light")
        repo.setTerminalTabMode(TerminalTabMode.Strip)
        repo.setAppLockEnabled(true)
        repo.setLocalShellEnabled(false)

        // Simulate a process restart: a brand-new instance over the same pref files.
        val reloaded = newRepository()

        assertEquals("Dracula", reloaded.themeName.first())
        assertEquals("Nord Light", reloaded.lightThemeName.first())
        assertEquals(TerminalTabMode.Strip, reloaded.terminalTabMode.first())
        assertEquals(true, reloaded.appLockEnabled.first())
        assertEquals(false, reloaded.localShellEnabled.first())
    }

    @Test
    fun `terminal font size is clamped to the supported range`() = runBlocking {
        clearPrefs()
        val repo = newRepository()

        repo.setTerminalFontSize(200f)
        assertEquals(SettingsRepository.MAX_TERMINAL_FONT_SIZE, repo.terminalFontSize.first())

        repo.setTerminalFontSize(1f)
        assertEquals(SettingsRepository.MIN_TERMINAL_FONT_SIZE, repo.terminalFontSize.first())

        val reloaded = newRepository()
        assertEquals(SettingsRepository.MIN_TERMINAL_FONT_SIZE, reloaded.terminalFontSize.first())
    }

    @Test
    fun `legacy font size is migrated from the old prefs file`() = runBlocking {
        clearPrefs()
        // The app once stored the font size in the "chuchu_terminal" file; the repository
        // must still pick it up until the user customizes the size in the new store.
        context.getSharedPreferences("chuchu_terminal", Context.MODE_PRIVATE)
            .edit()
            .putFloat("terminal_font_size_sp", 18f)
            .commit()

        val repo = newRepository()
        assertEquals(18f, repo.terminalFontSize.first())
    }

    @Test
    fun `unknown stored enum values fall back instead of crashing`() = runBlocking {
        clearPrefs()
        context.getSharedPreferences("chuchu_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("terminal_tab_mode", "NOT_A_REAL_MODE")
            .putString("theme_mode", "NOT_A_REAL_MODE")
            .commit()

        val repo = newRepository()
        assertEquals(TerminalTabMode.Classic, repo.terminalTabMode.first())
        assertEquals(SettingsRepository.DEFAULT_THEME_MODE, repo.themeMode.first())
    }
}
