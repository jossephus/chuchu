package com.jossephus.chuchu.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Registry<T : Any>(
    private val kind: String,
    private val idOf: (T) -> String,
) {
    private val owners = HashMap<String, String>()
    private val _items = MutableStateFlow<List<T>>(emptyList())

    val items: StateFlow<List<T>> = _items.asStateFlow()

    operator fun get(id: String?): T? = id?.let { wanted -> _items.value.firstOrNull { idOf(it) == wanted } }

    @Synchronized
    fun register(ownerId: String, item: T) {
        val id = idOf(item)
        check(id !in owners) { "$kind '$id' is already provided by '${owners[id]}'" }
        owners[id] = ownerId
        _items.value = _items.value + item
    }

    @Synchronized
    fun unregisterOwner(ownerId: String) {
        val removed = owners.filterValues { it == ownerId }.keys.toSet()
        owners.keys.removeAll(removed)
        _items.value = _items.value.filterNot { idOf(it) in removed }
    }
}
