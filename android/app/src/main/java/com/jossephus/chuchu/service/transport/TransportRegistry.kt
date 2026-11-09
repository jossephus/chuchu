package com.jossephus.chuchu.service.transport

import com.jossephus.chuchu.plugin.api.TransportProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TransportRegistry {
    private class Entry(val ownerId: String, val provider: TransportProvider)

    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private val _providers = MutableStateFlow<List<TransportProvider>>(emptyList())

    val providers: StateFlow<List<TransportProvider>> = _providers.asStateFlow()

    fun forId(id: String?): TransportProvider? =
        id?.let { wanted -> entries.value.firstOrNull { it.provider.id == wanted }?.provider }

    @Synchronized
    fun register(ownerId: String, provider: TransportProvider) {
        val existing = entries.value.firstOrNull { it.provider.id == provider.id }
        check(existing == null) {
            "transport '${provider.id}' is already provided by '${existing?.ownerId}'"
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
