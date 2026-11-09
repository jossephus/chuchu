package com.jossephus.chuchu.service.terminal

import java.net.URI

internal fun terminalPwdPath(reported: String): String {
    val uri = runCatching { URI(reported) }.getOrNull()
    if (uri?.scheme?.equals("file", ignoreCase = true) != true) return reported
    return uri.path?.takeIf { it.isNotEmpty() } ?: reported
}
