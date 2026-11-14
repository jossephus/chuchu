package com.jossephus.chuchu.plugin.api

enum class ProgramState(val urgency: Int) {
    Idle(0),
    Done(1),
    Working(2),
    Error(3),
    Blocked(4),
    ;

    companion object {
        fun parse(name: String?): ProgramState = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Idle
    }
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

fun Iterable<ProgramStatus>.mostUrgent(): ProgramStatus? {
    val urgency = maxOfOrNull { it.state.urgency } ?: return null
    return last { it.state.urgency == urgency }.takeIf { it.state != ProgramState.Idle }
}
