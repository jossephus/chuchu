package com.jossephus.chuchu.ui.screens.Terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalTabStripScrollTest {
    @Test
    fun visibleTabDoesNotScroll() {
        assertNull(scrollToReveal(bounds = 1200 until 1300, scroll = 1000, viewport = 600))
    }

    @Test
    fun tabLeftOfViewportScrollsToItsStart() {
        assertEquals(400, scrollToReveal(bounds = 400 until 500, scroll = 1000, viewport = 600))
    }

    @Test
    fun tabRightOfViewportScrollsUntilItsEndIsVisible() {
        assertEquals(1900, scrollToReveal(bounds = 2400 until 2500, scroll = 1000, viewport = 600))
    }
}
