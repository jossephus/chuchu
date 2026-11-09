package com.jossephus.chuchu.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.jossephus.chuchu.R
import com.jossephus.chuchu.service.terminal.HostKeyPrompt
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun HostKeyPromptDialog(prompt: HostKeyPrompt, onDecision: (Boolean) -> Unit) {
    val context = LocalContext.current
    ChuDialog(
        onDismiss = { onDecision(false) },
        title = stringResource(R.string.terminal_hostkey_title),
        confirmLabel = stringResource(R.string.action_accept),
        dismissLabel = stringResource(R.string.action_reject),
        onConfirm = { onDecision(true) },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val previous = prompt.previousFingerprint
        val message = buildString {
            append(context.getString(R.string.terminal_hostkey_host, prompt.host, prompt.port.toString()))
            append('\n')
            append(context.getString(R.string.terminal_hostkey_algorithm, prompt.algorithm))
            append('\n')
            if (previous != null) {
                append(context.getString(R.string.terminal_hostkey_changed_warning))
                append('\n')
                append(context.getString(R.string.terminal_hostkey_old, previous))
                append('\n')
            }
            append(context.getString(R.string.terminal_hostkey_new, prompt.fingerprint))
        }
        ChuText(message, style = ChuTypography.current.body)
    }
}
