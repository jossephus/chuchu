package com.jossephus.chuchu.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HerdrTerminalStreamTest {
    @Test
    fun parsesTerminalFrameAndDecodesBytes() {
        val message = parseHerdrStreamMessage(
            """{"type":"terminal.frame","seq":42,"full":true,"encoding":"ansi","width":83,"height":28,"bytes":"SGVyZHIgZnJhbWUh"}""",
        )

        assertEquals(
            HerdrStreamMessage.Frame(
                HerdrTerminalFrame(
                    type = "terminal.frame",
                    seq = 42,
                    full = true,
                    encoding = "ansi",
                    width = 83,
                    height = 28,
                    bytes = "SGVyZHIgZnJhbWUh",
                ),
            ),
            message,
        )
        assertEquals("Herdr frame!", (message as HerdrStreamMessage.Frame).value.decodedBytes().decodeToString())
        assertEquals(0, HerdrTerminalFrame(bytes = "not base64").decodedBytes().size)
    }

    @Test
    fun parsesTerminalClosed() {
        assertEquals(
            HerdrStreamMessage.Closed(
                HerdrTerminalClosed(
                    type = "terminal.closed",
                    reason = "already has an attached client; retry with --takeover",
                ),
            ),
            parseHerdrStreamMessage(
                """{"type":"terminal.closed","reason":"already has an attached client; retry with --takeover"}""",
            ),
        )
    }

    @Test
    fun returnsNullForGarbageAndUnknownMessages() {
        assertNull(parseHerdrStreamMessage("garbage"))
        assertNull(parseHerdrStreamMessage("""{"type":"terminal.unknown"}"""))
    }

    @Test
    fun framesNdjsonAcrossChunksAndRetainsPartialLines() {
        val buffer = StringBuilder()

        assertEquals(listOf("first"), appendHerdrNdjsonChunk(buffer, "first\nsec"))
        assertEquals("sec", buffer.toString())
        assertEquals(listOf("second", "third"), appendHerdrNdjsonChunk(buffer, "ond\nthird\npartial"))
        assertEquals("partial", buffer.toString())
    }

    @Test
    fun encodesTerminalInputAndControlMessages() {
        assertEquals(
            """{"type":"terminal.input","text":"hello\nworld"}""",
            herdrInputTextJson("hello\nworld"),
        )
        assertEquals(
            """{"type":"terminal.input","bytes":"AAECA/8="}""",
            herdrInputBytesJson(byteArrayOf(0, 1, 2, 3, -1)),
        )
        assertEquals(
            """{"type":"terminal.resize","cols":166,"rows":56}""",
            herdrResizeJson(166, 56),
        )
        assertEquals(
            """{"type":"terminal.scroll","direction":"up","lines":3}""",
            herdrScrollJson(HerdrScrollDirection.Up, 3),
        )
        assertEquals(
            """{"type":"terminal.scroll","direction":"down","lines":2}""",
            herdrScrollJson(HerdrScrollDirection.Down, 2),
        )
    }

    @Test
    fun determinesFrameDispositionFromSequence() {
        assertEquals(
            FrameDisposition.Apply,
            frameDisposition(lastSeq = null, frame = HerdrTerminalFrame(seq = 10, full = true)),
        )
        assertEquals(
            FrameDisposition.Apply,
            frameDisposition(lastSeq = 10, frame = HerdrTerminalFrame(seq = 11)),
        )
        assertEquals(
            FrameDisposition.Restart,
            frameDisposition(lastSeq = 10, frame = HerdrTerminalFrame(seq = 12)),
        )
        assertEquals(
            FrameDisposition.Restart,
            frameDisposition(lastSeq = null, frame = HerdrTerminalFrame(seq = 10)),
        )
    }

    @Test
    fun mapsPositiveScrollDeltaToHistoryUp() {
        assertEquals(HerdrScrollDirection.Up to 3, herdrScrollCommand(3))
        assertEquals(HerdrScrollDirection.Down to 2, herdrScrollCommand(-2))
    }
}
