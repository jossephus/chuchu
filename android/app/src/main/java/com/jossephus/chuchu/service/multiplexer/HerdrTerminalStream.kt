package com.jossephus.chuchu.service.multiplexer

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class HerdrTerminalFrame(
    val type: String = "",
    val seq: Long = 0,
    val full: Boolean = false,
    val encoding: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bytes: String = "",
) {
    fun decodedBytes(): ByteArray =
        try {
            Base64.getDecoder().decode(bytes)
        } catch (_: IllegalArgumentException) {
            byteArrayOf()
        }
}

@Serializable
data class HerdrTerminalClosed(
    val type: String = "",
    val reason: String = "",
)

sealed interface HerdrStreamMessage {
    data class Frame(val value: HerdrTerminalFrame) : HerdrStreamMessage

    data class Closed(val value: HerdrTerminalClosed) : HerdrStreamMessage
}

enum class HerdrStreamMode {
    Control,
    ControlTakeover,
    Observe,
}

@Serializable
enum class HerdrScrollDirection {
    @SerialName("up")
    Up,

    @SerialName("down")
    Down,
}

enum class FrameDisposition {
    Apply,
    Restart,
}

private val herdrTerminalJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
}

fun parseHerdrStreamMessage(line: String): HerdrStreamMessage? =
    try {
        when (herdrTerminalJson.parseToJsonElement(line).jsonObject["type"]?.jsonPrimitive?.contentOrNull) {
            "terminal.frame" -> HerdrStreamMessage.Frame(herdrTerminalJson.decodeFromString(line))
            "terminal.closed" -> HerdrStreamMessage.Closed(herdrTerminalJson.decodeFromString(line))
            else -> null
        }
    } catch (_: Exception) {
        null
    }

fun appendHerdrNdjsonChunk(buffer: StringBuilder, chunk: String): List<String> {
    val lines = mutableListOf<String>()
    buffer.append(chunk)

    while (true) {
        val newlineIndex = buffer.indexOf("\n")
        if (newlineIndex < 0) return lines
        lines += buffer.substring(0, newlineIndex)
        buffer.delete(0, newlineIndex + 1)
    }
}

fun herdrInputTextJson(text: String): String =
    herdrTerminalJson.encodeToString(HerdrTerminalInputText(text = text))

fun herdrInputBytesJson(bytes: ByteArray): String =
    herdrTerminalJson.encodeToString(HerdrTerminalInputBytes(bytes = Base64.getEncoder().encodeToString(bytes)))

fun herdrResizeJson(cols: Int, rows: Int): String =
    herdrTerminalJson.encodeToString(HerdrTerminalResize(cols = cols, rows = rows))

fun herdrScrollJson(direction: HerdrScrollDirection, lines: Int): String =
    herdrTerminalJson.encodeToString(HerdrTerminalScroll(direction = direction, lines = lines))

fun frameDisposition(lastSeq: Long?, frame: HerdrTerminalFrame): FrameDisposition =
    if (frame.full || (lastSeq != null && frame.seq == lastSeq + 1)) {
        FrameDisposition.Apply
    } else {
        FrameDisposition.Restart
    }

@Serializable
private data class HerdrTerminalInputText(
    val type: String = "terminal.input",
    val text: String,
)

@Serializable
private data class HerdrTerminalInputBytes(
    val type: String = "terminal.input",
    val bytes: String,
)

@Serializable
private data class HerdrTerminalResize(
    val type: String = "terminal.resize",
    val cols: Int,
    val rows: Int,
)

@Serializable
private data class HerdrTerminalScroll(
    val type: String = "terminal.scroll",
    val direction: HerdrScrollDirection,
    val lines: Int,
)
