package com.jossephus.chuchu.ui.terminal

/**
 * Ghostty input.Key enum values (c_int backed, sequential from 0).
 * These are layout-independent physical key codes based on the W3C standard.
 * See: https://www.w3.org/TR/uievents-code
 *
 * IMPORTANT: These values must match the Ghostty Key enum declaration order exactly.
 * Reference: ghostty-vt src/input/key.zig
 */
object GhosttyKey {
    const val unidentified = 0
    const val backquote = 1
    const val backslash = 2
    const val bracketLeft = 3
    const val bracketRight = 4
    const val comma = 5
    const val digit0 = 6
    const val digit1 = 7
    const val digit2 = 8
    const val digit3 = 9
    const val digit4 = 10
    const val digit5 = 11
    const val digit6 = 12
    const val digit7 = 13
    const val digit8 = 14
    const val digit9 = 15
    const val equal = 16
    const val intlBackslash = 17
    const val intlRo = 18
    const val intlYen = 19
    const val keyA = 20
    const val keyB = 21
    const val keyC = 22
    const val keyD = 23
    const val keyE = 24
    const val keyF = 25
    const val keyG = 26
    const val keyH = 27
    const val keyI = 28
    const val keyJ = 29
    const val keyK = 30
    const val keyL = 31
    const val keyM = 32
    const val keyN = 33
    const val keyO = 34
    const val keyP = 35
    const val keyQ = 36
    const val keyR = 37
    const val keyS = 38
    const val keyT = 39
    const val keyU = 40
    const val keyV = 41
    const val keyW = 42
    const val keyX = 43
    const val keyY = 44
    const val keyZ = 45
    const val minus = 46
    const val period = 47
    const val quote = 48
    const val semicolon = 49
    const val slash = 50
    const val altLeft = 51
    const val altRight = 52
    const val backspace = 53
    const val capsLock = 54
    const val contextMenu = 55
    const val controlLeft = 56
    const val controlRight = 57
    const val enter = 58
    const val metaLeft = 59
    const val metaRight = 60
    const val shiftLeft = 61
    const val shiftRight = 62
    const val space = 63
    const val tab = 64
    const val convert = 65
    const val kanaMode = 66
    const val nonConvert = 67
    const val delete = 68
    const val end = 69
    const val help = 70
    const val home = 71
    const val insert = 72
    const val pageDown = 73
    const val pageUp = 74
    const val arrowDown = 75
    const val arrowLeft = 76
    const val arrowRight = 77
    const val arrowUp = 78
    const val numLock = 79
    const val f1 = 120
    const val f2 = 121
    const val f3 = 122
    const val f4 = 123
    const val f5 = 124
    const val f6 = 125
    const val f7 = 126
    const val f8 = 127
    const val f9 = 128
    const val f10 = 129
    const val f11 = 130
    const val f12 = 131
    const val escape = 145
}

/**
 * Map a Unicode character to its Ghostty physical key code,
 * or null if there is no direct mapping (uncommon punctuation, etc.).
 */
fun Char.toGhosttyKey(): Int? = when (this) {
    'a', 'A' -> GhosttyKey.keyA
    'b', 'B' -> GhosttyKey.keyB
    'c', 'C' -> GhosttyKey.keyC
    'd', 'D' -> GhosttyKey.keyD
    'e', 'E' -> GhosttyKey.keyE
    'f', 'F' -> GhosttyKey.keyF
    'g', 'G' -> GhosttyKey.keyG
    'h', 'H' -> GhosttyKey.keyH
    'i', 'I' -> GhosttyKey.keyI
    'j', 'J' -> GhosttyKey.keyJ
    'k', 'K' -> GhosttyKey.keyK
    'l', 'L' -> GhosttyKey.keyL
    'm', 'M' -> GhosttyKey.keyM
    'n', 'N' -> GhosttyKey.keyN
    'o', 'O' -> GhosttyKey.keyO
    'p', 'P' -> GhosttyKey.keyP
    'q', 'Q' -> GhosttyKey.keyQ
    'r', 'R' -> GhosttyKey.keyR
    's', 'S' -> GhosttyKey.keyS
    't', 'T' -> GhosttyKey.keyT
    'u', 'U' -> GhosttyKey.keyU
    'v', 'V' -> GhosttyKey.keyV
    'w', 'W' -> GhosttyKey.keyW
    'x', 'X' -> GhosttyKey.keyX
    'y', 'Y' -> GhosttyKey.keyY
    'z', 'Z' -> GhosttyKey.keyZ
    '0' -> GhosttyKey.digit0
    '1' -> GhosttyKey.digit1
    '2' -> GhosttyKey.digit2
    '3' -> GhosttyKey.digit3
    '4' -> GhosttyKey.digit4
    '5' -> GhosttyKey.digit5
    '6' -> GhosttyKey.digit6
    '7' -> GhosttyKey.digit7
    '8' -> GhosttyKey.digit8
    '9' -> GhosttyKey.digit9
    ' ' -> GhosttyKey.space
    '`' -> GhosttyKey.backquote
    '-' -> GhosttyKey.minus
    '=' -> GhosttyKey.equal
    '[' -> GhosttyKey.bracketLeft
    ']' -> GhosttyKey.bracketRight
    '\\' -> GhosttyKey.backslash
    ';' -> GhosttyKey.semicolon
    '\'' -> GhosttyKey.quote
    ',' -> GhosttyKey.comma
    '.' -> GhosttyKey.period
    '/' -> GhosttyKey.slash
    else -> null
}