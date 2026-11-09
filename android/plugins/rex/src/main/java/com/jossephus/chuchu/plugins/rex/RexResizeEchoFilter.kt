package com.jossephus.chuchu.plugins.rex

/**
 * Drops the server's late echoes of sizes this client already moved past.
 *
 * Rex reports every pane resize back on the data stream, including the ones we
 * asked for. The keyboard animates the canvas through many sizes, so the echoes
 * arrive after the grid has already reached its final size; applying them would
 * replay the animation a second late and draw the old grid under the toolbar.
 *
 * A resize we never requested (another client, or the server clamping our
 * size) is still applied, and so is the echo of the latest request, which puts
 * the grid back after a snapshot restored it at the server's old size.
 */
internal class RexResizeEchoFilter {
    private val sent = ArrayDeque<Pair<Int, Int>>()

    @Synchronized
    fun onSent(columns: Int, rows: Int) {
        sent.addLast(columns to rows)
        while (sent.size > MAX_TRACKED) sent.removeFirst()
    }

    /** Returns true when a `resize` event from the server should reach the terminal. */
    @Synchronized
    fun shouldApply(columns: Int, rows: Int): Boolean {
        val size = columns to rows
        val at = sent.indexOf(size)
        if (at < 0) return true
        val isLatest = at == sent.lastIndex
        // Echoes arrive in request order, so everything up to this one is answered.
        repeat(at) { sent.removeFirst() }
        if (!isLatest) sent.removeFirst()
        return isLatest
    }

    private companion object {
        // Comfortably more than one keyboard animation's worth of row changes.
        const val MAX_TRACKED = 64
    }
}
