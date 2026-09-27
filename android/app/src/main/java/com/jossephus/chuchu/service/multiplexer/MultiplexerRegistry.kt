package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.plugin.api.MultiplexerProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Multiplexers contributed by plugins (tmux and zmx are built-in plugins). Global because
 * the session engine, host editor and backups resolve host multiplexer ids from anywhere;
 * only [com.jossephus.chuchu.plugin.PluginManager] registers and removes entries.
 */
object MultiplexerRegistry {
    val defaultType: MultiplexerType = MultiplexerType.Tmux

    private class Entry(val ownerId: String, val provider: MultiplexerProvider)

    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private val _providers = MutableStateFlow<List<MultiplexerProvider>>(emptyList())

    /** Registered providers in registration order, for pickers. */
    val providers: StateFlow<List<MultiplexerProvider>> = _providers.asStateFlow()

    fun forType(type: MultiplexerType): MultiplexerProvider? =
        entries.value.firstOrNull { it.provider.id == type.id }?.provider

    /** Throws [IllegalStateException] if [provider]'s id is already registered. */
    @Synchronized
    fun register(ownerId: String, provider: MultiplexerProvider) {
        val existing = entries.value.firstOrNull { it.provider.id == provider.id }
        check(existing == null) {
            "multiplexer '${provider.id}' is already provided by '${existing?.ownerId}'"
        }
        publish(entries.value + Entry(ownerId, provider))
    }

    /** Removes everything [ownerId] registered, e.g. when its plugin is disabled. */
    @Synchronized
    fun unregisterOwner(ownerId: String) {
        publish(entries.value.filterNot { it.ownerId == ownerId })
    }

    private fun publish(next: List<Entry>) {
        entries.value = next
        _providers.value = next.map { it.provider }
    }
}
