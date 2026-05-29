package com.jossephus.chuchu.ui.terminal

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.text.Editable
import android.text.Selection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

class TerminalInputView(context: Context) : EditText(context) {

    companion object {
        private const val LOG_TAG = "TerminalInput"
        private const val DEBUG_INPUT_LOGS = true
        private const val SUPPRESSION_CLEANUP_WINDOW_MS = 120L
    }

    var onTerminalText: ((String) -> Unit)? = null
    var onTerminalKey: ((Int, Int, Int, Int, Int) -> Unit)? = null

    @Volatile
    var suppressInput = false

    @Volatile
    private var suppressionEpoch = 0L

    @Volatile
    private var suppressionSnapshot = ""

    @Volatile
    private var suppressionDeadlineUptimeMs = 0L

    @Volatile
    private var suppressedSoftKeyUpCode = KeyEvent.KEYCODE_UNKNOWN

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

    private fun isSoftKeyboardCleanupKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DEL ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_TAB

    private fun shouldInvalidateImeMirrorForKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DEL ||
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_TAB ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_MOVE_HOME ||
            keyCode == KeyEvent.KEYCODE_MOVE_END ||
            keyCode == KeyEvent.KEYCODE_PAGE_UP ||
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
            keyCode == KeyEvent.KEYCODE_INSERT ||
            keyCode == KeyEvent.KEYCODE_ESCAPE

    private fun emitTerminalText(source: String, text: String) {
        logInput("emitText source=$source text=${describeText(text)}")
        onTerminalText?.invoke(text)
    }

    private fun emitBackspaceText(source: String) {
        logInput("emitBackspace source=$source text=<BS>")
        onTerminalText?.invoke("\u007f")
    }

    fun armInputSuppression(reason: String) {
        suppressInput = true
        suppressionSnapshot = editableText.toString()
        suppressionDeadlineUptimeMs = SystemClock.uptimeMillis() + SUPPRESSION_CLEANUP_WINDOW_MS
        val epoch = suppressionEpoch + 1
        suppressionEpoch = epoch
        logInput("armSuppression reason=$reason epoch=$epoch")
        activeInputConnection?.armSuppression()
        inputMethodManager?.let { imm ->
            post {
                if (suppressInput && suppressionEpoch == epoch) {
                    logInput("armSuppression restartInput epoch=$epoch")
                    imm.restartInput(this)
                }
            }
        }
    }

    private fun clearSuppression(reason: String) {
        if (!suppressInput && suppressionSnapshot.isEmpty()) return
        suppressInput = false
        suppressionSnapshot = ""
        suppressionDeadlineUptimeMs = 0L
    }

    private fun isSuppressionCleanupWindowActive(): Boolean =
        suppressInput && SystemClock.uptimeMillis() <= suppressionDeadlineUptimeMs

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setTextColor(android.graphics.Color.TRANSPARENT)
        isCursorVisible = false
        isFocusableInTouchMode = true
        isFocusable = true
        setSingleLine(false)
        imeOptions =
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (
            event.action == KeyEvent.ACTION_DOWN &&
                isSuppressionCleanupWindowActive() &&
                (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) != 0 &&
                isSoftKeyboardCleanupKey(keyCode)
        ) {
            suppressedSoftKeyUpCode = keyCode
            clearSuppression("onKeyDown soft-keyboard cleanup keyCode=$keyCode")
            return true
        }
        val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
        val mapped = KeyMapper.map(keyCode, event.unicodeChar, event.metaState)
        if (mapped != null && ghosttyAction != null) {
            clearSuppression("onKeyDown keyCode=$keyCode")
            if (ghosttyAction == GhosttyKeyAction.Press && shouldInvalidateImeMirrorForKey(keyCode)) {
                activeInputConnection?.invalidateImeMirror()
            }
            onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction, mapped.charCode)
            return true
        }
        val unicodeChar = event.unicodeChar
        if (unicodeChar != 0) {
            clearSuppression("onKeyDown.unicode keyCode=$keyCode")
            emitTerminalText("onKeyDown.unicode", unicodeChar.toChar().toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (
            keyCode == suppressedSoftKeyUpCode &&
                (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) != 0
        ) {
            suppressedSoftKeyUpCode = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
        val mapped = KeyMapper.map(keyCode, event.unicodeChar, event.metaState)
        if (mapped != null && ghosttyAction != null) {
            onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction, mapped.charCode)
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
            android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        outAttrs.initialSelStart = selectionStart
        outAttrs.initialSelEnd = selectionEnd

        val conn = TerminalInputConnection(this)
        activeInputConnection = conn
        return conn
    }

    private class TerminalInputConnection(
        private val view: TerminalInputView,
    ) : BaseInputConnection(view, true) {

        private val maxImeBufferChars = 1024
        private var batchDepth = 0
        private var batchBeforeText: String? = null
        private var batchHadDirectEmit = false
        private var batchEpoch = 0L
        private var pendingBatchId = 0L
        private var pendingBatchBefore: String? = null
        private var pendingBatchAfter: String? = null

        override fun getEditable(): Editable = view.editableText

        fun armSuppression() {
            clearImeBuffer(restart = false)
        }

        fun invalidateImeMirror() {
            clearImeBuffer(restart = false)
        }

        private fun clearImeBuffer(restart: Boolean) {
            val editable = getEditable()
            BaseInputConnection.removeComposingSpans(editable)
            if (editable.isNotEmpty()) editable.clear()
            Selection.setSelection(editable, 0)
            if (restart) {
                view.inputMethodManager?.restartInput(view)
            }
        }

        private fun emitTerminalTextWithNewlineMapping(source: String, text: String) {
            val parts = text.split('\n')
            parts.forEachIndexed { index, part ->
                if (part.isNotEmpty()) {
                    view.emitTerminalText("$source.part", part)
                }
                if (index < parts.lastIndex) {
                    view.emitTerminalText("$source.newline", "\r")
                }
            }
        }

        private fun emitDiff(source: String, before: String, after: String) {
            val commonLen = before.zip(after).takeWhile { it.first == it.second }.size
            repeat(before.length - commonLen) {
                view.emitBackspaceText("$source.diffDelete")
            }
            val inserted = after.substring(commonLen)
            if (inserted.isNotEmpty()) {
                emitTerminalTextWithNewlineMapping(source, inserted)
            }
        }

        private fun clearPendingBatchIfMatched(before: String, after: String) {
            if (batchDepth == 0 && before == pendingBatchBefore && after == pendingBatchAfter) {
                pendingBatchId += 1
                pendingBatchBefore = null
                pendingBatchAfter = null
            }
        }

        private fun replaceEditableRange(start: Int, end: Int, replacement: CharSequence?): Boolean {
            val editable = getEditable()
            val safeStart = start.coerceIn(0, editable.length)
            val safeEnd = end.coerceIn(safeStart, editable.length)
            val text = replacement ?: ""
            editable.replace(safeStart, safeEnd, text)
            Selection.setSelection(editable, safeStart + text.length)
            return true
        }

        private fun replaceCurrentImeSegment(replacement: CharSequence?): Boolean {
            val editable = getEditable()
            val composingStart = BaseInputConnection.getComposingSpanStart(editable)
            val composingEnd = BaseInputConnection.getComposingSpanEnd(editable)
            if (composingStart >= 0 && composingEnd >= composingStart) {
                return replaceEditableRange(composingStart, composingEnd, replacement)
            }

            val selectionStart = Selection.getSelectionStart(editable)
            val selectionEnd = Selection.getSelectionEnd(editable)
            if (selectionStart >= 0 && selectionEnd > selectionStart) {
                return replaceEditableRange(selectionStart, selectionEnd, replacement)
            }

            return replaceEditableRange(0, editable.length, replacement)
        }

        private fun consumeSuppressionIfCleanup(source: String, before: String, after: String): Boolean {
            if (!view.isSuppressionCleanupWindowActive()) return false

            val snapshot = view.suppressionSnapshot
            val isCleanupEvent =
                before == after ||
                    after.isEmpty() ||
                    after == snapshot ||
                    (snapshot.startsWith(after) && after.length <= snapshot.length)

            if (!isCleanupEvent) return false

            clearPendingBatchIfMatched(before, after)
            clearImeBuffer(restart = false)
            view.clearSuppression("$source cleanup")
            return true
        }

        private fun consumeDeleteSuppressionIfCleanup(before: String, after: String): Boolean {
            if (!view.isSuppressionCleanupWindowActive() || before != after) return false

            clearPendingBatchIfMatched(before, after)
            clearImeBuffer(restart = false)
            view.clearSuppression("deleteSurroundingText cleanup")
            return true
        }

        private fun mutateEditableAndEmit(source: String, op: () -> Boolean): Boolean {
            val before = getEditable().toString()
            val ok = op()
            val after = getEditable().toString()

            if (consumeSuppressionIfCleanup(source, before, after)) {
                return ok
            }

            if (view.suppressInput) {
                view.clearSuppression("$source real input")
            }

            emitDiff(source, before, after)
            clearPendingBatchIfMatched(before, after)
            if (batchDepth > 0 && before != after) {
                batchHadDirectEmit = true
            }

            if (after.contains('\n') || after.contains('\r')) {
                clearImeBuffer(restart = true)
            } else if (after.length > maxImeBufferChars) {
                clearImeBuffer(restart = true)
            }

            return ok
        }

        private fun emitBatchDiffIfNeeded(source: String, before: String, after: String) {
            if (before == after) return
            if (consumeSuppressionIfCleanup(source, before, after)) {
                return
            }
            if (view.suppressInput) {
                view.clearSuppression("$source real input")
            }
            emitDiff(source, before, after)
            if (after.contains('\n') || after.contains('\r')) {
                clearImeBuffer(restart = true)
            } else if (after.length > maxImeBufferChars) {
                clearImeBuffer(restart = true)
            }
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            return mutateEditableAndEmit("commitText") {
                super.commitText(text ?: "", newCursorPosition)
            }
        }

        override fun commitCompletion(text: CompletionInfo?): Boolean {
            return mutateEditableAndEmit("commitCompletion") {
                replaceCurrentImeSegment(text?.text)
            }
        }

        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
            return mutateEditableAndEmit("commitCorrection") {
                val oldTextLength = correctionInfo?.oldText?.length ?: 0
                val offset = correctionInfo?.offset ?: -1
                val newText = correctionInfo?.newText
                if (offset >= 0) {
                    replaceEditableRange(offset, offset + oldTextLength, newText)
                } else {
                    replaceCurrentImeSegment(newText)
                }
            }
        }

        override fun beginBatchEdit(): Boolean {
            if (batchDepth == 0) {
                batchBeforeText = getEditable().toString()
                batchHadDirectEmit = false
                batchEpoch += 1
            }
            batchDepth += 1
            return super.beginBatchEdit()
        }

        override fun endBatchEdit(): Boolean {
            val ok = super.endBatchEdit()
            if (batchDepth > 0) {
                batchDepth -= 1
            }
            if (batchDepth == 0) {
                val before = batchBeforeText
                val after = getEditable().toString()
                val epoch = batchEpoch
                if (before != null) {
                    val runId = pendingBatchId + 1
                    pendingBatchId = runId
                    pendingBatchBefore = before
                    pendingBatchAfter = after
                    view.post {
                        if (
                            batchDepth == 0 &&
                                batchEpoch == epoch &&
                                !batchHadDirectEmit &&
                                pendingBatchId == runId
                        ) {
                            emitBatchDiffIfNeeded("batchEdit", before, after)
                        }
                    }
                }
                batchBeforeText = null
            }
            return ok
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            return mutateEditableAndEmit("setComposingText") {
                super.setComposingText(text ?: "", newCursorPosition)
            }
        }

        override fun finishComposingText(): Boolean {
            return super.finishComposingText()
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            return super.setComposingRegion(start, end)
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            return super.setSelection(start, end)
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            val editable = getEditable()
            return ExtractedText().apply {
                text = editable.toString()
                startOffset = 0
                partialStartOffset = -1
                partialEndOffset = -1
                selectionStart = Selection.getSelectionStart(editable).coerceAtLeast(0)
                selectionEnd = Selection.getSelectionEnd(editable).coerceAtLeast(0)
            }
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val before = getEditable().toString()
            val ok = super.deleteSurroundingText(beforeLength, afterLength)
            val after = getEditable().toString()

            if (consumeDeleteSuppressionIfCleanup(before, after)) {
                return ok
            }

            if (view.suppressInput) {
                view.clearSuppression("deleteSurroundingText real input")
            }

            if (before != after) {
                emitDiff("deleteSurroundingText", before, after)
            } else {
                repeat(beforeLength) {
                    view.emitBackspaceText("deleteSurroundingText.before")
                }
                repeat(afterLength) {
                    view.emitTerminalText("deleteSurroundingText.after", "\u001b[3~")
                }
            }
            clearPendingBatchIfMatched(before, after)
            if (batchDepth > 0 && before != after) {
                batchHadDirectEmit = true
            }
            return ok
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (
                view.isSuppressionCleanupWindowActive() &&
                    (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) != 0 &&
                    view.isSoftKeyboardCleanupKey(event.keyCode)
            ) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    view.suppressedSoftKeyUpCode = event.keyCode
                    view.clearSuppression("sendKeyEvent soft-keyboard cleanup keyCode=${event.keyCode}")
                }
                return true
            }
            val ghosttyAction = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount)
            if (ghosttyAction != null) {
                val mapped = KeyMapper.map(event.keyCode, event.unicodeChar, event.metaState)
                if (mapped != null) {
                    if (ghosttyAction == GhosttyKeyAction.Press && view.shouldInvalidateImeMirrorForKey(event.keyCode)) {
                        invalidateImeMirror()
                    }
                    view.onTerminalKey?.invoke(mapped.key, mapped.codepoint, mapped.mods, ghosttyAction, mapped.charCode)
                    return true
                }
            }
            return super.sendKeyEvent(event)
        }
    }
}
