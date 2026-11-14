package com.jossephus.chuchu.service.terminal

enum class ProgramState(val urgency: Int) {
    Idle(0),
    Done(1),
    Working(2),
    Error(3),
    Blocked(4),
}

data class ProgramStatus(
    val state: ProgramState,
    val id: String = "",
    val app: String? = null,
    val kind: String? = null,
    val progress: Int? = null,
    val title: String? = null,
    val message: String? = null,
)

class ProgramStatusRecords {
    private val records = LinkedHashMap<String, ProgramStatus>()

    val current: ProgramStatus?
        get() {
            val urgency = records.values.maxOfOrNull { it.state.urgency } ?: return null
            val top = records.values.last { it.state.urgency == urgency }
            if (top.state == ProgramState.Idle) return null
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
        val state = ProgramState.entries.firstOrNull { it.name.equals(stateName, ignoreCase = true) } ?: return
        records.remove(id)
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
    }
}
