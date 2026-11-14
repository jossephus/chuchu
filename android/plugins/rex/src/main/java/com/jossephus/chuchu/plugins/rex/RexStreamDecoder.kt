package com.jossephus.chuchu.plugins.rex

import org.json.JSONException
import org.json.JSONObject

sealed interface RexStreamItem {
    class Snapshot(val bytes: ByteArray) : RexStreamItem

    class Output(val bytes: ByteArray) : RexStreamItem

    data class Resize(val columns: Int, val rows: Int) : RexStreamItem
}

class RexStreamDecoder(nonce: ULong) {
    private val marker = "\u001b_rex1;$nonce;".toByteArray(Charsets.US_ASCII)
    private val snapshot = GhosttySnapshotAccumulator()
    private var snapshotDone = false
    private var pending = EMPTY

    fun feed(chunk: ByteArray): List<RexStreamItem> {
        val items = mutableListOf<RexStreamItem>()
        var data = chunk
        if (!snapshotDone) {
            when (val result = snapshot.append(chunk)) {
                GhosttySnapshotAccumulator.Result.Pending -> return items
                is GhosttySnapshotAccumulator.Result.NotSnapshot -> {
                    snapshotDone = true
                    data = result.bytes
                }
                is GhosttySnapshotAccumulator.Result.Complete -> {
                    snapshotDone = true
                    items += RexStreamItem.Snapshot(result.snapshot)
                    data = result.rest
                }
            }
        }
        if (pending.isNotEmpty()) {
            data = pending + data
            pending = EMPTY
        }
        filterMessages(data, items)
        return items
    }

    private fun filterMessages(data: ByteArray, items: MutableList<RexStreamItem>) {
        var start = 0
        while (true) {
            val markerAt = indexOf(data, marker, start)
            if (markerAt < 0) {
                val held = heldMarkerPrefix(data, start)
                addOutput(data, start, data.size - held, items)
                pending = data.copyOfRange(data.size - held, data.size)
                return
            }
            addOutput(data, start, markerAt, items)
            val bodyStart = markerAt + marker.size
            val terminatorAt = indexOfStringTerminator(data, bodyStart)
            if (terminatorAt < 0) {
                if (data.size - markerAt > MAX_MESSAGE_BYTES) {
                    addOutput(data, markerAt, data.size, items)
                } else {
                    pending = data.copyOfRange(markerAt, data.size)
                }
                return
            }
            parseMessage(String(data, bodyStart, terminatorAt - bodyStart, Charsets.UTF_8))?.let(items::add)
            start = terminatorAt + 2
        }
    }

    private fun parseMessage(json: String): RexStreamItem? {
        val message =
            try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null
            }
        return when (message.optString("type")) {
            "resize" -> {
                val columns = message.optInt("cols")
                val rows = message.optInt("rows")
                if (columns > 0 && rows > 0) RexStreamItem.Resize(columns, rows) else null
            }
            else -> null
        }
    }

    private fun heldMarkerPrefix(data: ByteArray, start: Int): Int {
        val maxHeld = minOf(marker.size - 1, data.size - start)
        for (length in maxHeld downTo 1) {
            if (regionMatches(data, data.size - length, marker, length)) return length
        }
        return 0
    }

    private fun addOutput(data: ByteArray, from: Int, to: Int, items: MutableList<RexStreamItem>) {
        if (to <= from) return
        items += RexStreamItem.Output(data.copyOfRange(from, to))
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val MAX_MESSAGE_BYTES = 64 * 1024
        const val ESC: Byte = 0x1b
        const val BACKSLASH: Byte = 0x5c

        fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
            val last = data.size - needle.size
            var i = from
            while (i <= last) {
                if (regionMatches(data, i, needle, needle.size)) return i
                i++
            }
            return -1
        }

        fun indexOfStringTerminator(data: ByteArray, from: Int): Int {
            var i = from
            while (i < data.size - 1) {
                if (data[i] == ESC && data[i + 1] == BACKSLASH) return i
                i++
            }
            return -1
        }

        fun regionMatches(data: ByteArray, offset: Int, needle: ByteArray, length: Int): Boolean {
            for (j in 0 until length) {
                if (data[offset + j] != needle[j]) return false
            }
            return true
        }
    }
}

internal class GhosttySnapshotAccumulator {
    sealed interface Result {
        data object Pending : Result

        class NotSnapshot(val bytes: ByteArray) : Result

        class Complete(val snapshot: ByteArray, val rest: ByteArray) : Result
    }

    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var nextRecordAt = ENVELOPE_BYTES

    fun append(chunk: ByteArray): Result {
        ensureCapacity(size + chunk.size)
        chunk.copyInto(buffer, size)
        size += chunk.size
        for (i in 0 until minOf(size, MAGIC.size)) {
            if (buffer[i] != MAGIC[i]) return Result.NotSnapshot(drain())
        }
        while (nextRecordAt + RECORD_HEADER_BYTES <= size) {
            val tag = readU16(nextRecordAt)
            val end = nextRecordAt.toLong() + RECORD_HEADER_BYTES + readU32(nextRecordAt + 2)
            check(end <= MAX_SNAPSHOT_BYTES) { "Rex snapshot exceeds $MAX_SNAPSHOT_BYTES bytes" }
            if (end > size) return Result.Pending
            nextRecordAt = end.toInt()
            if (tag == TAG_FINISH) {
                val snapshot = buffer.copyOfRange(0, nextRecordAt)
                val rest = buffer.copyOfRange(nextRecordAt, size)
                reset()
                return Result.Complete(snapshot, rest)
            }
        }
        return Result.Pending
    }

    private fun drain(): ByteArray {
        val bytes = buffer.copyOfRange(0, size)
        reset()
        return bytes
    }

    private fun reset() {
        buffer = ByteArray(0)
        size = 0
        nextRecordAt = ENVELOPE_BYTES
    }

    private fun ensureCapacity(needed: Int) {
        check(needed <= MAX_SNAPSHOT_BYTES) { "Rex snapshot exceeds $MAX_SNAPSHOT_BYTES bytes" }
        if (needed <= buffer.size) return
        var capacity = maxOf(buffer.size, INITIAL_CAPACITY)
        while (capacity < needed) capacity = minOf(capacity * 2L, MAX_SNAPSHOT_BYTES.toLong()).toInt()
        buffer = buffer.copyOf(capacity)
    }

    private fun readU16(at: Int): Int =
        (buffer[at].toInt() and 0xff) or ((buffer[at + 1].toInt() and 0xff) shl 8)

    private fun readU32(at: Int): Long =
        (buffer[at].toLong() and 0xff) or
            ((buffer[at + 1].toLong() and 0xff) shl 8) or
            ((buffer[at + 2].toLong() and 0xff) shl 16) or
            ((buffer[at + 3].toLong() and 0xff) shl 24)

    private companion object {
        val MAGIC = "GHOSTSNP".toByteArray(Charsets.US_ASCII)
        const val ENVELOPE_BYTES = 10
        const val RECORD_HEADER_BYTES = 10
        const val TAG_FINISH = 6
        const val INITIAL_CAPACITY = 64 * 1024
        const val MAX_SNAPSHOT_BYTES = 256 * 1024 * 1024
    }
}
