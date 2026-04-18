package com.jossephus.chuchu.ui.terminal

import android.view.KeyEvent

object GhosttyKeyAction {
    const val Release = 0
    const val Press = 1
    const val Repeat = 2

    fun fromAndroid(action: Int, repeatCount: Int = 0): Int? = when (action) {
        KeyEvent.ACTION_DOWN -> if (repeatCount > 0) Repeat else Press
        KeyEvent.ACTION_UP -> Release
        else -> null
    }
}
