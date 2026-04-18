package com.jossephus.chuchu.ui.terminal

enum class TerminalModifier {
    Ctrl,
    Alt,
    Shift,
    Cmd,
}

data class ModifierState(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val cmd: Boolean = false,
) {
    fun toggle(modifier: TerminalModifier): ModifierState = when (modifier) {
        TerminalModifier.Ctrl -> copy(ctrl = !ctrl)
        TerminalModifier.Alt -> copy(alt = !alt)
        TerminalModifier.Shift -> copy(shift = !shift)
        TerminalModifier.Cmd -> copy(cmd = !cmd)
    }

    fun isEnabled(modifier: TerminalModifier): Boolean = when (modifier) {
        TerminalModifier.Ctrl -> ctrl
        TerminalModifier.Alt -> alt
        TerminalModifier.Shift -> shift
        TerminalModifier.Cmd -> cmd
    }

    fun hasActiveModifiers(): Boolean = ctrl || alt || shift || cmd

    fun reset(): ModifierState = ModifierState()

    fun terminalMods(): Int {
        var mods = 0
        if (shift) mods = mods or (1 shl 0) // shift
        if (ctrl) mods = mods or (1 shl 1) // ctrl
        if (alt) mods = mods or (1 shl 2)  // alt
        if (cmd) mods = mods or (1 shl 3)  // super
        return mods
    }

    fun applyToText(text: String): String {
        if (text.isEmpty()) return text

        val first = if (ctrl) ctrlModifiedChar(text[0]) else text[0].toString()
        val metaWrapped = if (alt || cmd) "\u001b$first" else first
        return metaWrapped + text.substring(1)
    }

    private fun ctrlModifiedChar(char: Char): String {
        val code = when (char) {
            '@' -> 0
            '[' -> 27
            '\\' -> 28
            ']' -> 29
            '^' -> 30
            '_' -> 31
            in 'a'..'z' -> char.code - 96
            in 'A'..'Z' -> char.code - 64
            else -> char.code
        }
        return code.toChar().toString()
    }
}

enum class TerminalSpecialKey(
    val label: String,
    val engineKey: Int,
) {
    Escape("Esc", GhosttyKey.escape),
    Tab("Tab", GhosttyKey.tab),
    Up("↑", GhosttyKey.arrowUp),
    Down("↓", GhosttyKey.arrowDown),
    Left("←", GhosttyKey.arrowLeft),
    Right("→", GhosttyKey.arrowRight),
    Home("Home", GhosttyKey.home),
    End("End", GhosttyKey.end),
    PageUp("PgUp", GhosttyKey.pageUp),
    PageDown("PgDn", GhosttyKey.pageDown),
    Insert("Ins", GhosttyKey.insert),
    Delete("Del", GhosttyKey.delete),
    F1("F1", GhosttyKey.f1),
    F2("F2", GhosttyKey.f2),
    F3("F3", GhosttyKey.f3),
    F4("F4", GhosttyKey.f4),
    F5("F5", GhosttyKey.f5),
    F6("F6", GhosttyKey.f6),
    F7("F7", GhosttyKey.f7),
    F8("F8", GhosttyKey.f8),
    F9("F9", GhosttyKey.f9),
    F10("F10", GhosttyKey.f10),
    F11("F11", GhosttyKey.f11),
    F12("F12", GhosttyKey.f12),
}

sealed interface AccessoryAction {
    data class ToggleModifier(val modifier: TerminalModifier) : AccessoryAction

    data class SendSpecialKey(val key: TerminalSpecialKey) : AccessoryAction

    data class SendText(val text: String) : AccessoryAction

    data object Paste : AccessoryAction
}

data class AccessoryKeyItem(
    val id: String,
    val label: String,
    val action: AccessoryAction,
)

data class AccessoryDispatchResult(
    val modifierState: ModifierState,
    val text: String? = null,
    val specialKey: TerminalSpecialKey? = null,
    val shouldPaste: Boolean = false,
    val suppressImeInput: Boolean = false,
)

object TerminalAccessoryDispatcher {
    fun dispatch(
        action: AccessoryAction,
        modifierState: ModifierState,
    ): AccessoryDispatchResult = when (action) {
        is AccessoryAction.ToggleModifier -> AccessoryDispatchResult(
            modifierState = modifierState.toggle(action.modifier),
        )

        is AccessoryAction.SendSpecialKey -> AccessoryDispatchResult(
            modifierState = modifierState.reset(),
            specialKey = action.key,
            suppressImeInput = true,
        )

        is AccessoryAction.SendText -> AccessoryDispatchResult(
            modifierState = modifierState.reset(),
            text = modifierState.applyToText(action.text),
        )

        AccessoryAction.Paste -> AccessoryDispatchResult(
            modifierState = modifierState,
            shouldPaste = true,
        )
    }
}

object TerminalAccessoryLayoutStore {
    private val catalogItems: List<AccessoryKeyItem> = listOf(
        AccessoryKeyItem("tab", "Tab", AccessoryAction.SendSpecialKey(TerminalSpecialKey.Tab)),
        AccessoryKeyItem("escape", "Esc", AccessoryAction.SendSpecialKey(TerminalSpecialKey.Escape)),
        AccessoryKeyItem("ctrl", "Ctrl", AccessoryAction.ToggleModifier(TerminalModifier.Ctrl)),
        AccessoryKeyItem("cmd", "Cmd", AccessoryAction.ToggleModifier(TerminalModifier.Cmd)),
        AccessoryKeyItem("alt", "Alt", AccessoryAction.ToggleModifier(TerminalModifier.Alt)),
        AccessoryKeyItem("shift", "Shift", AccessoryAction.ToggleModifier(TerminalModifier.Shift)),
        AccessoryKeyItem("up", TerminalSpecialKey.Up.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.Up)),
        AccessoryKeyItem("down", TerminalSpecialKey.Down.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.Down)),
        AccessoryKeyItem("left", TerminalSpecialKey.Left.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.Left)),
        AccessoryKeyItem("right", TerminalSpecialKey.Right.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.Right)),
        AccessoryKeyItem("home", TerminalSpecialKey.Home.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.Home)),
        AccessoryKeyItem("end", TerminalSpecialKey.End.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.End)),
        AccessoryKeyItem("page_up", TerminalSpecialKey.PageUp.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.PageUp)),
        AccessoryKeyItem("page_down", TerminalSpecialKey.PageDown.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.PageDown)),
        AccessoryKeyItem("insert", TerminalSpecialKey.Insert.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.Insert)),
        AccessoryKeyItem("delete", TerminalSpecialKey.Delete.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.Delete)),
        AccessoryKeyItem("f1", TerminalSpecialKey.F1.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.F1)),
        AccessoryKeyItem("f2", TerminalSpecialKey.F2.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.F2)),
        AccessoryKeyItem("f3", TerminalSpecialKey.F3.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.F3)),
        AccessoryKeyItem("f4", TerminalSpecialKey.F4.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.F4)),
        AccessoryKeyItem("f5", TerminalSpecialKey.F5.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.F5)),
        AccessoryKeyItem("f6", TerminalSpecialKey.F6.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.F6)),
        AccessoryKeyItem("f7", TerminalSpecialKey.F7.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.F7)),
        AccessoryKeyItem("f8", TerminalSpecialKey.F8.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.F8)),
        AccessoryKeyItem("f9", TerminalSpecialKey.F9.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.F9)),
        AccessoryKeyItem("f10", TerminalSpecialKey.F10.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.F10)),
        AccessoryKeyItem("f11", TerminalSpecialKey.F11.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.F11)),
        AccessoryKeyItem("f12", TerminalSpecialKey.F12.label, AccessoryAction.SendSpecialKey(TerminalSpecialKey.F12)),
        AccessoryKeyItem("paste", "Paste", AccessoryAction.Paste),
    )

    private val catalogById: Map<String, AccessoryKeyItem> = catalogItems.associateBy { it.id }

    private val defaultLayoutIds: List<String> = catalogItems.map { it.id }

    fun defaultLayout(): List<AccessoryKeyItem> = resolveLayout(defaultLayoutIds)

    private fun resolveLayout(ids: List<String>): List<AccessoryKeyItem> {
        val resolved = ids.mapNotNull(catalogById::get)
        return if (resolved.isEmpty()) {
            defaultLayoutIds.mapNotNull(catalogById::get)
        } else {
            resolved
        }
    }
}
