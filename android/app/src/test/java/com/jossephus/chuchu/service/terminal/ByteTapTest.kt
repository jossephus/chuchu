package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.plugin.api.ByteStreamEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

// runBlocking's single-threaded loop only runs the collector once the test body suspends
// (at await), so every emit() before that lands in the subscriber's queue first. That makes
// overflow deterministic without a test dispatcher.
class ByteTapTest {
    private fun ByteStreamEvent.bytes(): ByteArray = (this as ByteStreamEvent.Data).bytes

    @Test
    fun deliversChunksInOrder() = runBlocking {
        val tap = ByteTap()
        val received = async(start = CoroutineStart.UNDISPATCHED) { tap.subscribe(8).take(2).toList() }

        tap.emit(byteArrayOf(1))
        tap.emit(byteArrayOf(2, 3))

        val events = received.await()
        assertArrayEquals(byteArrayOf(1), events[0].bytes())
        assertArrayEquals(byteArrayOf(2, 3), events[1].bytes())
    }

    @Test
    fun overflowDropsOldestAndReportsGapFirst() = runBlocking {
        val tap = ByteTap()
        val received = async(start = CoroutineStart.UNDISPATCHED) { tap.subscribe(2).take(3).toList() }

        tap.emit(byteArrayOf(1))
        tap.emit(byteArrayOf(2, 2))
        tap.emit(byteArrayOf(3, 3, 3))

        val events = received.await()
        assertEquals(ByteStreamEvent.Gap(1), events[0])
        assertArrayEquals(byteArrayOf(2, 2), events[1].bytes())
        assertArrayEquals(byteArrayOf(3, 3, 3), events[2].bytes())
    }

    @Test
    fun eachSubscriberGetsItsOwnCopy() = runBlocking {
        val tap = ByteTap()
        val first = async(start = CoroutineStart.UNDISPATCHED) { tap.subscribe(4).take(1).toList() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { tap.subscribe(4).take(1).toList() }

        val source = byteArrayOf(7, 7)
        tap.emit(source)
        source[0] = 0

        val a = first.await().single().bytes()
        val b = second.await().single().bytes()
        assertArrayEquals(byteArrayOf(7, 7), a)
        assertArrayEquals(byteArrayOf(7, 7), b)
        assertNotSame(a, b)
    }

    @Test
    fun subscriptionEndsWhenCollectionStops() = runBlocking {
        val tap = ByteTap()
        val received = async(start = CoroutineStart.UNDISPATCHED) { tap.subscribe(4).take(1).toList() }
        assertTrue(tap.hasSubscribers)

        tap.emit(byteArrayOf(1))
        received.await()

        assertFalse(tap.hasSubscribers)
    }

    @Test
    fun rejectsNonPositiveCapacity() {
        val result = runCatching { ByteTap().subscribe(0) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
