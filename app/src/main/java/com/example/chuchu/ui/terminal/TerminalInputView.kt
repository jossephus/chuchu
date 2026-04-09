package com.example.chuchu.ui.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

class TerminalInputView(context: Context) : EditText(context) {

    var onTerminalText: ((String) -> Unit)? = null
    var onTerminalKey: ((Int, KeyEvent?) -> Unit)? = null

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

    fun showKeyboard(inputMethodManager: InputMethodManager?) {
        if (inputMethodManager == null) return
        if (!hasFocus()) {
            requestFocus()
            requestFocusFromTouch()
        }
        post {
            inputMethodManager.restartInput(this)
            inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        return TerminalInputConnection(this)
    }

    private class TerminalInputConnection(
        private val view: TerminalInputView,
    ) : BaseInputConnection(view, false) {

        private var composing = ""

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val str = text?.toString() ?: return true
            if (str.isEmpty()) return true

            // Delete whatever composing text we already sent to the terminal
            repeat(composing.length) {
                view.onTerminalText?.invoke("\u007f")
            }
            composing = ""

            val parts = str.split('\n')
            parts.forEachIndexed { index, part ->
                if (part.isNotEmpty()) {
                    view.onTerminalText?.invoke(part)
                }
                if (index < parts.lastIndex) {
                    view.onTerminalText?.invoke("\r")
                }
            }
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val newText = text?.toString() ?: ""
            val commonLen = composing.zip(newText).takeWhile { it.first == it.second }.size

            // Delete removed composing characters
            repeat(composing.length - commonLen) {
                view.onTerminalText?.invoke("\u007f")
            }
            // Send newly added composing characters
            if (newText.length > commonLen) {
                view.onTerminalText?.invoke(newText.substring(commonLen))
            }

            composing = newText
            return true
        }

        override fun finishComposingText(): Boolean {
            // Composing chars already sent — just clear tracking
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) {
                view.onTerminalText?.invoke("\u007f")
            }
            repeat(afterLength) {
                view.onTerminalText?.invoke("\u001b[3~")
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        view.onTerminalText?.invoke("\u007f")
                        return true
                    }
                    KeyEvent.KEYCODE_ENTER -> {
                        view.onTerminalText?.invoke("\r")
                        return true
                    }
                    KeyEvent.KEYCODE_FORWARD_DEL -> {
                        view.onTerminalText?.invoke("\u001b[3~")
                        return true
                    }
                }

                val unicodeChar = event.unicodeChar
                if (unicodeChar != 0) {
                    view.onTerminalText?.invoke(unicodeChar.toChar().toString())
                    return true
                }
            }
            return super.sendKeyEvent(event)
        }
    }
}
