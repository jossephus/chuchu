package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.plugin.api.PtySize
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecChannelsTest {
    /** Scripted remote: queued output per stream, EOF once the scripts drain. */
    private class FakeTransport : ExecTransport {
        val stdout = ArrayDeque<ByteArray>()
        val stderr = ArrayDeque<ByteArray>()
        var infiniteStdout = false
        var eofWhenDrained = true
        var exitStatus = 0
        var maxWritePerCall = Int.MAX_VALUE
        val written = mutableListOf<Byte>()
        val closed = mutableListOf<Int>()
        var stdoutReads = 0
        var nextId = 1

        @Synchronized override fun open(command: String, pty: PtySize?): Int = nextId++

        @Synchronized override fun read(id: Int, stream: Int, maxBytes: Int): ByteArray {
            if (stream == 0) stdoutReads++
            if (stream == 0 && infiniteStdout) return ByteArray(1024)
            val queue = if (stream == 0) stdout else stderr
            return queue.removeFirstOrNull() ?: ByteArray(0)
        }

        @Synchronized override fun write(id: Int, data: ByteArray): Int {
            val n = minOf(data.size, maxWritePerCall)
            written += data.take(n)
            return n
        }

        @Synchronized override fun isEof(id: Int) =
            eofWhenDrained && !infiniteStdout && stdout.isEmpty() && stderr.isEmpty()

        @Synchronized override fun exitStatus(id: Int) = exitStatus

        override fun sendEof(id: Int) = true

        override fun resize(id: Int, size: PtySize) = true

        @Synchronized override fun close(id: Int) {
            closed += id
        }
    }

    // One thread, like the engine's session dispatcher.
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val transport = FakeTransport()
    private val channels = ExecChannels(transport, dispatcher, scope)

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun streamsOutputAndReportsExitStatusAfterEof() = runBlocking {
        transport.stdout += "hello ".toByteArray()
        transport.stdout += "world".toByteArray()
        transport.stderr += "warn".toByteArray()
        transport.exitStatus = 3

        val exec = channels.open("echo", pty = null)
        val out = withTimeout(2_000) { exec.stdout.toList() }
        val err = withTimeout(2_000) { exec.stderr.toList() }

        assertEquals("hello world", out.joinToString("") { String(it) })
        assertEquals("warn", err.joinToString("") { String(it) })
        assertEquals(3, withTimeout(2_000) { exec.exitCode.await() })
        assertEquals(listOf(1), transport.closed)
    }

    @Test
    fun uncollectedStreamStopsReadingInsteadOfBufferingForever() = runBlocking {
        transport.infiniteStdout = true
        val exec = channels.open("yes", pty = null)

        delay(300)
        val reads = withContext(dispatcher) { transport.stdoutReads }

        // 16 buffered chunks plus one pending; afterwards the pump waits for the collector.
        assertTrue("stdout was read $reads times", reads <= 17)
        exec.close()
    }

    @Test
    fun writeRetriesUntilPartialWritesComplete() = runBlocking {
        transport.eofWhenDrained = false
        transport.maxWritePerCall = 2
        val exec = channels.open("cat", pty = null)

        exec.write("abcde".toByteArray())

        assertArrayEquals("abcde".toByteArray(), withContext(dispatcher) { transport.written.toByteArray() })
        exec.close()
    }

    @Test
    fun closeReleasesTheNativeChannelOnceWithNullExitCode() = runBlocking {
        transport.eofWhenDrained = false
        val exec = channels.open("sleep", pty = null)

        exec.close()
        exec.close()

        assertNull(withTimeout(2_000) { exec.exitCode.await() })
        assertEquals(listOf(1), withContext(dispatcher) { transport.closed.toList() })
    }

    @Test
    fun reconnectInvalidatesChannelsWithoutClosingReusedIds() = runBlocking {
        transport.eofWhenDrained = false
        val stale = channels.open("tail -f log", pty = null)

        withContext(dispatcher) { channels.invalidateAll() }
        // The new native session hands out id 1 again; the stale handle must not close it.
        stale.close()
        delay(50)

        assertNull(withTimeout(2_000) { stale.exitCode.await() })
        assertTrue(withContext(dispatcher) { transport.closed.isEmpty() })
        assertTrue(withTimeout(2_000) { stale.stdout.toList() }.isEmpty())
    }
}
