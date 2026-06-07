package com.jossephus.chuchu.service.multiplexer

object MultiplexerSessionAllocator {
    fun nextChuchuSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String {
        val used = remoteSessions.map { it.name }.toSet() + localSessionNames
        var index = 1
        while ("chuchu-$index" in used) index += 1
        return "chuchu-$index"
    }
}
