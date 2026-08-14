package com.jossephus.chuchu.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiplexerSessionAllocatorTest {
    @Test
    fun reusesHighestDetachedChuchuSessionOutsideLocalTabs() {
        val reusable = MultiplexerSessionAllocator.reusableDetachedChuchuSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("chuchu-1", attached = false),
                RemoteMultiplexerSession("chuchu-2", attached = false),
                RemoteMultiplexerSession("chuchu-3", attached = false),
                RemoteMultiplexerSession("chuchu-5", attached = true),
                RemoteMultiplexerSession("chuchu-7-extra", attached = false),
            ),
            localSessionNames = listOf("chuchu-3"),
        )

        assertEquals("chuchu-2", reusable)
    }

    @Test
    fun doesNotReuseAttachedOrLocalChuchuSessions() {
        val reusable = MultiplexerSessionAllocator.reusableDetachedChuchuSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("chuchu-1", attached = true),
                RemoteMultiplexerSession("chuchu-2", attached = false),
                RemoteMultiplexerSession("work", attached = false),
            ),
            localSessionNames = listOf("chuchu-2"),
        )

        assertEquals(null, reusable)
    }

    @Test
    fun skipsUsedRemoteAndLocalNames() {
        val next = MultiplexerSessionAllocator.nextChuchuSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("chuchu-1", attached = false),
                RemoteMultiplexerSession("main", attached = true),
                RemoteMultiplexerSession("chuchu-3", attached = false),
            ),
            localSessionNames = listOf("chuchu-2"),
        )

        assertEquals("chuchu-4", next)
    }

    @Test
    fun ignoresPrefixCollisions() {
        val next = MultiplexerSessionAllocator.nextChuchuSessionName(
            remoteSessions = listOf(RemoteMultiplexerSession("chuchu-2", attached = false)),
            localSessionNames = listOf("chuchu-1-extra"),
        )

        assertEquals("chuchu-1", next)
    }
}
