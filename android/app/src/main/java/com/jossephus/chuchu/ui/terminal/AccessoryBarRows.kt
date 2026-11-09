package com.jossephus.chuchu.ui.terminal

internal object AccessoryBarRows {
    const val MAX_LINES = 2

    fun lineCount(itemWidths: List<Int>, spacing: Int, maxWidth: Int): Int {
        if (itemWidths.isEmpty()) return 0
        var lines = 1
        var lineWidth = 0
        itemWidths.forEach { width ->
            if (lineWidth == 0) {
                lineWidth = width
            } else if (lineWidth + spacing + width <= maxWidth) {
                lineWidth += spacing + width
            } else {
                lines++
                lineWidth = width
            }
        }
        return lines
    }

    fun fitsInTwoLines(itemWidths: List<Int>, spacing: Int, maxWidth: Int): Boolean =
        lineCount(itemWidths, spacing, maxWidth) <= MAX_LINES

    fun firstRowKeyCount(keyWidths: List<Int>, trailingWidth: Int, spacing: Int, maxWidth: Int): Int {
        val available = maxWidth - trailingWidth - if (trailingWidth > 0) spacing else 0
        var used = 0
        keyWidths.forEachIndexed { index, width ->
            val next = if (index == 0) width else used + spacing + width
            if (next > available) return index
            used = next
        }
        return keyWidths.size
    }
}
