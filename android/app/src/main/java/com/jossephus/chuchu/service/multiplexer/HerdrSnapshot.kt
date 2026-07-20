package com.jossephus.chuchu.service.multiplexer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class HerdrSnapshot(
    val protocol: Int = 0,
    val version: String = "",
    val workspaces: List<HerdrWorkspace> = emptyList(),
    val tabs: List<HerdrTab> = emptyList(),
    val panes: List<HerdrPane> = emptyList(),
    val layouts: List<HerdrTabLayout> = emptyList(),
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
)

@Serializable
data class HerdrRect(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class HerdrLayoutPane(
    @SerialName("pane_id") val paneId: String = "",
    val rect: HerdrRect = HerdrRect(),
    val focused: Boolean = false,
)

@Serializable
data class HerdrTabLayout(
    @SerialName("tab_id") val tabId: String = "",
    @SerialName("workspace_id") val workspaceId: String = "",
    val area: HerdrRect = HerdrRect(),
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
    val panes: List<HerdrLayoutPane> = emptyList(),
    val zoomed: Boolean = false,
)

fun desiredHerdrPaneStreams(
    snapshot: HerdrSnapshot?,
    nativeModeActive: Boolean,
    foreground: Boolean,
): Set<String> {
    if (!nativeModeActive || !foreground || snapshot == null) return emptySet()
    val layout = snapshot.layouts.firstOrNull { it.tabId == snapshot.focusedTabId } ?: return emptySet()
    val focusedPaneId = snapshot.focusedPaneId ?: layout.focusedPaneId
    if (layout.zoomed) return focusedPaneId?.let(::setOf) ?: emptySet()
    val paneIds = layout.panes.map { it.paneId }.filter { it.isNotBlank() }.toMutableSet()
    if (paneIds.size <= 6) return paneIds
    val retained = LinkedHashSet<String>(6)
    focusedPaneId?.takeIf { it in paneIds }?.let(retained::add)
    paneIds.asSequence().filterNot { it == focusedPaneId }.take(6 - retained.size).forEach(retained::add)
    return retained
}

sealed interface HerdrControlState {
    data object Inactive : HerdrControlState

    data object Connecting : HerdrControlState

    data class Active(
        val snapshot: HerdrSnapshot,
        val protocolWarning: Boolean,
    ) : HerdrControlState

    data class Error(val message: String) : HerdrControlState
}

internal fun appendHerdrStreamChunk(buffer: StringBuilder, chunk: String): List<String> {
    val begin = "CHUCHU_SNAP_BEGIN\n"
    val end = "\nCHUCHU_SNAP_END"
    val frames = mutableListOf<String>()
    buffer.append(chunk)

    while (true) {
        val beginIndex = buffer.indexOf(begin)
        if (beginIndex < 0) {
            val keep =
                (begin.length - 1 downTo 1).firstOrNull { length ->
                    buffer.endsWith(begin.substring(0, length))
                } ?: 0
            if (buffer.length > keep) buffer.delete(0, buffer.length - keep)
            return frames
        }
        if (beginIndex > 0) buffer.delete(0, beginIndex)

        val endIndex = buffer.indexOf(end, begin.length)
        if (endIndex < 0) return frames

        frames += buffer.substring(begin.length, endIndex)
        buffer.delete(0, endIndex + end.length)
    }
}

@Serializable
data class HerdrWorkspace(
    @SerialName("workspace_id") val workspaceId: String = "",
    val label: String? = null,
    val number: Int = 0,
    @SerialName("agent_status") val agentStatus: HerdrAgentStatus = HerdrAgentStatus.Unknown,
    val focused: Boolean = false,
    @SerialName("tab_count") val tabCount: Int = 0,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("active_tab_id") val activeTabId: String? = null,
)

@Serializable
data class HerdrTab(
    @SerialName("tab_id") val tabId: String = "",
    @SerialName("workspace_id") val workspaceId: String = "",
    val label: String? = null,
    val number: Int = 0,
    @SerialName("agent_status") val agentStatus: HerdrAgentStatus = HerdrAgentStatus.Unknown,
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
)

@Serializable
data class HerdrPane(
    @SerialName("pane_id") val paneId: String = "",
    @SerialName("tab_id") val tabId: String = "",
    @SerialName("workspace_id") val workspaceId: String = "",
    val agent: String? = null,
    @SerialName("agent_status") val agentStatus: HerdrAgentStatus = HerdrAgentStatus.Unknown,
    val cwd: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    val focused: Boolean = false,
)

@Serializable(with = HerdrAgentStatusSerializer::class)
enum class HerdrAgentStatus {
    Idle,
    Working,
    Blocked,
    Done,
    Unknown,
}

object HerdrAgentStatusSerializer : KSerializer<HerdrAgentStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("HerdrAgentStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): HerdrAgentStatus {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
        val value = (element as? JsonPrimitive)?.contentOrNull
        return when (value) {
            "idle" -> HerdrAgentStatus.Idle
            "working" -> HerdrAgentStatus.Working
            "blocked" -> HerdrAgentStatus.Blocked
            "done" -> HerdrAgentStatus.Done
            else -> HerdrAgentStatus.Unknown
        }
    }

    override fun serialize(encoder: Encoder, value: HerdrAgentStatus) {
        encoder.encodeString(
            when (value) {
                HerdrAgentStatus.Idle -> "idle"
                HerdrAgentStatus.Working -> "working"
                HerdrAgentStatus.Blocked -> "blocked"
                HerdrAgentStatus.Done -> "done"
                HerdrAgentStatus.Unknown -> "unknown"
            },
        )
    }
}

private val herdrSnapshotJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun parseHerdrSnapshot(output: String): HerdrSnapshot? {
    val jsonStart = output.indexOf('{')
    if (jsonStart < 0) return null

    return try {
        val envelope = herdrSnapshotJson.decodeFromString<HerdrSnapshotEnvelope>(output.substring(jsonStart))
        if (envelope.id != "cli:api:snapshot") null else envelope.result.snapshot
    } catch (_: Exception) {
        null
    }
}

@Serializable
private data class HerdrSnapshotEnvelope(
    val id: String,
    val result: HerdrSnapshotResult,
)

@Serializable
private data class HerdrSnapshotResult(
    val snapshot: HerdrSnapshot,
)
