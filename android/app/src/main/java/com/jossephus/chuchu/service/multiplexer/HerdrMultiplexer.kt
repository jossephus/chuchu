package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.MultiplexerType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object HerdrMultiplexer : Multiplexer {
    internal const val pathPrelude = "PATH=\"\$HOME/.local/bin:/usr/local/bin:/opt/homebrew/bin:\$PATH\"; "
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override val type: MultiplexerType = MultiplexerType.Herdr

    override fun availabilityCommand(): String = pathPrelude + "command -v herdr >/dev/null 2>&1"

    override fun listSessionsCommand(): String =
        pathPrelude + "if ! command -v herdr >/dev/null 2>&1; then printf 'herdr executable not found\\n' >&2; false; " +
            "else herdr session list --json 2>/dev/null; fi"

    override fun parseSessions(output: String): List<RemoteMultiplexerSession> {
        val jsonStart = output.indexOf('{')
        if (jsonStart < 0) return emptyList()
        return try {
            json.decodeFromString<HerdrSessionList>(output.substring(jsonStart))
                .sessions
                .asSequence()
                .filter { it.name.isNotBlank() }
                .sortedByDescending { it.default }
                .map { session ->
                    RemoteMultiplexerSession(
                        name = session.name,
                        attached = session.running,
                    )
                }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun launchCommand(
        sessionName: String,
        createIfMissing: Boolean,
        trustedRemoteName: Boolean,
    ): String {
        val target = shellQuote(sessionName)
        return if (createIfMissing) {
            pathPrelude + "exec herdr --session $target"
        } else {
            val pattern = shellQuote("\"name\":\"$sessionName\"")
            pathPrelude + "if herdr session list --json 2>/dev/null | grep -Fq -- $pattern; then exec herdr session attach $target; " +
                "else printf 'herdr session %s is no longer available\\n' $target; exec \"\${SHELL:-/bin/sh}\" -l; fi"
        }
    }

    fun nativeModeLaunchCommand(sessionName: String): String {
        val target = shellQuote(sessionName)
        val pattern = shellQuote("\"name\":\"$sessionName\"")
        return pathPrelude +
            "if ! herdr session list --json 2>/dev/null | grep -Fq -- $pattern; then " +
            "printf 'herdr session %s is not running on this host\\n' $target; exit 1; fi; " +
            "while :; do sleep 3600; done"
    }

    override fun defaultSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String = "default"

    fun snapshotStreamCommand(): String =
        pathPrelude +
            "while IFS= read -r _; do printf 'CHUCHU_SNAP_BEGIN\\n'; herdr api snapshot 2>/dev/null; " +
            "printf '\\nCHUCHU_SNAP_END\\n'; done"

    fun terminalSessionCommand(
        paneId: String,
        cols: Int,
        rows: Int,
        mode: HerdrStreamMode,
    ): String {
        val command = when (mode) {
            HerdrStreamMode.Control,
            HerdrStreamMode.ControlTakeover,
            -> "control"
            HerdrStreamMode.Observe -> "observe"
        }
        val takeover = if (mode == HerdrStreamMode.ControlTakeover) " --takeover" else ""
        return pathPrelude + "herdr terminal session $command ${shellQuote(paneId)} --cols $cols --rows $rows$takeover"
    }

    fun focusTabCommand(tabId: String): String =
        pathPrelude + "herdr tab focus ${shellQuote(tabId)}"

    fun focusWorkspaceCommand(workspaceId: String): String =
        pathPrelude + "herdr workspace focus ${shellQuote(workspaceId)}"

    fun focusPaneCommand(paneId: String): String =
        pathPrelude + "herdr agent focus ${shellQuote(paneId)}"

    fun createTabCommand(workspaceId: String): String =
        pathPrelude + "herdr tab create --workspace ${shellQuote(workspaceId)} --focus"

    fun closeTabCommand(tabId: String): String =
        pathPrelude + "herdr tab close ${shellQuote(tabId)}"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    @Serializable
    private data class HerdrSessionList(
        val sessions: List<HerdrSession> = emptyList(),
    )

    @Serializable
    private data class HerdrSession(
        val default: Boolean = false,
        val name: String = "",
        val running: Boolean = false,
    )
}
