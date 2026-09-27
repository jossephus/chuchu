package com.jossephus.chuchu.plugin.builtin

import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.api.MultiplexerProvider
import com.jossephus.chuchu.plugin.api.MultiplexerSessionNames
import com.jossephus.chuchu.plugin.api.PluginHost
import com.jossephus.chuchu.plugin.api.RemoteMultiplexerSession
import com.jossephus.chuchu.plugin.api.Shell

/** Built-in plugin contributing zmx session persistence. */
class ZmxPlugin : ChuchuPlugin {
    override fun register(host: PluginHost) = host.registerMultiplexer(ZmxMultiplexer)
}

object ZmxMultiplexer : MultiplexerProvider {
    override val id: String = "zmx"
    override val displayName: String = "zmx"

    override fun availabilityCommand(): String = "command -v zmx >/dev/null 2>&1"

    override fun listSessionsCommand(): String =
        "if ! command -v zmx >/dev/null 2>&1; then printf 'zmx executable not found\\n' >&2; false; " +
            "else zmx list 2>/dev/null; fi"

    override fun parseSessions(output: String): List<RemoteMultiplexerSession> =
        output
            .lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                val fields = trimmed
                    .split('\t')
                    .mapNotNull { part ->
                        val separator = part.indexOf('=')
                        if (separator <= 0) return@mapNotNull null
                        part.substring(0, separator) to part.substring(separator + 1)
                    }
                    .toMap()
                val name = fields["name"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RemoteMultiplexerSession(
                    name = name,
                    attached = fields["clients"]?.toIntOrNull()?.let { it > 0 } == true,
                )
            }
            .toList()

    override fun launchCommand(
        sessionName: String,
        createIfMissing: Boolean,
        trustedRemoteName: Boolean,
    ): String {
        val target = Shell.quote(sessionName)
        return if (createIfMissing) {
            "exec zmx attach $target"
        } else {
            "if zmx list --short 2>/dev/null | grep -Fqx -- $target; then exec zmx attach $target; " +
                "else printf 'zmx session %s is no longer available\\n' $target; exec \"\${SHELL:-/bin/sh}\" -l; fi"
        }
    }

    override fun defaultSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String = MultiplexerSessionNames.next(remoteSessions, localSessionNames)
}
