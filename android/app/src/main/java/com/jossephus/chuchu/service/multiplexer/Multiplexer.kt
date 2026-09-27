package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.plugin.api.MultiplexerProvider

// Multiplexers are plugin contributions now; the host-side name is kept for existing callers.
typealias Multiplexer = MultiplexerProvider
