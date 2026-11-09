package com.jossephus.chuchu.plugins.rex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RexStreamDecoderTest {
    private val nonce = 1234567890123uL

    @Test
    fun splitsSnapshotFromFollowingOutput() {
        val snapshot = snapshotBytes(payloads = listOf(1 to bytes(5), 5 to bytes(0)))
        val items = RexStreamDecoder(nonce).feed(snapshot + "hello".toByteArray())

        assertEquals(2, items.size)
        assertArrayEquals(snapshot, (items[0] as RexStreamItem.Snapshot).bytes)
        assertEquals("hello", outputText(items.drop(1)))
    }

    @Test
    fun waitsForTheWholeSnapshotAcrossChunks() {
        val snapshot = snapshotBytes(payloads = listOf(1 to bytes(300), 3 to bytes(70_000)))
        val decoder = RexStreamDecoder(nonce)
        val items = mutableListOf<RexStreamItem>()
        snapshot.toList().chunked(4096).forEach { items += decoder.feed(it.toByteArray()) }
        items += decoder.feed("$ ".toByteArray())

        assertArrayEquals(snapshot, (items.first() as RexStreamItem.Snapshot).bytes)
        assertEquals("$ ", outputText(items.drop(1)))
    }

    @Test
    fun streamWithoutSnapshotIsAllOutput() {
        val items = RexStreamDecoder(nonce).feed("plain output".toByteArray())
        assertEquals("plain output", outputText(items))
    }

    @Test
    fun extractsRexMessagesWithMatchingNonce() {
        val decoder = RexStreamDecoder(nonce)
        decoder.feed(snapshotBytes(listOf(6 to bytes(0))))
        val stream = "a${rex("""{"type":"resize","cols":120,"rows":40}""")}b${rex("""{"type":"child_exited","exit_code":3}""")}c"
        val items = decoder.feed(stream.toByteArray())

        assertEquals(
            listOf(
                "out:a",
                "Resize(columns=120, rows=40)",
                "out:b",
                "ChildExited(exitCode=3)",
                "out:c",
            ),
            items.map(::describe),
        )
    }

    @Test
    fun keepsLookalikeMessagesThatAProgramPrinted() {
        val decoder = RexStreamDecoder(nonce)
        decoder.feed(snapshotBytes(listOf(6 to bytes(0))))
        val forged = "\u001b_rex1;999;{\"type\":\"resize\",\"cols\":1,\"rows\":1}\u001b\\"
        val items = decoder.feed(forged.toByteArray())

        assertEquals(forged, outputText(items))
    }

    @Test
    fun reassemblesMessagesSplitAcrossChunks() {
        val decoder = RexStreamDecoder(nonce)
        decoder.feed(snapshotBytes(listOf(6 to bytes(0))))
        val stream = ("x" + rex("""{"type":"resize","cols":80,"rows":24}""") + "y").toByteArray()
        val items = stream.map { decoder.feed(byteArrayOf(it)) }.flatten()

        assertEquals("xy", outputText(items))
        assertTrue(items.contains(RexStreamItem.Resize(80, 24)))
    }

    @Test
    fun releasesHeldPrefixWhenItIsNotAMessage() {
        val decoder = RexStreamDecoder(nonce)
        decoder.feed(snapshotBytes(listOf(6 to bytes(0))))
        val first = decoder.feed("abc\u001b_re".toByteArray())
        val second = decoder.feed("st".toByteArray())

        assertEquals("abc", outputText(first))
        assertEquals("\u001b_rest", outputText(second))
    }

    @Test
    fun splitUtf8KeepsMultibyteCharactersWhole() {
        val text = "aé€😀".repeat(50)
        val chunks = RexTerminalSession.splitUtf8(text.toByteArray(), maxBytes = 7)

        assertTrue(chunks.all { it.size <= 7 })
        assertEquals(text, chunks.joinToString("") { String(it, Charsets.UTF_8) })
    }

    private fun rex(json: String) = "\u001b_rex1;$nonce;$json\u001b\\"

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun snapshotBytes(payloads: List<Pair<Int, ByteArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("GHOSTSNP".toByteArray())
        out.write(byteArrayOf(1, 0))
        val all = if (payloads.last().first == 6) payloads else payloads + (6 to ByteArray(0))
        for ((tag, payload) in all) {
            out.write(byteArrayOf(tag.toByte(), (tag shr 8).toByte()))
            val n = payload.size
            out.write(byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()))
            out.write(ByteArray(4))
            out.write(payload)
        }
        return out.toByteArray()
    }

    private fun outputText(items: List<RexStreamItem>): String =
        items.filterIsInstance<RexStreamItem.Output>().joinToString("") { String(it.bytes, Charsets.UTF_8) }

    private fun describe(item: RexStreamItem): String =
        when (item) {
            is RexStreamItem.Output -> "out:" + String(item.bytes, Charsets.UTF_8)
            else -> item.toString()
        }
}
