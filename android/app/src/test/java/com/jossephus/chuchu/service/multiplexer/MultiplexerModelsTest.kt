package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.MultiplexerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MultiplexerModelsTest {
    @Test
    fun registryReturnsTmuxRuntime() {
        assertSame(TmuxMultiplexer, MultiplexerRegistry.forType(MultiplexerType.Tmux))
    }

    @Test
    fun registryLeavesFutureMultiplexersUnsupportedForNow() {
        assertNull(MultiplexerRegistry.forType(MultiplexerType.Zellij))
        assertNull(MultiplexerRegistry.forType(MultiplexerType.Zmx))
    }

    @Test
    fun persistedValuesResolveByStableId() {
        assertEquals(MultiplexerType.Tmux, MultiplexerType.fromPersistedValue("tmux"))
    }
}
