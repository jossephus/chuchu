package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.plugin.api.MultiplexerSessionNames

object MultiplexerSessionAllocator {
    private val chuchuSessionRegex = Regex("^chuchu-([1-9][0-9]*)$")

    fun reusableDetachedChuchuSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String? {
        val localNames = localSessionNames.toSet()
        return remoteSessions
            .asSequence()
            .filter { session -> !session.attached && session.name !in localNames }
            .mapNotNull { session ->
                chuchuSessionRegex
                    .matchEntire(session.name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.let { index -> session.name to index }
            }
            .maxByOrNull { (_, index) -> index }
            ?.first
    }

    fun nextChuchuSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String = MultiplexerSessionNames.next(remoteSessions, localSessionNames)
}
