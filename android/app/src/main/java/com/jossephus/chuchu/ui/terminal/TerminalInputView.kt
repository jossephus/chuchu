package com.jossephus.chuchu.ui.terminal

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

class TerminalInputView(context: Context) : EditText(context) {

    companion object {
        private const val LOG_TAG = "TerminalInput"
        private const val DEBUG_INPUT_LOGS = false
    }

    var onTerminalText: ((String) -> Unit)? = null
    var onTerminalKey: ((Int, Int, Int, Int) -> Unit)? = null

    /**
     * When true, suppress IME text input (used to prevent double-sends
     * when accessory-bar virtual keys like Tab are tapped).
     */
    @Volatile
    var suppressInput = false

    /** Active input connection for composing-state resets. */
    private var activeInputConnection: TerminalInputConnection? = null

    /** Cached InputMethodManager for IME restarts. */
    private var inputMethodManager: InputMethodManager? = null

    private fun logInput(message: String) {
        if (!DEBUG_INPUT_LOGS) return
        Log.d(LOG_TAG, message)
    }

    private fun describeText(text: String): String = buildString {
        text.forEach { char ->
            when (char) {
                '\u001b' -> append("<ESC>")
                '\t' -> append("<TAB>")
                '\r' -> append("<CR>")
                '\n' -> append("<LF>")
                '\u007f' -> append("<BS>")
                else -> append(char)
            }
        }
    }

    private fun describeKeyEvent(event: KeyEvent): String = buildString {
        append("action=")
        append(event.action)
        append(" keyCode=")
        append(event.keyCode)
        append(" unicode=")
        append(event.unicodeChar)
        append(" repeat=")
        append(event.repeatCount)
        append(" meta=")
        append(event.metaState)
    }

    private fun emitTerminalText(source: String, text: String) {
        logInput("emit source=$source text=${describeText(text)} suppress=$suppressInput")
        onTerminalText?.invoke(text)
    }

    fun armInputSuppression(reason: String) {
        suppressInput = true
        logInput("arm suppression reason=$reason")
        activeInputConnection?.resetComposing()
        inputMethodManager?.let { imm ->
            post {
                logInput("arm suppression - restarting IME input")
                imm.restartInput(this)
            }
        }
    }

    private fun clearSuppression(reason: String) {
        if (!suppressInput) return
        logInput("clear suppression reason=$reason")
        suppressInput = false
    }

    private fun consumeSuppressionIfImeCleanup(
        source: String,
        incomingText: String,
        composing: String,
    ): Boolean {
        if (!suppressInput) return false

        val isCleanupEvent = incomingText.isEmpty() ||
            incomingText == composing ||
            (composing.isNotEmpty() && composing.startsWith(incomingText) && incomingText.length <= composing.length)

        return if (isCleanupEvent) {
            logInput(
                "suppression consumed source=$source incoming=${describeText(incomingText)} composing=${describeText(composing)}",
            )
            suppressInput = false
            true
        } else {
            logInput(
                "suppression bypass source=$source incoming=${describeText(incomingText)} composing=${describeText(composing)}",
            )
            clearSuppression("$source real input")
            false
        }
    }

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setTextColor(android.graphics.Color.TRANSPARENT)
        isCursorVisible = false
        isFocusableInTouchMode = true
        isFocusable = true
        setSingleLine(false)
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        logInput("onKeyDown ${describeKeyEvent(event)} suppress=$suppressInput")
        if (event.action == KeyEvent.ACTION_DOWN) {
            clearSuppression("onKeyDown keyCode=$keyCode")
        }
        val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
        val mapped = KeyMapper.map(keyCode, event.unicodeChar, event.metaState)
        if (mapped != null && ghosttyAction != null) {
            onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction)
            return true
        }
        val unicodeChar = event.unicodeChar
        if (unicodeChar != 0) {
            emitTerminalText("onKeyDown.unicode", unicodeChar.toChar().toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        logInput("onKeyUp ${describeKeyEvent(event)} suppress=$suppressInput")
        val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
        val mapped = KeyMapper.map(keyCode, event.unicodeChar, event.metaState)
        if (mapped != null && ghosttyAction != null) {
            onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction)
            return true
        }
        if (event.unicodeChar != 0) return true
        return super.onKeyUp(keyCode, event)
    }

    fun showKeyboard(imm: InputMethodManager?) {
        if (imm == null) return
        inputMethodManager = imm
        if (!hasFocus()) {
            requestFocus()
            requestFocusFromTouch()
        }
        post {
            logInput("showKeyboard restartInput suppress=$suppressInput")
            imm.restartInput(this)
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        val conn = TerminalInputConnection(this)
        activeInputConnection = conn
        return conn
    }

    private class TerminalInputConnection(
        private val view: TerminalInputView,
    ) : BaseInputConnection(view, false) {

        private var composing = ""

        /** Reset composing state without emitting reconciliation backspaces. */
        fun resetComposing() {
            view.logInput("resetComposing was=${view.describeText(composing)}")
            composing = ""
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val str = text?.toString() ?: return true
            view.logInput(
                "commitText text=${view.describeText(str)} cursor=$newCursorPosition suppress=${view.suppressInput} composing=${view.describeText(composing)}",
            )
            if (view.consumeSuppressionIfImeCleanup("commitText", str, composing)) {
                composing = ""
                return true
            }
            if (str.isEmpty()) return true

            val commonLen = composing.zip(str).takeWhile { it.first == it.second }.size

            repeat(composing.length - commonLen) {
                view.emitTerminalText("commitText.clearComposing", "\u007f")
            }

            val parts = str.substring(commonLen).split('\n')
            parts.forEachIndexed { index, part ->
                if (part.isNotEmpty()) {
                    view.emitTerminalText("commitText.part", part)
                }
                if (index < parts.lastIndex) {
                    view.emitTerminalText("commitText.newline", "\r")
                }
            }

            composing = ""
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val newText = text?.toString() ?: ""
            view.logInput(
                "setComposingText text=${view.describeText(newText)} cursor=$newCursorPosition suppress=${view.suppressInput} composing=${view.describeText(composing)}",
            )
            if (view.consumeSuppressionIfImeCleanup("setComposingText", newText, composing)) {
                composing = ""
                return true
            }
            val commonLen = composing.zip(newText).takeWhile { it.first == it.second }.size

            repeat(composing.length - commonLen) {
                view.emitTerminalText("setComposingText.delete", "\u007f")
            }
            if (newText.length > commonLen) {
                view.emitTerminalText("setComposingText.append", newText.substring(commonLen))
            }

            composing = newText
            return true
        }

        override fun finishComposingText(): Boolean {
            view.logInput("finishComposingText composing=${view.describeText(composing)} suppress=${view.suppressInput}")
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            view.logInput("deleteSurroundingText before=$beforeLength after=$afterLength suppress=${view.suppressInput}")
            repeat(beforeLength) {
                view.emitTerminalText("deleteSurroundingText.before", "\u007f")
            }
            repeat(afterLength) {
                view.emitTerminalText("deleteSurroundingText.after", "\u001b[3~")
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
            if (ghosttyAction != null) {
                val mapped = KeyMapper.map(event.keyCode, event.unicodeChar, event.metaState)
                if (mapped != null) {
                    view.onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction)
                    return true
                }
            }
            return super.sendKeyEvent(event)
        }
    }
}
