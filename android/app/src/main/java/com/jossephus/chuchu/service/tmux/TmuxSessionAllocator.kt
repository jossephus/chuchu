package com.jossephus.chuchu.service.tmux

object TmuxSessionAllocator {
    fun nextChuchuSessionName(
        remoteSessions: Collection<RemoteTmuxSession>,
        localSessionNames: Collection<String?>,
    ): String {
        val used = buildSet {
            remoteSessions.mapTo(this) { it.name }
            localSessionNames.filterNotNull().mapTo(this) { it }
        }
        var index = 1
        while (true) {
            val candidate = "chuchu-$index"
            if (candidate !in used) return candidate
            index += 1
        }
    }
}
