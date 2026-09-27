package com.jossephus.chuchu.plugin

import com.jossephus.chuchu.plugin.api.ByteStreamEvent
import com.jossephus.chuchu.service.terminal.ByteTap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteWhenTest {
    @Test
    fun tapSubscriptionCompletesAndUnsubscribesWhenSignalled() = runBlocking {
        val tap = ByteTap()
        val closed = CompletableDeferred<Unit>()
        val received = async(start = CoroutineStart.UNDISPATCHED) { tap.subscribe(4).completeWhen(closed).toList() }
        // Let channelFlow start its forwarding collector so the tap subscription exists.
        repeat(3) { yield() }
        assertTrue(tap.hasSubscribers)

        tap.emit(byteArrayOf(5))
        repeat(3) { yield() }
        closed.complete(Unit)

        val events = withTimeout(1_000) { received.await() }
        assertEquals(1, events.size)
        assertArrayEquals(byteArrayOf(5), (events.single() as ByteStreamEvent.Data).bytes)
        assertFalse(tap.hasSubscribers)
    }

    @Test
    fun alreadyClosedSessionYieldsEmptyStream() = runBlocking {
        val tap = ByteTap()
        val closed = CompletableDeferred(Unit)

        val events = withTimeout(1_000) { tap.subscribe(4).completeWhen(closed).toList() }

        assertTrue(events.isEmpty())
        assertFalse(tap.hasSubscribers)
    }
}
