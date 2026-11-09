package com.jossephus.chuchu.service.terminal

import java.net.URI

internal fun terminalPwdPath(reported: String): String {
    val uri = runCatching { URI(reported) }.getOrNull()
    if (uri?.scheme?.equals("file", ignoreCase = true) != true) return reported
    return uri.path?.takeIf { it.isNotEmpty() } ?: reported
}

internal fun pwdLabel(path: String, maxChars: Int = 40): String =
    if (path.length <= maxChars) path else "…" + path.takeLast(maxChars - 1)
