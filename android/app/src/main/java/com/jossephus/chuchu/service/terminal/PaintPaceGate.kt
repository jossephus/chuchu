package com.jossephus.chuchu.service.terminal

/**
 * Paces repaints while remote output arrives faster than the app can draw it.
 *
 * A burst — a multiplexer replaying scrollback, a build log, `yes` — would otherwise repaint at the
 * interactive interval, so most of those frames are intermediate states nobody reads and the phone
 * spends its time serialising terminal snapshots. Above [FLOOD_BYTES_PER_WINDOW] bytes per
 * [WINDOW_MS] the gate raises the interval to [FLOOD_PAINT_INTERVAL_MS]. It is a floor, never a
 * freeze: a program that streams forever still gets a frame every [FLOOD_PAINT_INTERVAL_MS].
 *
 * The burst state expires on its own after [FLOOD_HOLD_MS] without a burst, so nothing has to be
 * reset when output settles, and a stream that straddles a window boundary does not flap back to
 * the interactive interval in between.
 */
internal class PaintPaceGate {
    private companion object {
        const val WINDOW_MS = 100L

        // Several full-screen repaints inside one window: past this point frames are thrown away
        // faster than a reader can follow them.
        const val FLOOD_BYTES_PER_WINDOW = 16 * 1024

        // Longer than WINDOW_MS on purpose: a stream that keeps hitting the threshold must not
        // drop back to the interactive interval at every window boundary.
        const val FLOOD_HOLD_MS = 200L

        const val FLOOD_PAINT_INTERVAL_MS = 150L
    }

    private var windowStartedAtMs = 0L
    private var bytesInWindow = 0
    private var floodedUntilMs = 0L

    fun onBytes(count: Int, nowMs: Long) {
        if (nowMs - windowStartedAtMs >= WINDOW_MS) {
            windowStartedAtMs = nowMs
            bytesInWindow = 0
        }
        bytesInWindow += count
        if (bytesInWindow >= FLOOD_BYTES_PER_WINDOW) floodedUntilMs = nowMs + FLOOD_HOLD_MS
    }

    /** True while the recent output rate is one a reader cannot follow. */
    fun isFlooded(nowMs: Long): Boolean = nowMs < floodedUntilMs

    /** Paint interval floor for right now: no floor while calm, the flood interval while flooded. */
    fun paintIntervalFloorMs(nowMs: Long): Long = if (isFlooded(nowMs)) FLOOD_PAINT_INTERVAL_MS else 0L
}
