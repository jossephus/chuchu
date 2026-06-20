package com.jossephus.chuchu.ui.terminal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class TerminalCustomAction(
    val label: String,
    val payload: String,
    val shortcut: String? = null,
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

object TerminalCustomActionStore {
    private val defaultGroups: List<TerminalCustomKeyGroup> = listOf(
        TerminalCustomKeyGroup(
            keyLabel = "qv",
            actions = listOf(TerminalCustomAction(label = "qv", payload = ":q")),
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
                val shortcut = action.shortcut?.trim()?.takeIf { it.isNotEmpty() }
                TerminalCustomAction(label = label, payload = payload, shortcut = shortcut)
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
        val parsed = try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { groupIndex ->
                val groupObj = array.optJSONObject(groupIndex) ?: return@mapNotNull null
                val keyLabel = groupObj.optString("key").trim()
                if (keyLabel.isEmpty()) return@mapNotNull null
                val actionsArray = groupObj.optJSONArray("actions") ?: return@mapNotNull null
                val actions = (0 until actionsArray.length()).mapNotNull { actionIndex ->
                    val actionObj = actionsArray.optJSONObject(actionIndex)
                        ?: return@mapNotNull null
                    val label = actionObj.optString("label").trim()
                    val payload = actionObj.optString("payload")
                    if (label.isEmpty() || payload.isEmpty()) return@mapNotNull null
                    val shortcut = actionObj.optString("shortcut").trim().takeIf { it.isNotEmpty() }
                    TerminalCustomAction(label = label, payload = payload, shortcut = shortcut)
                }
                if (actions.isEmpty()) return@mapNotNull null
                TerminalCustomKeyGroup(keyLabel = keyLabel, actions = actions)
            }
        } catch (_: JSONException) {
            // Migration: actions saved before the JSON switch used a delimited
            // "keyLabel=label::payload|..." string. The shortcut field is new, so
            // legacy entries never carried one — split on the first "::" only, which
            // preserves any "::" inside the payload. Re-saving rewrites it as JSON.
            parseLegacy(raw)
        }

        return normalize(parsed).ifEmpty { defaultGroups }
    }

    private fun parseLegacy(raw: String): List<TerminalCustomKeyGroup> {
        return raw
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
                        val parts = actionToken.split("::", limit = 2)
                        if (parts.size != 2) return@mapNotNull null
                        val label = parts[0].trim()
                        val payload = parts[1]
                        if (label.isEmpty() || payload.isEmpty()) return@mapNotNull null
                        TerminalCustomAction(label = label, payload = payload)
                    }
                if (actions.isEmpty()) return@mapNotNull null
                TerminalCustomKeyGroup(keyLabel = keyLabel, actions = actions)
            }
            .toList()
    }

    fun serialize(groups: List<TerminalCustomKeyGroup>): String {
        val normalized = normalize(groups)
        if (normalized.isEmpty()) return ""
        val array = JSONArray()
        normalized.forEach { group ->
            val actionsArray = JSONArray()
            group.actions.forEach { action ->
                val actionObj = JSONObject()
                actionObj.put("label", action.label)
                actionObj.put("payload", action.payload)
                if (action.shortcut != null) {
                    actionObj.put("shortcut", action.shortcut)
                }
                actionsArray.put(actionObj)
            }
            array.put(JSONObject().put("key", group.keyLabel).put("actions", actionsArray))
        }
        return array.toString()
    }
}
