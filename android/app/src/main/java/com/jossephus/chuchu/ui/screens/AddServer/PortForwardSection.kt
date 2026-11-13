package com.jossephus.chuchu.ui.screens.AddServer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.R
import com.jossephus.chuchu.model.PortForward
import com.jossephus.chuchu.model.PortForwardProblem
import com.jossephus.chuchu.model.PortForwardType
import com.jossephus.chuchu.model.label
import com.jossephus.chuchu.model.problem
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuSegmentedControl
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

private const val NEW_FORWARD = -1

@Composable
internal fun PortForwardSection(
    forwards: List<PortForward>,
    onSave: (Int?, PortForward) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    SectionHeader(stringResource(R.string.section_port_forwarding))
    forwards.forEachIndexed { index, forward ->
        if (editingIndex == index) {
            key(index) {
                PortForwardEditor(
                    initial = forward,
                    others = forwards.filterIndexed { i, _ -> i != index },
                    onSave = {
                        onSave(index, it)
                        editingIndex = null
                    },
                    onCancel = { editingIndex = null },
                    onRemove = {
                        onRemove(index)
                        editingIndex = null
                    },
                )
            }
        } else {
            ChuText(
                forward.label,
                style = typography.body,
                color = colors.textPrimary,
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { editingIndex = index }
                        .padding(vertical = 6.dp),
            )
        }
    }
    if (editingIndex == NEW_FORWARD) {
        key(NEW_FORWARD) {
            PortForwardEditor(
                initial = null,
                others = forwards,
                onSave = {
                    onSave(null, it)
                    editingIndex = null
                },
                onCancel = { editingIndex = null },
                onRemove = null,
            )
        }
    } else if (editingIndex == null) {
        ChuButton(
            onClick = { editingIndex = NEW_FORWARD },
            variant = ChuButtonVariant.Ghost,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            ChuText(stringResource(R.string.port_forward_add), style = typography.labelSmall, color = colors.accent)
        }
    }
    ChuText(
        stringResource(R.string.port_forward_lifecycle_hint),
        style = typography.bodySmall,
        color = colors.textMuted,
    )
}

@Composable
private fun PortForwardEditor(
    initial: PortForward?,
    others: List<PortForward>,
    onSave: (PortForward) -> Unit,
    onCancel: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    var type by rememberSaveable { mutableStateOf(initial?.type ?: PortForwardType.Local) }
    var localPort by rememberSaveable { mutableStateOf(initial?.localPort?.toString().orEmpty()) }
    var remotePort by rememberSaveable { mutableStateOf(initial?.remotePort?.toString().orEmpty()) }
    var host by rememberSaveable {
        mutableStateOf(initial?.destinationHost ?: PortForward.DEFAULT_DESTINATION_HOST)
    }
    var showProblem by rememberSaveable { mutableStateOf(false) }
    val draft =
        PortForward(
            type = type,
            localPort = localPort.toIntOrNull() ?: 0,
            destinationHost = host.trim(),
            remotePort = remotePort.toIntOrNull() ?: 0,
        )
    val problem = draft.problem(others)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChuSegmentedControl(
            options = listOf(PortForwardType.Local, PortForwardType.Remote),
            labels =
                mapOf(
                    PortForwardType.Local to stringResource(R.string.port_forward_type_local),
                    PortForwardType.Remote to stringResource(R.string.port_forward_type_remote),
                ),
            selected = type,
            onSelect = { type = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PortField(
                value = localPort,
                onValueChange = { localPort = it },
                label = stringResource(R.string.port_forward_local_port),
                modifier = Modifier.weight(1f),
            )
            PortField(
                value = remotePort,
                onValueChange = { remotePort = it },
                label = stringResource(R.string.port_forward_remote_port),
                modifier = Modifier.weight(1f),
            )
        }
        ChuTextField(
            value = host,
            onValueChange = { host = it },
            label = stringResource(R.string.port_forward_destination_host),
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        ChuText(
            forwardHint(type, localPort, host, remotePort),
            style = typography.bodySmall,
            color = colors.textMuted,
        )
        if (showProblem && problem != null) {
            ChuText(problemMessage(problem), style = typography.bodySmall, color = colors.error)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            if (onRemove != null) {
                EditorButton(stringResource(R.string.port_forward_remove), colors.error, onRemove)
            }
            EditorButton(stringResource(R.string.port_forward_cancel), colors.textMuted, onCancel)
            EditorButton(stringResource(R.string.port_forward_save), colors.accent) {
                if (problem == null) onSave(draft) else showProblem = true
            }
        }
    }
}

@Composable
private fun PortField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier) {
    ChuTextField(
        value = value,
        onValueChange = { next -> onValueChange(next.filter(Char::isDigit).take(5)) },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        autoFocus = false,
        modifier = modifier,
    )
}

@Composable
private fun EditorButton(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    ChuButton(
        onClick = onClick,
        variant = ChuButtonVariant.Ghost,
        bracketed = true,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        ChuText(text, style = ChuTypography.current.labelSmall, color = color)
    }
}

@Composable
private fun forwardHint(type: PortForwardType, localPort: String, host: String, remotePort: String): String {
    val local = localPort.ifBlank { "…" }
    val remote = remotePort.ifBlank { "…" }
    val destination = host.trim().ifBlank { "…" }
    return when (type) {
        PortForwardType.Local -> stringResource(R.string.port_forward_local_hint, local, destination, remote)
        PortForwardType.Remote -> stringResource(R.string.port_forward_remote_hint, remote, destination, local)
    }
}

@Composable
private fun problemMessage(problem: PortForwardProblem): String =
    stringResource(
        when (problem) {
            PortForwardProblem.InvalidLocalPort -> R.string.port_forward_problem_local_port
            PortForwardProblem.PrivilegedLocalPort -> R.string.port_forward_problem_privileged
            PortForwardProblem.InvalidRemotePort -> R.string.port_forward_problem_remote_port
            PortForwardProblem.MissingHost -> R.string.port_forward_problem_host
            PortForwardProblem.DuplicateListenPort -> R.string.port_forward_problem_duplicate
        }
    )
