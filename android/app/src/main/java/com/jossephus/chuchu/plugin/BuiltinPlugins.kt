package com.jossephus.chuchu.plugin

import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import com.jossephus.chuchu.plugin.builtin.TmuxMultiplexer
import com.jossephus.chuchu.plugin.builtin.ZmxMultiplexer

object BuiltinPlugins {
    val all: Map<String, ChuchuPlugin> =
        mapOf(
            "tmux" to ChuchuPlugin { it.registerMultiplexer(TmuxMultiplexer) },
            "zmx" to ChuchuPlugin { it.registerMultiplexer(ZmxMultiplexer) },
        )
}
