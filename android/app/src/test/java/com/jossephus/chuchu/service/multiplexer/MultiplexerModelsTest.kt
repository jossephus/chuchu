package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.MultiplexerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiplexerModelsTest {
    @Test
    fun persistedValuesResolveByStableIdIncludingLegacyEnumNames() {
        assertEquals(MultiplexerType.Tmux, MultiplexerType.fromPersistedValue("tmux"))
        assertEquals(MultiplexerType("zmx"), MultiplexerType.fromPersistedValue("Zmx"))
        assertEquals(MultiplexerType("herdr"), MultiplexerType.fromPersistedValue("herdr"))
        assertNull(MultiplexerType.fromPersistedValue(" "))
    }
}
