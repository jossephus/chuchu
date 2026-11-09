package com.jossephus.chuchu.plugins.rex

import org.json.JSONObject

/**
 * Tracks whether this client still owns its terminal block.
 *
 * Rex only applies a resize from the block's owner, and every official client
 * takes ownership when the user interacts with it (the desktop on focus, iOS on
 * a tap). Without re-taking it, a pane the desktop grabbed keeps the desktop's
 * size while the phone draws its own grid, so output wraps at the wrong width.
 * Knowing when ownership moved lets us re-take it on the next interaction
 * instead of calling the server on every keystroke.
 */
internal class RexBlockOwnership(private val blockId: String) {
    @Volatile private var clientId: String? = null
    @Volatile private var owned = false

    val needsClaim: Boolean
        get() = !owned

    /** Records the reply to `session.own_block`, which names us as the owner. */
    fun onClaimed(result: JSONObject) {
        val blocks = result.optJSONArray("blocks") ?: return
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (block.stringOrNull("block_id") != blockId) continue
            clientId = block.stringOrNull("owner")
            owned = clientId != null
        }
    }

    /** Reads a control-connection event; only `block_owner_changed` for our block matters. */
    fun onEvent(event: JSONObject) {
        if (event.optString("type") != "session_event") return
        val inner = event.optJSONObject("payload")?.optJSONObject("event") ?: return
        if (inner.optString("type") != "block_owner_changed") return
        val payload = inner.optJSONObject("payload") ?: return
        if (payload.stringOrNull("block_id") != blockId) return
        val ours = clientId ?: return
        owned = payload.stringOrNull("owner") == ours
    }
}
