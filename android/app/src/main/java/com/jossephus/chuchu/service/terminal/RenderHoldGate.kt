package com.jossephus.chuchu.service.terminal

internal enum class RenderHoldAction {
    /** No hold in play: paint through the normal coalescer. */
    Paint,

    /** The hold just ended: paint this frame immediately. */
    PaintForced,

    /** The program asked us to stop painting while it repaints. */
    Skip,
}

internal class RenderHoldGate {
    private companion object {
        // ghostty never ends a hold on its own, so a program that dies mid-repaint
        // would freeze the screen. Paint anyway once the data has stopped arriving.
        const val STALL_THRESHOLD_MS = 800L

        // A program that keeps streaming data while holding would keep resetting the
        // stall window, so cap the hold regardless of incoming bytes.
        const val HARD_CAP_MS = 2_000L
    }

    private var holdStartedAtMs: Long? = null
    private var lastChunkAtMs = 0L

    val isHeld: Boolean
        get() = holdStartedAtMs != null

    /** How long to wait between stall checks. */
    val stallCheckMs: Long
        get() = STALL_THRESHOLD_MS

    /** Decide what to do with the frame for this chunk of remote output. */
    fun onChunk(holdActive: Boolean, nowMs: Long): RenderHoldAction {
        if (holdActive) {
            if (holdStartedAtMs == null) holdStartedAtMs = nowMs
            lastChunkAtMs = nowMs
            return RenderHoldAction.Skip
        }
        if (holdStartedAtMs != null) {
            holdStartedAtMs = null
            // The release can land in the same chunk as the last byte of data, so the
            // final frame must be forced instead of waiting for the coalescer.
            return RenderHoldAction.PaintForced
        }
        return RenderHoldAction.Paint
    }

    /** True when the hold has run too long and the host must paint anyway. */
    fun stallDue(nowMs: Long): Boolean {
        val startedAt = holdStartedAtMs ?: return false
        if (nowMs - startedAt >= HARD_CAP_MS) return true
        return nowMs - lastChunkAtMs >= STALL_THRESHOLD_MS
    }

    fun release() {
        holdStartedAtMs = null
    }
}
