package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.MultiplexerType

object MultiplexerRegistry {
    val defaultType: MultiplexerType = MultiplexerType.Tmux

    fun forType(type: MultiplexerType): Multiplexer? = when (type) {
        MultiplexerType.Tmux -> TmuxMultiplexer
        MultiplexerType.Zellij,
        MultiplexerType.Zmx -> null
    }
}
