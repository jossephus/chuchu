package com.jossephus.chuchu.service.tmux

import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxSessionAllocatorTest {
    @Test
    fun choosesLowestUnusedChuchuNumberAcrossRemoteAndLocal() {
        val next = TmuxSessionAllocator.nextChuchuSessionName(
            remoteSessions = listOf(
                RemoteTmuxSession("chuchu-1", attached = false),
                RemoteTmuxSession("main", attached = true),
                RemoteTmuxSession("chuchu-3", attached = false),
            ),
            localSessionNames = listOf("chuchu-2", null),
        )

        assertEquals("chuchu-4", next)
    }

    @Test
    fun reusesGapWhenRemoteAndLocalDoNotUseIt() {
        val next = TmuxSessionAllocator.nextChuchuSessionName(
            remoteSessions = listOf(RemoteTmuxSession("chuchu-2", attached = false)),
            localSessionNames = listOf("chuchu-3"),
        )

        assertEquals("chuchu-1", next)
    }
}
