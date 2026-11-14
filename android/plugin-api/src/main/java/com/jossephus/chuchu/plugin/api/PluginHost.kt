package com.jossephus.chuchu.plugin.api

interface PluginHost {
    /** Throws if another plugin already registered the same id. */
    fun registerMultiplexer(provider: MultiplexerProvider)

    /** Throws if another plugin already registered the same id. */
    fun registerTransport(provider: TransportProvider)
}
