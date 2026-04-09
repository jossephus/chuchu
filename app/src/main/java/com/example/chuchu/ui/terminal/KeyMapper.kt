package com.example.chuchu.ui.terminal

import android.view.KeyEvent

object KeyMapper {
    fun map(keyCode: Int, codepoint: Int, metaState: Int): MappedKey? {
        val mods = translateMods(metaState)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> MappedKey(key = 265, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_DPAD_DOWN -> MappedKey(key = 264, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_DPAD_LEFT -> MappedKey(key = 263, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_DPAD_RIGHT -> MappedKey(key = 262, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_PAGE_UP -> MappedKey(key = 266, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_PAGE_DOWN -> MappedKey(key = 267, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_MOVE_HOME -> MappedKey(key = 268, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_MOVE_END -> MappedKey(key = 269, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_INSERT -> MappedKey(key = 260, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_FORWARD_DEL -> MappedKey(key = 261, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_DEL -> MappedKey(key = 259, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_TAB -> MappedKey(key = 258, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_ESCAPE -> MappedKey(key = 256, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_ENTER -> MappedKey(key = 257, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F1 -> MappedKey(key = 290, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F2 -> MappedKey(key = 291, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F3 -> MappedKey(key = 292, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F4 -> MappedKey(key = 293, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F5 -> MappedKey(key = 294, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F6 -> MappedKey(key = 295, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F7 -> MappedKey(key = 296, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F8 -> MappedKey(key = 297, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F9 -> MappedKey(key = 298, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F10 -> MappedKey(key = 299, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F11 -> MappedKey(key = 300, codepoint = 0, mods = mods)
            KeyEvent.KEYCODE_F12 -> MappedKey(key = 301, codepoint = 0, mods = mods)
            else -> {
                if (codepoint == 0) return null
                MappedKey(key = 0, codepoint = codepoint, mods = mods)
            }
        }
    }

    private fun translateMods(metaState: Int): Int {
        var mods = 0
        if (metaState and KeyEvent.META_SHIFT_ON != 0) mods = mods or 1
        if (metaState and KeyEvent.META_ALT_ON != 0) mods = mods or 2
        if (metaState and KeyEvent.META_CTRL_ON != 0) mods = mods or 4
        if (metaState and KeyEvent.META_META_ON != 0) mods = mods or 8
        return mods
    }
}

data class MappedKey(
    val key: Int,
    val codepoint: Int,
    val mods: Int,
)
