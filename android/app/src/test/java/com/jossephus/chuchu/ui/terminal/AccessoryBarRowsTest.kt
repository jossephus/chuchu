package com.jossephus.chuchu.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessoryBarRowsTest {

    @Test
    fun `items that fit on two lines keep the flow layout`() {
        val widths = List(6) { 50 }
        assertEquals(2, AccessoryBarRows.lineCount(widths, spacing = 6, maxWidth = 200))
        assertTrue(AccessoryBarRows.fitsInTwoLines(widths, spacing = 6, maxWidth = 200))
    }

    @Test
    fun `items that need a third line do not fit`() {
        val widths = List(10) { 50 }
        assertEquals(4, AccessoryBarRows.lineCount(widths, spacing = 6, maxWidth = 200))
        assertFalse(AccessoryBarRows.fitsInTwoLines(widths, spacing = 6, maxWidth = 200))
    }

    @Test
    fun `an item exactly filling the line stays on it`() {
        assertEquals(1, AccessoryBarRows.lineCount(listOf(97, 97), spacing = 6, maxWidth = 200))
        assertEquals(2, AccessoryBarRows.lineCount(listOf(97, 98), spacing = 6, maxWidth = 200))
    }

    @Test
    fun `no items is zero lines`() {
        assertEquals(0, AccessoryBarRows.lineCount(emptyList(), spacing = 6, maxWidth = 200))
        assertTrue(AccessoryBarRows.fitsInTwoLines(emptyList(), spacing = 6, maxWidth = 200))
    }

    @Test
    fun `first row takes the keys that fit beside the trailing buttons`() {
        val keys = listOf(45, 45, 53, 61, 45, 30)
        assertEquals(3, AccessoryBarRows.firstRowKeyCount(keys, trailingWidth = 110, spacing = 6, maxWidth = 280))
    }

    @Test
    fun `first row is empty when the first key does not fit`() {
        assertEquals(0, AccessoryBarRows.firstRowKeyCount(listOf(200), trailingWidth = 110, spacing = 6, maxWidth = 280))
    }

    @Test
    fun `first row holds every key when they all fit`() {
        assertEquals(2, AccessoryBarRows.firstRowKeyCount(listOf(40, 40), trailingWidth = 0, spacing = 6, maxWidth = 280))
    }
}
