package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.plugin.api.ProgramState
import com.jossephus.chuchu.plugin.api.ProgramStatus
import com.jossephus.chuchu.plugin.api.mostUrgent

class ProgramStatusRecords {
    private val records = LinkedHashMap<String, ProgramStatus>()

    val current: ProgramStatus?
        get() {
            val top = records.values.mostUrgent() ?: return null
            return if (top.app != null) top else top.copy(app = inheritedApp(top.id))
        }

    fun apply(reports: ByteArray) {
        reports.toString(Charsets.UTF_8)
            .split(RECORD_SEPARATOR)
            .filter { it.isNotEmpty() }
            .forEach(::applyReport)
    }

    fun clear() {
        records.clear()
    }

    private fun applyReport(line: String) {
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size != FIELD_COUNT) return
        val (stateName, id, app, kind, progress) = fields
        if (stateName == CLEAR) {
            if (id.isEmpty()) records.clear() else records.keys.removeAll { it == id || it.startsWith("$id/") }
            return
        }
        if (stateName == PROMPT) {
            records.values.removeAll { it.state == ProgramState.Working || it.state == ProgramState.Blocked }
            return
        }
        val state = ProgramState.entries.firstOrNull { it.name.equals(stateName, ignoreCase = true) } ?: return
        if (records.remove(id) == null && records.size >= MAX_RECORDS) records.remove(records.keys.first())
        records[id] =
            ProgramStatus(
                state = state,
                id = id,
                app = app.ifEmpty { null },
                kind = kind.ifEmpty { null },
                progress = progress.toIntOrNull(),
                title = fields[5].ifEmpty { null },
                message = fields[6].ifEmpty { null },
            )
    }

    private fun inheritedApp(id: String): String? {
        var parent = id
        while (parent.isNotEmpty()) {
            parent = parent.substringBeforeLast('/', missingDelimiterValue = "")
            records[parent]?.app?.let { return it }
        }
        return null
    }

    private companion object {
        const val FIELD_SEPARATOR = '\u001f'
        const val RECORD_SEPARATOR = '\u001e'
        const val FIELD_COUNT = 7
        const val CLEAR = "clear"
        const val PROMPT = "prompt"
        const val MAX_RECORDS = 256
    }
}
