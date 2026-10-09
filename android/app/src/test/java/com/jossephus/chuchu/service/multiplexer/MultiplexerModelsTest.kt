package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.plugin.builtin.TmuxMultiplexer
import com.jossephus.chuchu.plugin.builtin.ZmxMultiplexer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiplexerModelsTest {
    // The registry is global; each test registers under its own owner and cleans up.
    private val owner = "multiplexer-models-test"

    @After
    fun tearDown() {
        MultiplexerRegistry.unregisterOwner(owner)
    }

    @Test
    fun registryResolvesRegisteredProvidersById() {
        MultiplexerRegistry.register(owner, TmuxMultiplexer)
        MultiplexerRegistry.register(owner, ZmxMultiplexer)

        assertSame(TmuxMultiplexer, MultiplexerRegistry.forType(MultiplexerType.Tmux))
        assertSame(ZmxMultiplexer, MultiplexerRegistry.forType(MultiplexerType.Zmx))
        assertEquals("tmux", MultiplexerType.Tmux.label)
        assertTrue(MultiplexerType.Tmux.runtimeSupported)
    }

    @Test
    fun unregisteredIdIsUnsupportedButKeepsItsId() {
        val zellij = MultiplexerType("zellij")

        assertNull(MultiplexerRegistry.forType(zellij))
        assertFalse(zellij.runtimeSupported)
        assertEquals("zellij", zellij.label)
    }

    @Test
    fun unregisterOwnerRemovesOnlyThatOwnersProviders() {
        MultiplexerRegistry.register(owner, TmuxMultiplexer)
        MultiplexerRegistry.register("$owner-other", ZmxMultiplexer)
        try {
            MultiplexerRegistry.unregisterOwner(owner)

            assertNull(MultiplexerRegistry.forType(MultiplexerType.Tmux))
            assertSame(ZmxMultiplexer, MultiplexerRegistry.forType(MultiplexerType.Zmx))
        } finally {
            MultiplexerRegistry.unregisterOwner("$owner-other")
        }
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateIdIsRejected() {
        MultiplexerRegistry.register(owner, TmuxMultiplexer)
        MultiplexerRegistry.register(owner, TmuxMultiplexer)
    }

    @Test
    fun persistedValuesResolveByStableIdIncludingLegacyEnumNames() {
        assertEquals(MultiplexerType.Tmux, MultiplexerType.fromPersistedValue("tmux"))
        assertEquals(MultiplexerType.Zmx, MultiplexerType.fromPersistedValue("Zmx"))
        assertEquals(MultiplexerType("herdr"), MultiplexerType.fromPersistedValue("herdr"))
        assertNull(MultiplexerType.fromPersistedValue(" "))
    }
}
