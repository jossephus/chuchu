package com.jossephus.chuchu.plugin

import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.data.repository.SettingsRepository
import com.jossephus.chuchu.plugin.api.PluginTerminal
import com.jossephus.chuchu.plugin.api.PtySize
import com.jossephus.chuchu.plugin.api.TerminalFactory
import com.jossephus.chuchu.plugin.api.TerminalOptions
import com.jossephus.chuchu.service.terminal.GhosttyBridge
import com.jossephus.chuchu.service.terminal.TerminalSnapshot
import com.jossephus.chuchu.ui.terminal.GhosttyKeyAction
import com.jossephus.chuchu.ui.terminal.TerminalCanvas
import com.jossephus.chuchu.ui.terminal.TerminalInputView
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.GhosttyThemeRegistry
import com.jossephus.chuchu.ui.theme.resolveActiveThemeName
import com.jossephus.chuchu.ui.theme.toRgbIntArray
import com.jossephus.chuchu.ui.theme.toTerminalPaletteBytes
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The plugin terminal holding the keyboard, if any. chuchu's own input (accessory bar keys,
 * paste, custom actions) goes there instead of the tab's shell, because a plugin view that
 * shows its own terminals (e.g. multiplexer panes) is where the user is typing.
 */
object PluginInputFocus {
    @Volatile
    var target: GhosttyPluginTerminal? = null
        internal set
}

/**
 * [TerminalFactory] backed by Ghostty. All plugin terminals share one emulator thread: each
 * Ghostty handle must be used from a single thread, and a thread per pane would waste
 * memory when a multiplexer plugin opens many.
 */
class GhosttyTerminalFactory : TerminalFactory {
    private val dispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "plugin-terminals").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override fun create(options: TerminalOptions): PluginTerminal = GhosttyPluginTerminal(options, dispatcher, scope)
}

class GhosttyPluginTerminal internal constructor(
    private val options: TerminalOptions,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) : PluginTerminal {
    private val bridge = GhosttyBridge()

    // Only touched on [dispatcher].
    private var handle = 0L
    private var lastSnapshotAtMs = 0L
    private var snapshotScheduled = false
    private var cellWidth = 1
    private var cellHeight = 1

    @Volatile private var closed = false

    private val inputChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val snapshot = MutableStateFlow<TerminalSnapshot?>(null)
    private val _size = MutableStateFlow(PtySize(DEFAULT_COLS, DEFAULT_ROWS))
    private val _title = MutableStateFlow<String?>(null)

    override val input: Flow<ByteArray> = inputChannel.receiveAsFlow()
    override val size: StateFlow<PtySize> = _size.asStateFlow()
    override val title: StateFlow<String?> = _title.asStateFlow()

    init {
        scope.launch {
            if (closed || !bridge.isLoaded()) return@launch
            handle = bridge.nativeCreate(DEFAULT_COLS, DEFAULT_ROWS, options.scrollbackLines.coerceAtLeast(0))
            emitSnapshot()
        }
    }

    override fun feed(bytes: ByteArray) {
        val copy = bytes.copyOf()
        onEmulator {
            bridge.nativeWriteRemote(handle, copy)
            drainPtyWrites()
            requestSnapshot()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (PluginInputFocus.target === this) PluginInputFocus.target = null
        inputChannel.close()
        scope.launch {
            if (handle != 0L) bridge.nativeDestroy(handle)
            handle = 0L
        }
    }

    fun typeText(text: String) {
        if (text.isEmpty()) return
        onEmulator { bridge.nativeScrollToActive(handle) }
        inputChannel.trySend(text.toByteArray(Charsets.UTF_8))
    }

    // Mirrors TerminalViewModel.onHardwareKey so plugin terminals encode keys like the
    // main terminal (text keys carry their UTF-8 unless Ctrl/Alt/Super are held).
    fun typeKey(key: Int, codepoint: Int, mods: Int, action: Int, charCode: Int) {
        val isRelease = action == GhosttyKeyAction.Release
        val hasNonTextModifier = mods and NON_TEXT_MODIFIERS != 0
        val effectiveCodepoint = if (charCode > 0) charCode else codepoint
        val utf8 =
            if (effectiveCodepoint > 0 && !hasNonTextModifier && !isRelease) effectiveCodepoint.toChar().toString()
            else null
        onEmulator {
            if (!isRelease) bridge.nativeScrollToActive(handle)
            val encoded = bridge.nativeEncodeKey(handle, key, effectiveCodepoint, mods, action, utf8)
            if (encoded != null && encoded.isNotEmpty()) inputChannel.trySend(encoded)
        }
    }

    /** Bracketed when the program enabled bracketed paste, like the stock terminal. */
    fun paste(text: String) {
        if (text.isEmpty()) return
        onEmulator {
            bridge.nativeScrollToActive(handle)
            val encoded = bridge.nativeEncodePaste(handle, text)
            if (encoded != null && encoded.isNotEmpty()) inputChannel.trySend(encoded)
        }
    }

    private fun resize(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int, widthPx: Int, heightPx: Int) {
        if (cols <= 0 || rows <= 0 || cellWidth <= 0 || cellHeight <= 0) return
        onEmulator {
            this.cellWidth = cellWidth
            this.cellHeight = cellHeight
            bridge.nativeResize(handle, cols, rows, cellWidth, cellHeight)
            // The resize can queue in-band size reports (DEC 2048) for the program.
            drainPtyWrites()
            _size.value = PtySize(cols, rows, cols * cellWidth, rows * cellHeight)
            requestSnapshot(force = true)
        }
    }

    override fun setGridSize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        onEmulator {
            bridge.nativeResize(handle, cols, rows, cellWidth, cellHeight)
            requestSnapshot(force = true)
        }
    }

    private fun scroll(delta: Int, x: Float, y: Float) {
        val remote = options.onScroll
        if (remote != null) {
            remote(delta)
            return
        }
        onEmulator {
            bridge.nativeScroll(handle, delta, x, y)
            requestSnapshot(force = true)
        }
    }

    private fun reportFocus(focused: Boolean) =
        onEmulator {
            val encoded = bridge.nativeEncodeFocus(handle, focused)
            if (encoded != null && encoded.isNotEmpty()) inputChannel.trySend(encoded)
        }

    private fun applyColors(isDark: Boolean, fg: IntArray?, bg: IntArray?, cursor: IntArray?, palette: ByteArray?) =
        onEmulator {
            bridge.nativeSetColorScheme(handle, if (isDark) 1 else 0)
            bridge.nativeSetDefaultColors(handle, fg, bg, cursor, palette)
            requestSnapshot(force = true)
        }

    private fun onEmulator(block: () -> Unit) {
        if (closed) return
        scope.launch {
            if (handle == 0L || closed) return@launch
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "plugin terminal operation failed", e)
            }
        }
    }

    private fun drainPtyWrites() {
        repeat(MAX_PTY_DRAINS) {
            val writes = bridge.nativeDrainPtyWrites(handle)
            if (writes.isEmpty()) return
            inputChannel.trySend(writes)
        }
    }

    // Same throttle as the session engine: at most one snapshot per frame, with a trailing
    // snapshot so the last burst of output is always shown.
    private fun requestSnapshot(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastSnapshotAtMs
        if (force || elapsed >= SNAPSHOT_INTERVAL_MS) {
            emitSnapshot()
            return
        }
        if (snapshotScheduled) return
        snapshotScheduled = true
        scope.launch {
            delay((SNAPSHOT_INTERVAL_MS - elapsed).coerceAtLeast(1L))
            snapshotScheduled = false
            if (handle != 0L) emitSnapshot()
        }
    }

    private fun emitSnapshot() {
        val images = TerminalSnapshot.parseImages(bridge.nativeSnapshotImages(handle))
        snapshot.value = TerminalSnapshot.fromByteBuffer(bridge.nativeSnapshot(handle), images)
        bridge.nativePollTitle(handle)?.let { _title.value = it }
        lastSnapshotAtMs = System.currentTimeMillis()
    }

    @Composable
    override fun Content(modifier: Modifier, focused: Boolean, onTap: () -> Unit) {
        val context = LocalContext.current
        val colors = ChuColors.current
        val settings = remember(context) { SettingsRepository.getInstance(context) }
        val themeName by settings.themeName.collectAsStateWithLifecycle()
        val themeMode by settings.themeMode.collectAsStateWithLifecycle()
        val lightThemeName by settings.lightThemeName.collectAsStateWithLifecycle()
        val fontSize by settings.terminalFontSize.collectAsStateWithLifecycle()
        val disableAutocorrect by settings.disableAutocorrect.collectAsStateWithLifecycle()
        val resolvedTheme = resolveActiveThemeName(themeMode, themeName, lightThemeName)
        val theme = remember(context, resolvedTheme) { GhosttyThemeRegistry.getTheme(context, resolvedTheme) }
        val background = theme?.background ?: colors.background

        LaunchedEffect(theme, colors) {
            applyColors(
                isDark = background.luminance() < 0.5f,
                fg = (theme?.foreground ?: colors.textPrimary).toRgbIntArray(),
                bg = background.toRgbIntArray(),
                cursor = (theme?.cursorColor ?: colors.accent).toRgbIntArray(),
                palette = theme?.toTerminalPaletteBytes(),
            )
        }

        DisposableEffect(focused) {
            if (focused) PluginInputFocus.target = this@GhosttyPluginTerminal
            onDispose {
                if (PluginInputFocus.target === this@GhosttyPluginTerminal) PluginInputFocus.target = null
            }
        }

        // Skip the initial composition: only real focus changes are reported.
        val lastFocused = remember { booleanArrayOf(focused) }
        LaunchedEffect(focused) {
            if (lastFocused[0] != focused) reportFocus(focused)
            lastFocused[0] = focused
        }

        val current by snapshot.collectAsStateWithLifecycle()
        Box(modifier = modifier.background(background)) {
            current?.let { snap ->
                TerminalCanvas(
                    snapshot = snap,
                    modifier = Modifier.fillMaxSize(),
                    fontSizeSp = fontSize,
                    cursorColor = theme?.cursorColor ?: Color.White.copy(alpha = 0.28f),
                    cursorTextColor = theme?.cursorText,
                    selectionBackgroundColor = theme?.selectionBackground ?: colors.accent.copy(alpha = 0.45f),
                    selectionForegroundColor = theme?.selectionForeground,
                    onResize = ::resize,
                    onTap = onTap,
                    onScroll = ::scroll,
                )
            }
            if (focused) {
                // A 1dp input view owns the soft keyboard while this terminal is focused, the
                // same way the main terminal receives IME input.
                AndroidView(
                    factory = { viewContext ->
                        TerminalInputView(viewContext).apply {
                            onTerminalText = ::typeText
                            onTerminalKey = ::typeKey
                        }
                    },
                    modifier = Modifier.size(1.dp),
                    update = { view ->
                        view.disableAutocorrect = disableAutocorrect
                        if (!view.hasFocus()) {
                            view.requestFocus()
                            view.showKeyboard(view.context.getSystemService(InputMethodManager::class.java))
                        }
                    },
                )
            }
        }
    }

    private companion object {
        const val TAG = "PluginTerminal"
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val SNAPSHOT_INTERVAL_MS = 16L
        const val MAX_PTY_DRAINS = 8

        // Ctrl, Alt, Super bits in Ghostty's modifier mask; see TerminalViewModel.onHardwareKey.
        const val NON_TEXT_MODIFIERS = (1 shl 1) or (1 shl 2) or (1 shl 3)
    }
}
