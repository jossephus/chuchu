package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.plugin.api.MultiplexerProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MultiplexerRegistry {
    val defaultType: MultiplexerType = MultiplexerType.Tmux

    private class Entry(val ownerId: String, val provider: MultiplexerProvider)

    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private val _providers = MutableStateFlow<List<MultiplexerProvider>>(emptyList())

    val providers: StateFlow<List<MultiplexerProvider>> = _providers.asStateFlow()

    fun forType(type: MultiplexerType): MultiplexerProvider? =
        entries.value.firstOrNull { it.provider.id == type.id }?.provider

    @Synchronized
    fun register(ownerId: String, provider: MultiplexerProvider) {
        val existing = entries.value.firstOrNull { it.provider.id == provider.id }
        check(existing == null) {
            "multiplexer '${provider.id}' is already provided by '${existing?.ownerId}'"
        }
        publish(entries.value + Entry(ownerId, provider))
    }

    @Synchronized
    fun unregisterOwner(ownerId: String) {
        publish(entries.value.filterNot { it.ownerId == ownerId })
    }

    private fun publish(next: List<Entry>) {
        entries.value = next
        _providers.value = next.map { it.provider }
    }
}
