package com.jossephus.chuchu.ui.terminal

import java.util.UUID

data class TerminalCustomAction(
    val id: String = "",
    val label: String,
    val payload: String,
)

data class TerminalCustomKeyGroup(
    val keyLabel: String,
    val actions: List<TerminalCustomAction>,
)

enum class CustomActionModifier(val label: String, val flag: String) {
    Ctrl("Ctrl", "ctrl"),
    Alt("Alt", "alt"),
    Shift("Shift", "shift"),
    Cmd("Cmd", "cmd"),
    Enter("Enter", "enter"),
}

data class DecodedCustomActionValue(
    val text: String,
    val modifiers: Set<CustomActionModifier>,
)

private const val MOD_PREFIX = "[[chu_mods:"
private const val MOD_SUFFIX = "]]"
private const val ACTION_ID_PREFIX = "id:"

private val ACTION_ID_PATTERN =
    Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{12}$",
    )

fun legacyActionId(groupLabel: String, label: String, payload: String): String =
    UUID.nameUUIDFromBytes("$groupLabel::$label::$payload".toByteArray(Charsets.UTF_8)).toString()

fun encodeCustomActionValue(baseValue: String, modifiers: Set<CustomActionModifier>): String {
    if (baseValue.isEmpty()) return ""
    if (modifiers.isEmpty()) return baseValue
    val flags = CustomActionModifier.entries
        .filter { it in modifiers }
        .map { it.flag }
    return "$MOD_PREFIX${flags.joinToString(",")}$MOD_SUFFIX$baseValue"
}

fun decodeCustomActionValue(payload: String): DecodedCustomActionValue {
    if (!payload.startsWith(MOD_PREFIX)) {
        return DecodedCustomActionValue(text = payload, modifiers = emptySet())
    }
    val suffixIndex = payload.indexOf(MOD_SUFFIX)
    if (suffixIndex <= MOD_PREFIX.length) {
        return DecodedCustomActionValue(text = payload, modifiers = emptySet())
    }
    val flags = payload
        .substring(MOD_PREFIX.length, suffixIndex)
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()
    val modifiers = CustomActionModifier.entries
        .filter { it.flag in flags }
        .toSet()
    return DecodedCustomActionValue(
        text = payload.substring(suffixIndex + MOD_SUFFIX.length),
        modifiers = modifiers,
    )
}

fun modifierStateForCustomAction(modifiers: Set<CustomActionModifier>): ModifierState {
    return ModifierState(
        ctrl = CustomActionModifier.Ctrl in modifiers,
        alt = CustomActionModifier.Alt in modifiers,
        shift = CustomActionModifier.Shift in modifiers,
        cmd = CustomActionModifier.Cmd in modifiers,
    )
}

fun resolveCustomActionPayload(
    actionId: String?,
    groups: List<TerminalCustomKeyGroup>,
    appendEnter: Boolean = false,
): String {
    if (actionId.isNullOrBlank()) return ""
    val action =
        groups.asSequence().flatMap { it.actions.asSequence() }
            .firstOrNull { it.id == actionId } ?: return ""
    val decoded = decodeCustomActionValue(action.payload)
    val text = modifierStateForCustomAction(
        decoded.modifiers - CustomActionModifier.Enter,
    ).applyToText(decoded.text)
    val shouldAppendEnter = appendEnter || CustomActionModifier.Enter in decoded.modifiers
    return text + if (shouldAppendEnter) "\n" else ""
}

private fun escapeActionField(value: String): String =
    buildString {
        value.forEach { char ->
            when (char) {
                '%' -> append("%25")
                ':' -> append("%3A")
                '|' -> append("%7C")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                else -> append(char)
            }
        }
    }

private fun unescapeActionField(value: String): String {
    val result = StringBuilder()
    var index = 0
    while (index < value.length) {
        if (value[index] == '%' && index + 2 < value.length) {
            when (value.substring(index + 1, index + 3).uppercase()) {
                "25" -> {
                    result.append('%')
                    index += 3
                    continue
                }
                "3A" -> {
                    result.append(':')
                    index += 3
                    continue
                }
                "7C" -> {
                    result.append('|')
                    index += 3
                    continue
                }
                "0A" -> {
                    result.append('\n')
                    index += 3
                    continue
                }
                "0D" -> {
                    result.append('\r')
                    index += 3
                    continue
                }
            }
        }
        result.append(value[index])
        index += 1
    }
    return result.toString()
}

object TerminalCustomActionStore {
    private val defaultGroups: List<TerminalCustomKeyGroup> = listOf(
        TerminalCustomKeyGroup(
            keyLabel = "qv",
            actions = listOf(
                TerminalCustomAction(
                    id = legacyActionId("qv", "qv", ":q"),
                    label = "qv",
                    payload = ":q",
                ),
            ),
        ),
    )

    fun defaultGroups(): List<TerminalCustomKeyGroup> = defaultGroups

    fun normalize(groups: List<TerminalCustomKeyGroup>): List<TerminalCustomKeyGroup> {
        val seen = LinkedHashSet<String>()
        val normalized = mutableListOf<TerminalCustomKeyGroup>()
        groups.forEach { group ->
            val key = group.keyLabel.trim()
            if (key.isEmpty() || key in seen) return@forEach
            val actions = group.actions.mapNotNull { action ->
                val label = action.label.trim()
                val payload = action.payload
                if (label.isEmpty() || payload.isEmpty()) return@mapNotNull null
                val id = action.id.ifBlank { legacyActionId(key, label, payload) }
                TerminalCustomAction(id = id, label = label, payload = payload)
            }
            if (actions.isEmpty()) return@forEach
            seen += key
            normalized += TerminalCustomKeyGroup(keyLabel = key, actions = actions)
        }
        return normalized
    }

    fun parse(raw: String?): List<TerminalCustomKeyGroup> {
        if (raw == null) return defaultGroups
        if (raw.isBlank()) return emptyList()
        val parsed = raw
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val keySplit = line.split("=", limit = 2)
                if (keySplit.size != 2) return@mapNotNull null
                val keyLabel = keySplit[0].trim()
                val actionSection = keySplit[1]
                if (keyLabel.isEmpty()) return@mapNotNull null
                val actions = actionSection
                    .split("|")
                    .mapNotNull { actionToken ->
                        val newParts = actionToken.split("::", limit = 3)
                        val newId =
                            newParts.firstOrNull()?.removePrefix(ACTION_ID_PREFIX).orEmpty()
                        val (id, label, payload) =
                            if (
                                newParts.size == 3 &&
                                newParts[0].startsWith(ACTION_ID_PREFIX) &&
                                ACTION_ID_PATTERN.matches(newId)
                            ) {
                                Triple(
                                    newId,
                                    unescapeActionField(newParts[1].trim()),
                                    unescapeActionField(newParts[2]),
                                )
                            } else {
                                val legacyParts = actionToken.split("::", limit = 2)
                                if (legacyParts.size != 2) return@mapNotNull null
                                val legacyLabel = legacyParts[0].trim()
                                val legacyPayload = legacyParts[1]
                                Triple("", legacyLabel, legacyPayload)
                            }
                        if (label.isEmpty() || payload.isEmpty()) return@mapNotNull null
                        TerminalCustomAction(
                            id = id.ifBlank { legacyActionId(keyLabel, label, payload) },
                            label = label,
                            payload = payload,
                        )
                    }
                if (actions.isEmpty()) return@mapNotNull null
                TerminalCustomKeyGroup(keyLabel = keyLabel, actions = actions)
            }
            .toList()

        return normalize(parsed).ifEmpty { defaultGroups }
    }

    fun serialize(groups: List<TerminalCustomKeyGroup>): String {
        return normalize(groups)
            .joinToString(separator = "\n") { group ->
                val actions = group.actions.joinToString(separator = "|") { action ->
                    val id = action.id.ifBlank {
                        legacyActionId(group.keyLabel, action.label, action.payload)
                    }
                    "$ACTION_ID_PREFIX$id::${escapeActionField(action.label)}::" +
                        escapeActionField(action.payload)
                }
                "${group.keyLabel}=$actions"
            }
    }
}
