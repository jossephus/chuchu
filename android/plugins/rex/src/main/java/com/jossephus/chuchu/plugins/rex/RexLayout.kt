package com.jossephus.chuchu.plugins.rex

import org.json.JSONObject

internal object RexLayout {
    private const val TERMINAL_CREATOR = "com.superlogical.terminal"
    private const val WINDOW_ID_SUFFIX = 8

    fun pick(view: JSONObject): String? {
        val windows = windows(view)
        val activeId = view.stringOrNull("active_window_id")
        for (window in windows.sortedByDescending { it.stringOrNull("window_id") == activeId }) {
            val terminals = windowTerminals(window).map { it.first }
            if (terminals.isEmpty()) continue
            return listOfNotNull(window.stringOrNull("zoomed_block_id"), window.stringOrNull("focused_block_id"))
                .firstOrNull { it in terminals }
                ?: terminals.first()
        }
        return null
    }

    fun contains(view: JSONObject, blockId: String): Boolean = blockId in terminalBlockIds(view)

    fun terminalBlockIds(view: JSONObject): List<String> = terminalBlocks(view).map { it.first }

    /** Terminal blocks whose window has a name the user set, not Rex's default "Window <id>". */
    fun customNamedBlockIds(view: JSONObject): List<String> =
        windows(view).flatMap { window ->
            val label = window.stringOrNull("label")
            if (label == null || label == defaultWindowName(window)) emptyList() else windowTerminals(window).map { it.first }
        }

    /** Terminal blocks whose shown name another block also has, so the list needs more to tell them apart. */
    fun sharedNameBlockIds(view: JSONObject): List<String> {
        val blocks = terminalBlocks(view)
        val counts = blocks.groupingBy { it.second }.eachCount()
        return blocks.filter { (counts[it.second] ?: 0) > 1 }.map { it.first }
    }

    fun terminalBlocks(view: JSONObject): List<Pair<String, String?>> = windows(view).flatMap(::windowTerminals)

    private fun windows(view: JSONObject): List<JSONObject> {
        val windows = view.optJSONArray("windows") ?: return emptyList()
        return (0 until windows.length()).mapNotNull { windows.optJSONObject(it) }
    }

    private fun defaultWindowName(window: JSONObject): String? =
        window.stringOrNull("window_id")?.let { "Window " + it.takeLast(WINDOW_ID_SUFFIX) }

    /**
     * Terminal blocks in [window], each paired with the name to show for it. That is the
     * window's label, as the official clients list windows by name, and Rex's own
     * "Window <id>" form when a window has none.
     */
    private fun windowTerminals(window: JSONObject): List<Pair<String, String?>> {
        val layers = window.optJSONArray("layers") ?: return emptyList()
        val windowName = window.stringOrNull("label") ?: defaultWindowName(window)
        val found = mutableListOf<Pair<String, String?>>()
        for (i in 0 until layers.length()) {
            val blocks = layers.optJSONObject(i)?.optJSONArray("blocks") ?: continue
            for (j in 0 until blocks.length()) {
                val block = blocks.optJSONObject(j) ?: continue
                val id = block.stringOrNull("block_id") ?: continue
                if (block.stringOrNull("creator_name") == TERMINAL_CREATOR) {
                    found += id to (windowName ?: block.stringOrNull("label"))
                }
            }
        }
        return found
    }
}

internal fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
