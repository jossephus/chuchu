package com.jossephus.chuchu.plugins.rex

import java.net.URLDecoder
import java.net.URLEncoder

internal object RexTargets {
    private const val PANE = "pane"
    private const val WINDOW = "window"
    private const val SESSION = "session"

    fun encode(request: RexOpenRequest): String? =
        when (request) {
            RexOpenRequest.Focused -> null
            RexOpenRequest.NewSession -> newSession()
            is RexOpenRequest.NewWindow -> newWindow(request.sessionId)
            is RexOpenRequest.Block -> pane(request.attachment)
        }

    fun pane(attachment: RexAttachment): String =
        "$PANE:${escape(attachment.sessionId)}:${escape(attachment.blockId)}"

    fun newWindow(sessionId: String): String = "$WINDOW:${escape(sessionId)}"

    fun newSession(): String = SESSION

    fun decode(target: String?): RexOpenRequest {
        val parts = target?.split(':') ?: return RexOpenRequest.Focused
        return when {
            parts == listOf(SESSION) -> RexOpenRequest.NewSession
            parts.size == 2 && parts[0] == WINDOW -> RexOpenRequest.NewWindow(unescape(parts[1]))
            parts.size == 3 && parts[0] == PANE ->
                RexOpenRequest.Block(RexAttachment(unescape(parts[1]), unescape(parts[2])))
            else -> RexOpenRequest.Focused
        }
    }

    private fun escape(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun unescape(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
}
