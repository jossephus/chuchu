package com.jossephus.chuchu.ui.screens.AddServer

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuDialog
import com.jossephus.chuchu.ui.components.ChuSegmentedControl
import com.jossephus.chuchu.ui.components.ChuSwitch
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import kotlinx.coroutines.launch

@Composable
fun AddServerScreen(
    vm: AddServerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val testState by vm.testState.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val colors = ChuColors.current
    val typography = ChuTypography.current

    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val exportKeyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-pem-file"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val privateKeyPem = form.privateKeyPem
        if (privateKeyPem.isBlank()) return@rememberLauncherForActivityResult
        val ok = context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(privateKeyPem.toByteArray(Charsets.UTF_8))
            true
        } ?: false
        if (ok) {
            Toast.makeText(context, "Private key saved", Toast.LENGTH_SHORT).show()
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChuText("Add Server", style = typography.headline)

        // --- Connection ---
        SectionHeader("Connection")
        ChuTextField(
            value = form.name,
            onValueChange = vm::updateName,
            label = "Name",
            placeholder = "My server",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ChuTextField(
            value = form.host,
            onValueChange = vm::updateHost,
            label = "Host",
            placeholder = "192.168.1.10",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChuTextField(
                value = form.port,
                onValueChange = vm::updatePort,
                label = "Port",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.3f),
            )
            ChuTextField(
                value = form.username,
                onValueChange = vm::updateUsername,
                label = "Username",
                placeholder = "root",
                singleLine = true,
                modifier = Modifier.weight(0.7f),
            )
        }

        SectionDivider()

        // --- Transport ---
        SectionHeader("Transport")
        ChuSegmentedControl(
            options = listOf(Transport.SSH, Transport.TailscaleSSH),
            labels = mapOf(
                Transport.SSH to "SSH",
                Transport.TailscaleSSH to "Tailscale",
            ),
            selected = form.transport,
            onSelect = vm::updateTransport,
        )
        if (form.transport == Transport.TailscaleSSH) {
            ChuText(
                "Requires the Tailscale VPN to be active.",
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }

        SectionDivider()

        // --- Auth ---
        SectionHeader("Authentication")
        if (form.transport == Transport.TailscaleSSH) {
            ChuText(
                "Handled by Tailscale SSH policy — no credentials needed.",
                style = typography.bodySmall,
                color = colors.textSecondary,
            )
        } else {
            val authOptions = listOf(
                AuthMethod.Password,
                AuthMethod.Key,
            )
            val segmentSelected = if (form.authMethod == AuthMethod.KeyWithPassphrase) AuthMethod.Key else form.authMethod
            ChuSegmentedControl(
                options = authOptions,
                labels = mapOf(
                    AuthMethod.Password to "Password",
                    AuthMethod.Key to "SSH Key",
                ),
                selected = segmentSelected,
                onSelect = vm::updateAuthMethod,
            )

            when (form.authMethod) {
                AuthMethod.Password -> {
                    ChuTextField(
                        value = form.password,
                        onValueChange = vm::updatePassword,
                        label = "Password",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AuthMethod.Key, AuthMethod.KeyWithPassphrase -> {
                    KeyAuthSection(
                        form = form,
                        keys = keys,
                        onGenerate = { vm.generateKey(form.name) },
                        onSelectStoredKey = vm::selectStoredKey,
                        onDeleteStoredKey = vm::deleteStoredKey,
                        onSavePrivateKey = {
                            val name = form.name.trim().ifBlank { "android-ed25519" }
                            exportKeyLauncher.launch("$name.pem")
                        },
                        onCopyPublicKey = {
                            if (form.publicKeyOpenSsh.isBlank()) {
                                Toast.makeText(context, "No public key available", Toast.LENGTH_SHORT).show()
                            } else {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("SSH Public Key", form.publicKeyOpenSsh))
                                Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        ChuSwitch(
                            checked = form.authMethod == AuthMethod.KeyWithPassphrase,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    vm.updateAuthMethod(AuthMethod.KeyWithPassphrase)
                                } else {
                                    vm.updateAuthMethod(AuthMethod.Key)
                                    vm.updateKeyPassphrase("")
                                }
                            },
                        )
                        ChuText("Set passphrase", style = typography.label)
                    }
                    if (form.authMethod == AuthMethod.KeyWithPassphrase) {
                        ChuTextField(
                            value = form.keyPassphrase,
                            onValueChange = vm::updateKeyPassphrase,
                            label = "Passphrase",
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                AuthMethod.None -> Unit
            }
        }

        SectionDivider()

        // --- Actions ---
        val canTest = form.host.isNotBlank() &&
            (form.transport == Transport.TailscaleSSH || form.username.isNotBlank())
        ChuButton(
            onClick = vm::testConnection,
            enabled = canTest,
            variant = ChuButtonVariant.Outlined,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val label = when (testState.status) {
                ConnectionTestStatus.Running -> "Testing…"
                else -> "Test connection"
            }
            ChuText(label, style = typography.label)
        }
        if (testState.message != null) {
            ChuText(
                testState.message ?: "",
                style = typography.bodySmall,
                color = if (testState.status == ConnectionTestStatus.Error) colors.error else colors.success,
            )
        }

        ChuButton(
            onClick = { vm.save(onBack) },
            enabled = form.canSave(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChuText("Save", style = typography.label, color = colors.onAccent)
        }
    }
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ChuColors.current.border.copy(alpha = 0.5f)),
    )
}

@Composable
private fun SectionHeader(label: String) {
    ChuText(
        label,
        style = ChuTypography.current.title,
        color = ChuColors.current.textPrimary,
    )
}

@Composable
private fun KeyAuthSection(
    form: AddServerForm,
    keys: List<com.jossephus.chuchu.model.SshKey>,
    onGenerate: () -> Unit,
    onSelectStoredKey: (Long?) -> Unit,
    onDeleteStoredKey: (Long) -> Unit,
    onSavePrivateKey: () -> Unit,
    onCopyPublicKey: () -> Unit,
) {
    val typography = ChuTypography.current
    val colors = ChuColors.current
    val selectedKey = keys.firstOrNull { it.id == form.keyId }
    var showKeyPicker by remember { mutableStateOf(false) }

    if (selectedKey != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surface)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChuText(
                "Using: ${selectedKey.name}",
                style = typography.label,
                color = colors.textPrimary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChuButton(
                    onClick = onCopyPublicKey,
                    variant = ChuButtonVariant.Outlined,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    ChuText("Copy public key", style = typography.labelSmall)
                }
                ChuButton(
                    onClick = onSavePrivateKey,
                    variant = ChuButtonVariant.Outlined,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    ChuText("Export private key", style = typography.labelSmall)
                }
            }
        }
    } else {
        ChuText(
            "Generate an Ed25519 key, then copy the public key to ~/.ssh/authorized_keys on the remote host.",
            style = typography.bodySmall,
            color = colors.textMuted,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChuButton(
            onClick = onGenerate,
            variant = ChuButtonVariant.Outlined,
            modifier = Modifier.weight(1f),
        ) {
            ChuText(if (selectedKey != null) "New key" else "Generate key", style = typography.label)
        }
        if (keys.isNotEmpty()) {
            ChuButton(
                onClick = { showKeyPicker = true },
                variant = ChuButtonVariant.Outlined,
                modifier = Modifier.weight(1f),
            ) {
                ChuText(
                    "Stored (${keys.size})",
                    style = typography.label,
                )
            }
        }
    }

    if (showKeyPicker) {
        KeyPickerDialog(
            keys = keys,
            selectedKeyId = form.keyId,
            onSelect = { id ->
                onSelectStoredKey(id)
                showKeyPicker = false
            },
            onDelete = onDeleteStoredKey,
            onDismiss = { showKeyPicker = false },
        )
    }
}

@Composable
private fun KeyPickerDialog(
    keys: List<com.jossephus.chuchu.model.SshKey>,
    selectedKeyId: Long?,
    onSelect: (Long?) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ChuDialog(
        title = "Stored keys",
        confirmLabel = "Done",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            keys.forEach { key ->
                val isSelected = selectedKeyId == key.id
                SwipeToDeleteKeyRow(
                    keyName = key.name,
                    isSelected = isSelected,
                    onSelect = { onSelect(key.id) },
                    onDelete = { onDelete(key.id) },
                )
            }
        }
    }
}

@Composable
private fun SwipeToDeleteKeyRow(
    keyName: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val rowShape = RoundedCornerShape(4.dp)
    val rowHeight = 40.dp
    val maxSwipePx = with(density) { 120.dp.toPx() }
    val deleteThresholdPx = with(density) { 72.dp.toPx() }
    val offsetX = remember(keyName) { Animatable(0f) }

    Box(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.error.copy(alpha = 0.78f))
                .padding(end = 14.dp),
        ) {
            ChuText(
                "Delete",
                style = typography.labelSmall,
                color = colors.background,
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd),
            )
        }

        Box(
            modifier = Modifier
                .clip(rowShape)
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .background(colors.surface)
                .pointerInput(keyName) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (offsetX.value + dragAmount).coerceIn(-maxSwipePx, 0f)
                            scope.launch { offsetX.snapTo(next) }
                        },
                        onDragEnd = {
                            if (offsetX.value <= -deleteThresholdPx) {
                                onDelete()
                                scope.launch { offsetX.snapTo(0f) }
                            } else {
                                scope.launch { offsetX.animateTo(0f, animationSpec = tween(140)) }
                            }
                        },
                    )
                },
        ) {
            ChuButton(
                onClick = onSelect,
                variant = if (isSelected) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val label = if (isSelected) "✓ $keyName" else keyName
                ChuText(label, style = typography.labelSmall, color = if (isSelected) colors.onAccent else colors.textPrimary)
            }
        }
    }
}
