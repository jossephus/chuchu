package com.jossephus.chuchu.ui.terminal

import android.view.KeyEvent

object KeyMapper {
    fun map(keyCode: Int, codepoint: Int, metaState: Int): MappedKey? {
        val mods = translateMods(metaState)
        return when (keyCode) {
            // Navigation and editing keys
            KeyEvent.KEYCODE_DPAD_UP -> MappedKey(key = TerminalSpecialKey.Up.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_DPAD_DOWN -> MappedKey(key = TerminalSpecialKey.Down.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_DPAD_LEFT -> MappedKey(key = TerminalSpecialKey.Left.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_DPAD_RIGHT -> MappedKey(key = TerminalSpecialKey.Right.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_PAGE_UP -> MappedKey(key = TerminalSpecialKey.PageUp.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_PAGE_DOWN -> MappedKey(key = TerminalSpecialKey.PageDown.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_MOVE_HOME -> MappedKey(key = TerminalSpecialKey.Home.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_MOVE_END -> MappedKey(key = TerminalSpecialKey.End.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_INSERT -> MappedKey(key = TerminalSpecialKey.Insert.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_FORWARD_DEL -> MappedKey(key = TerminalSpecialKey.Delete.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_DEL -> MappedKey(key = GhosttyKey.backspace, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_TAB -> MappedKey(key = TerminalSpecialKey.Tab.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_ESCAPE -> MappedKey(key = TerminalSpecialKey.Escape.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_ENTER -> MappedKey(key = GhosttyKey.enter, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_SPACE -> MappedKey(key = GhosttyKey.space, codepoint = 0, mods = mods)

            // Function keys
            KeyEvent.KEYCODE_F1 -> MappedKey(key = TerminalSpecialKey.F1.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F2 -> MappedKey(key = TerminalSpecialKey.F2.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F3 -> MappedKey(key = TerminalSpecialKey.F3.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F4 -> MappedKey(key = TerminalSpecialKey.F4.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F5 -> MappedKey(key = TerminalSpecialKey.F5.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F6 -> MappedKey(key = TerminalSpecialKey.F6.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F7 -> MappedKey(key = TerminalSpecialKey.F7.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F8 -> MappedKey(key = TerminalSpecialKey.F8.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F9 -> MappedKey(key = TerminalSpecialKey.F9.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F10 -> MappedKey(key = TerminalSpecialKey.F10.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F11 -> MappedKey(key = TerminalSpecialKey.F11.engineKey, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F12 -> MappedKey(key = TerminalSpecialKey.F12.engineKey, codepoint = 0, mods = mods)

            // Letter keys (A-Z) — mapped to Ghostty's physical key codes
            KeyEvent.KEYCODE_A -> MappedKey(key = GhosttyKey.keyA, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_B -> MappedKey(key = GhosttyKey.keyB, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_C -> MappedKey(key = GhosttyKey.keyC, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_D -> MappedKey(key = GhosttyKey.keyD, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_E -> MappedKey(key = GhosttyKey.keyE, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F -> MappedKey(key = GhosttyKey.keyF, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_G -> MappedKey(key = GhosttyKey.keyG, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_H -> MappedKey(key = GhosttyKey.keyH, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_I -> MappedKey(key = GhosttyKey.keyI, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_J -> MappedKey(key = GhosttyKey.keyJ, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_K -> MappedKey(key = GhosttyKey.keyK, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_L -> MappedKey(key = GhosttyKey.keyL, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_M -> MappedKey(key = GhosttyKey.keyM, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_N -> MappedKey(key = GhosttyKey.keyN, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_O -> MappedKey(key = GhosttyKey.keyO, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_P -> MappedKey(key = GhosttyKey.keyP, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_Q -> MappedKey(key = GhosttyKey.keyQ, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_R -> MappedKey(key = GhosttyKey.keyR, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_S -> MappedKey(key = GhosttyKey.keyS, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_T -> MappedKey(key = GhosttyKey.keyT, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_U -> MappedKey(key = GhosttyKey.keyU, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_V -> MappedKey(key = GhosttyKey.keyV, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_W -> MappedKey(key = GhosttyKey.keyW, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_X -> MappedKey(key = GhosttyKey.keyX, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_Y -> MappedKey(key = GhosttyKey.keyY, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_Z -> MappedKey(key = GhosttyKey.keyZ, codepoint = 0, mods = mods)

            // Digit keys (0-9) — mapped to Ghostty's physical key codes
            KeyEvent.KEYCODE_0 -> MappedKey(key = GhosttyKey.digit0, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_1 -> MappedKey(key = GhosttyKey.digit1, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_2 -> MappedKey(key = GhosttyKey.digit2, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_3 -> MappedKey(key = GhosttyKey.digit3, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_4 -> MappedKey(key = GhosttyKey.digit4, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_5 -> MappedKey(key = GhosttyKey.digit5, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_6 -> MappedKey(key = GhosttyKey.digit6, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_7 -> MappedKey(key = GhosttyKey.digit7, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_8 -> MappedKey(key = GhosttyKey.digit8, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_9 -> MappedKey(key = GhosttyKey.digit9, codepoint = 0, mods = mods)

            // Punctuation keys
            KeyEvent.KEYCODE_GRAVE -> MappedKey(key = GhosttyKey.backquote, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_MINUS -> MappedKey(key = GhosttyKey.minus, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_EQUALS -> MappedKey(key = GhosttyKey.equal, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_LEFT_BRACKET -> MappedKey(key = GhosttyKey.bracketLeft, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_RIGHT_BRACKET -> MappedKey(key = GhosttyKey.bracketRight, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_BACKSLASH -> MappedKey(key = GhosttyKey.backslash, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_SEMICOLON -> MappedKey(key = GhosttyKey.semicolon, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_APOSTROPHE -> MappedKey(key = GhosttyKey.quote, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_COMMA -> MappedKey(key = GhosttyKey.comma, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_PERIOD -> MappedKey(key = GhosttyKey.period, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_SLASH -> MappedKey(key = GhosttyKey.slash, codepoint = 0, mods = mods)

            else -> {
                if (codepoint == 0) return null
                MappedKey(key = 0, codepoint = codepoint, mods = mods)
            }
        }
    }

    private fun translateMods(metaState: Int): Int {
        // Ghostty KeyMods packed struct layout:
        //   bit 0 = shift, bit 1 = ctrl, bit 2 = alt, bit 3 = super
        var mods = 0
        if (metaState and KeyEvent.META_SHIFT_ON != 0) mods = mods or (1 shl 0) // shift
        if (metaState and KeyEvent.META_CTRL_ON != 0) mods = mods or (1 shl 1) // ctrl
        if (metaState and KeyEvent.META_ALT_ON != 0) mods = mods or (1 shl 2)  // alt
        if (metaState and KeyEvent.META_META_ON != 0) mods = mods or (1 shl 3)  // super
        return mods
    }
}

data class MappedKey(
    val key: Int,
    val codepoint: Int,
    val mods: Int,
)
