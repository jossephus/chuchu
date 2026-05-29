package com.jossephus.chuchu.ui.screens.Settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.jossephus.chuchu.data.backup.BackupImportPlan
import com.jossephus.chuchu.data.backup.CHUCHU_BACKUP_MIME_TYPE
import com.jossephus.chuchu.data.backup.KeyImportAction
import com.jossephus.chuchu.model.SshKey
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuCard
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.security.VerificationResult
import com.jossephus.chuchu.ui.security.requireUserVerification
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

/** Internal step tracking for the backup sheet flow. */
private sealed interface SheetStep {
    /** Default view: key list, counts, export/import buttons. */
    data object Overview : SheetStep

    /** Show export passphrase fields. */
    data object ExportPassphrase : SheetStep

    /** Show import passphrase field. */
    data object ImportPassphrase : SheetStep

    /** Show import preview summary before confirming. */
    data class ImportPreview(val plan: BackupImportPlan) : SheetStep
}

@Composable
internal fun SshBackupSheet(
    visible: Boolean,
    viewModel: SettingsBackupViewModel,
    onDismiss: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val context = LocalContext.current

    // VM state
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val success by viewModel.success.collectAsStateWithLifecycle()
    val importPlan by viewModel.importPlan.collectAsStateWithLifecycle()
    val exportPassphrase by viewModel.exportPassphrase.collectAsStateWithLifecycle()
    val exportPassphraseConfirm by viewModel.exportPassphraseConfirm.collectAsStateWithLifecycle()
    val importPassphrase by viewModel.importPassphrase.collectAsStateWithLifecycle()

    // Sheet step – derived from VM state so they stay in sync
    val step = when {
        importPlan != null -> SheetStep.ImportPreview(importPlan!!)
        else -> {
            // Local-only steps managed inside the sheet
            null
        }
    }
    var localStep by remember { mutableStateOf<SheetStep>(SheetStep.Overview) }
    val effectiveStep: SheetStep = step ?: localStep

    // SAF launchers – stay at composable scope
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(CHUCHU_BACKUP_MIME_TYPE),
    ) { uri ->
        if (uri != null) {
            viewModel.performExport(uri)
        } else {
            // User cancelled SAF picker – clear export passphrase
            viewModel.updateExportPassphrase("")
            viewModel.updateExportPassphraseConfirm("")
        }
        localStep = SheetStep.Overview
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.performImport(uri)
        } else {
            // User cancelled SAF picker – clear import passphrase
            viewModel.updateImportPassphrase("")
        }
        localStep = SheetStep.Overview
    }

    // ── Dismiss handler ────────────────────────────────────────────────────
    fun handleDismiss() {
        viewModel.dismissSheet()
        localStep = SheetStep.Overview
        onDismiss()
    }

    fun handleBack() {
        when (effectiveStep) {
            SheetStep.Overview -> handleDismiss()
            SheetStep.ExportPassphrase -> {
                viewModel.updateExportPassphrase("")
                viewModel.updateExportPassphraseConfirm("")
                localStep = SheetStep.Overview
            }
            SheetStep.ImportPassphrase -> {
                viewModel.updateImportPassphrase("")
                localStep = SheetStep.Overview
            }
            is SheetStep.ImportPreview -> {
                viewModel.cancelImport()
                localStep = SheetStep.Overview
            }
        }
    }

    BackHandler(enabled = visible) {
        handleBack()
    }

    // ── Scrim + Sheet ──────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim overlay
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { handleDismiss() },
                    ),
            )
        }

        // Sheet panel
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .background(colors.surfaceVariant)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ── Header ────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChuText("ssh keys & backups", style = typography.headline)
                        ChuText(
                            text = when (effectiveStep) {
                                is SheetStep.Overview -> "Backup your SSH keys and server profiles."
                                is SheetStep.ExportPassphrase -> "Create an encrypted backup file."
                                is SheetStep.ImportPassphrase -> "Restore from an encrypted backup file."
                                is SheetStep.ImportPreview -> "Review what will be imported."
                            },
                            style = typography.body,
                            color = colors.textSecondary,
                        )
                    }

                    ChuButton(
                        onClick = { handleDismiss() },
                        variant = ChuButtonVariant.Ghost,
                        bracketed = true,
                        borderColor = colors.textMuted,
                        contentPadding = PaddingValues(6.dp),
                    ) {
                        ChuText("x", style = typography.label, color = colors.textMuted)
                    }
                }

                // ── Scrollable content ─────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (effectiveStep) {
                        is SheetStep.Overview -> OverviewContent(
                            keys = keys,
                            hostCount = hosts.size,
                            busy = isBusy,
                            onExport = { localStep = SheetStep.ExportPassphrase },
                            onImport = { localStep = SheetStep.ImportPassphrase },
                        )

                        is SheetStep.ExportPassphrase -> ExportPassphraseContent(
                            passphrase = exportPassphrase,
                            passphraseConfirm = exportPassphraseConfirm,
                            onPassphraseChange = viewModel::updateExportPassphrase,
                            onPassphraseConfirmChange = viewModel::updateExportPassphraseConfirm,
                            valid = viewModel.exportPassphraseValid(),
                            busy = isBusy,
                            onExport = {
                                requireUserVerification(
                                    context = context,
                                    title = "Export SSH backup",
                                    subtitle = "Authenticate to create an encrypted backup",
                                ) { result ->
                                    if (result == VerificationResult.Success || result == VerificationResult.Unavailable) {
                                        exportLauncher.launch(defaultBackupFileName())
                                    }
                                    // On Failed, stay on the same step
                                }
                            },
                            onCancel = {
                                viewModel.updateExportPassphrase("")
                                viewModel.updateExportPassphraseConfirm("")
                                localStep = SheetStep.Overview
                            },
                        )

                        is SheetStep.ImportPassphrase -> ImportPassphraseContent(
                            passphrase = importPassphrase,
                            onPassphraseChange = viewModel::updateImportPassphrase,
                            valid = viewModel.importPassphraseValid(),
                            busy = isBusy,
                            onImport = {
                                requireUserVerification(
                                    context = context,
                                    title = "Import SSH backup",
                                    subtitle = "Authenticate to restore an encrypted backup",
                                ) { result ->
                                    if (result == VerificationResult.Success || result == VerificationResult.Unavailable) {
                                        importLauncher.launch(arrayOf(CHUCHU_BACKUP_MIME_TYPE, "*/*"))
                                    }
                                    // On Failed, stay on the same step
                                }
                            },
                            onCancel = {
                                viewModel.updateImportPassphrase("")
                                localStep = SheetStep.Overview
                            },
                        )

                        is SheetStep.ImportPreview -> ImportPreviewContent(
                            plan = effectiveStep.plan,
                            busy = isBusy,
                            onConfirm = viewModel::confirmImport,
                            onCancel = {
                                viewModel.cancelImport()
                                localStep = SheetStep.Overview
                            },
                        )
                    }

                    // ── Status messages ────────────────────────────────────
                    if (isBusy) {
                        ChuCard(modifier = Modifier.fillMaxWidth()) {
                            ChuText(
                                text = "Working…",
                                style = typography.body,
                                color = colors.textMuted,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    error?.let { msg ->
                        ChuCard(modifier = Modifier.fillMaxWidth(), background = colors.error.copy(alpha = 0.12f)) {
                            ChuText(
                                text = msg,
                                style = typography.body,
                                color = colors.error,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    success?.let { msg ->
                        ChuCard(modifier = Modifier.fillMaxWidth(), background = colors.accent.copy(alpha = 0.12f)) {
                            ChuText(
                                text = msg,
                                style = typography.body,
                                color = colors.accent,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }

                // ── Bottom action (only for overview) ──────────────────────
                if (effectiveStep is SheetStep.Overview && (error != null || success != null)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChuButton(
                            onClick = {
                                viewModel.clearMessages()
                            },
                            variant = ChuButtonVariant.Outlined,
                            bracketed = true,
                            modifier = Modifier.weight(1f),
                        ) {
                            ChuText("dismiss", style = typography.label)
                        }
                    }
                }
            }
        }
    }
}

// ── Overview content ───────────────────────────────────────────────────────────

@Composable
private fun OverviewContent(
    keys: List<SshKey>,
    hostCount: Int,
    busy: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    ChuCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Summary row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ChuText("ssh keys", style = typography.label)
                    ChuText(
                        text = if (keys.isEmpty()) "none" else "${keys.size} key(s)",
                        style = typography.body,
                        color = colors.textMuted,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ChuText("host profiles", style = typography.label)
                    ChuText(
                        text = if (hostCount == 0) "none" else "$hostCount host(s)",
                        style = typography.body,
                        color = colors.textMuted,
                    )
                }
            }

            // Key list (safe metadata only)
            if (keys.isNotEmpty()) {
                ChuText(
                    text = "configured keys:",
                    style = typography.labelSmall,
                    color = colors.textSecondary,
                )
                keys.forEach { key ->
                    KeyInfoRow(key = key)
                }
            } else {
                ChuText(
                    text = "No SSH keys configured yet. Add keys in a server profile.",
                    style = typography.body,
                    color = colors.textSecondary,
                )
            }

            // Warning about encrypted secrets
            ChuText(
                text = "Backups include all private keys, passwords, and passphrases inside an encrypted file.",
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChuButton(
            onClick = onExport,
            enabled = !busy,
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.weight(1f),
        ) {
            ChuText("export", style = typography.label, color = colors.accent)
        }
        ChuButton(
            onClick = onImport,
            enabled = !busy,
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.weight(1f),
        ) {
            ChuText("import", style = typography.label, color = colors.accent)
        }
    }
}

// ── Key info row (safe metadata only) ──────────────────────────────────────────

@Composable
private fun KeyInfoRow(key: SshKey) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val shortPublic = publicKeyShortPrefix(key)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ChuText(text = key.name, style = typography.label)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChuText(
                    text = key.algorithm,
                    style = typography.bodySmall,
                    color = colors.textMuted,
                )
                if (shortPublic.isNotBlank()) {
                    ChuText(
                        text = shortPublic,
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
    }
}

/** Extracts a short public key prefix for safe display (never shows full PEM). */
private fun publicKeyShortPrefix(key: SshKey): String {
    val pk = key.publicKeyOpenSsh.trim()
    if (pk.isEmpty()) return ""
    // Show first line break or first 32 chars, whichever is shorter
    val firstLine = pk.substringBefore('\n').trim()
    return if (firstLine.length <= 36) firstLine else firstLine.take(32) + "…"
}

// ── Export passphrase content ───────────────────────────────────────────────────

@Composable
private fun ExportPassphraseContent(
    passphrase: String,
    passphraseConfirm: String,
    onPassphraseChange: (String) -> Unit,
    onPassphraseConfirmChange: (String) -> Unit,
    valid: Boolean,
    busy: Boolean,
    onExport: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    ChuCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChuTextField(
                value = passphrase,
                onValueChange = onPassphraseChange,
                label = "encryption passphrase",
                placeholder = "at least $BACKUP_MIN_PASSPHRASE_LENGTH characters",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                autoFocus = true,
            )
            ChuTextField(
                value = passphraseConfirm,
                onValueChange = onPassphraseConfirmChange,
                label = "confirm passphrase",
                placeholder = "re-enter passphrase",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                autoFocus = false,
            )
            if (passphrase.length in 1 until BACKUP_MIN_PASSPHRASE_LENGTH) {
                ChuText(
                    text = "Passphrase must be at least $BACKUP_MIN_PASSPHRASE_LENGTH characters.",
                    style = typography.bodySmall,
                    color = colors.textMuted,
                )
            } else if (passphrase.length >= BACKUP_MIN_PASSPHRASE_LENGTH && !valid) {
                ChuText(
                    text = "Passphrases do not match.",
                    style = typography.bodySmall,
                    color = colors.error,
                )
            }
            ChuText(
                text = "You will be prompted to choose a save location and verify your identity.",
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChuButton(
            onClick = onCancel,
            variant = ChuButtonVariant.Ghost,
            bracketed = true,
            borderColor = colors.textMuted,
            modifier = Modifier.weight(1f),
        ) {
            ChuText("cancel", style = typography.label, color = colors.textMuted)
        }
        ChuButton(
            onClick = onExport,
            enabled = !busy && valid,
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            borderColor = colors.accent,
            modifier = Modifier.weight(1f),
        ) {
            ChuText("export", style = typography.label, color = colors.accent)
        }
    }
}

// ── Import passphrase content ───────────────────────────────────────────────────

@Composable
private fun ImportPassphraseContent(
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    valid: Boolean,
    busy: Boolean,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    ChuCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChuTextField(
                value = passphrase,
                onValueChange = onPassphraseChange,
                label = "backup passphrase",
                placeholder = "passphrase used during export",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                autoFocus = true,
            )
            ChuText(
                text = "You will be prompted to select a backup file and verify your identity.",
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChuButton(
            onClick = onCancel,
            variant = ChuButtonVariant.Ghost,
            bracketed = true,
            borderColor = colors.textMuted,
            modifier = Modifier.weight(1f),
        ) {
            ChuText("cancel", style = typography.label, color = colors.textMuted)
        }
        ChuButton(
            onClick = onImport,
            enabled = !busy && valid,
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            borderColor = colors.accent,
            modifier = Modifier.weight(1f),
        ) {
            ChuText("import", style = typography.label, color = colors.accent)
        }
    }
}

// ── Import preview content ─────────────────────────────────────────────────────

@Composable
private fun ImportPreviewContent(
    plan: BackupImportPlan,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    ChuCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChuText("import summary", style = typography.label)

            val summaryParts = mutableListOf<String>()
            val keyInsert = plan.keyInsertCount
            val keyReuse = plan.keyReuseCount
            val keyRenamed = plan.renamedKeyCount
            val hostImported = plan.hostActions.size
            val hostRenamed = plan.renamedHostCount

            if (keyInsert > 0) summaryParts += "$keyInsert new key(s)"
            if (keyReuse > 0) summaryParts += "$keyReuse existing key(s) reused"
            if (keyRenamed > 0) summaryParts += "$keyRenamed key(s) renamed"
            if (hostImported > 0) summaryParts += "$hostImported host(s)"
            if (hostRenamed > 0) summaryParts += "$hostRenamed host(s) renamed"

            ChuText(
                text = summaryParts.joinToString(", ").ifEmpty { "Nothing to import" },
                style = typography.body,
                color = colors.textSecondary,
            )

            // Show which keys will be reused
            val reusedKeys = plan.keyActions.filterIsInstance<KeyImportAction.Reuse>()
            if (reusedKeys.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                ChuText("reused keys:", style = typography.labelSmall, color = colors.textMuted)
                reusedKeys.forEach { action ->
                    ChuText(
                        text = "  • ${action.name}",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }

            // Show which keys will be inserted
            val insertedKeys = plan.keyActions.filterIsInstance<KeyImportAction.Insert>()
            if (insertedKeys.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                ChuText("new keys:", style = typography.labelSmall, color = colors.textMuted)
                insertedKeys.forEach { action ->
                    val note = if (action.renamed) " (renamed)" else ""
                    ChuText(
                        text = "  • ${action.key.name}$note",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }

            // Show which hosts will be imported
            val renamedHosts = plan.hostActions.filter { it.renamed }
            val normalHosts = plan.hostActions.filter { !it.renamed }
            if (normalHosts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                ChuText("hosts to add:", style = typography.labelSmall, color = colors.textMuted)
                normalHosts.forEach { action ->
                    ChuText(
                        text = "  • ${action.host.name} → ${action.host.host}",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
            if (renamedHosts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                ChuText("renamed hosts:", style = typography.labelSmall, color = colors.textMuted)
                renamedHosts.forEach { action ->
                    ChuText(
                        text = "  • ${action.host.name} (conflict resolved)",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }

            ChuText(
                text = "Existing data will not be overwritten. Conflicting names will be auto-renamed.",
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChuButton(
            onClick = onCancel,
            variant = ChuButtonVariant.Ghost,
            bracketed = true,
            borderColor = colors.textMuted,
            modifier = Modifier.weight(1f),
        ) {
            ChuText("cancel", style = typography.label, color = colors.textMuted)
        }
        ChuButton(
            onClick = onConfirm,
            enabled = !busy && (plan.keyInsertCount > 0 || plan.hostActions.isNotEmpty()),
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            borderColor = colors.accent,
            modifier = Modifier.weight(1f),
        ) {
            ChuText("confirm import", style = typography.label, color = colors.accent)
        }
    }
}

private fun defaultBackupFileName(): String {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return "chuchu-ssh-hosts-backup-$date.chuchu-backup"
}
