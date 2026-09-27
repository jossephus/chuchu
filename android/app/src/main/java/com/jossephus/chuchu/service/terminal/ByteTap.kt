package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.plugin.api.ByteStreamEvent
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fans a byte stream (terminal output or user input) out to plugin subscribers without
 * ever blocking the producer.
 *
 * [emit] runs on the session's read/write path, so it must stay cheap: with no subscribers
 * it is a single emptiness check, and with subscribers it only copies and enqueues. Each
 * subscriber has its own bounded queue; when full, the oldest chunk is dropped and counted
 * so the subscriber sees a [ByteStreamEvent.Gap] rather than a silently corrupted stream.
 */
class ByteTap {
    private val subscribers = CopyOnWriteArrayList<Subscriber>()

    val hasSubscribers: Boolean
        get() = subscribers.isNotEmpty()

    fun emit(bytes: ByteArray) {
        if (subscribers.isEmpty()) return
        // Each subscriber gets its own copy: plugins may keep or mutate what they receive,
        // and the producer may reuse its buffer.
        for (subscriber in subscribers) subscriber.offer(bytes.copyOf())
    }

    /** Cold flow; the subscription exists only while it is being collected. */
    fun subscribe(capacity: Int): Flow<ByteStreamEvent> {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
        return flow {
            val subscriber = Subscriber(capacity)
            subscribers.add(subscriber)
            try {
                while (true) {
                    subscriber.signal.receive()
                    for (event in subscriber.drain()) emit(event)
                }
            } finally {
                subscribers.remove(subscriber)
            }
        }
    }

    private class Subscriber(private val capacity: Int) {
        // Conflated: one pending wake-up is enough, since drain() takes everything queued.
        val signal = Channel<Unit>(Channel.CONFLATED)
        private val queue = ArrayDeque<ByteArray>()
        private var droppedBytes = 0L

        fun offer(chunk: ByteArray) {
            synchronized(this) {
                if (queue.size == capacity) droppedBytes += queue.removeFirst().size
                queue.addLast(chunk)
            }
            signal.trySend(Unit)
        }

        fun drain(): List<ByteStreamEvent> =
            synchronized(this) {
                val events = ArrayList<ByteStreamEvent>(queue.size + 1)
                // Dropped chunks were older than everything still queued, so the gap
                // comes first.
                if (droppedBytes > 0) events.add(ByteStreamEvent.Gap(droppedBytes))
                queue.forEach { events.add(ByteStreamEvent.Data(it)) }
                queue.clear()
                droppedBytes = 0
                events
            }
    }
}
