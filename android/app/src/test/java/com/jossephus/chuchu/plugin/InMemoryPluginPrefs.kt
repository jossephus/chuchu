package com.jossephus.chuchu.plugin

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class InMemoryPluginPrefs : PluginPrefs {
    private val values = LinkedHashMap<String, String>()
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun get(key: String): String? = synchronized(values) { values[key] }

    override fun put(key: String, value: String?) {
        synchronized(values) { if (value == null) values.remove(key) else values[key] = value }
        _changes.tryEmit(key)
    }

    override fun keys(): Set<String> = synchronized(values) { values.keys.toSet() }

    override val changes: Flow<String> = _changes
}
