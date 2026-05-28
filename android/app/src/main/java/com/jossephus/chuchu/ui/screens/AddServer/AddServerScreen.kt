package com.jossephus.chuchu.ui.screens.AddServer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    var showAdditionalSettings by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ChuText("$ ", style = typography.headline, color = colors.textMuted)
            ChuText("add server", style = typography.headline)
        }

        SectionHeader("CONNECTION")
        ChuTextField(
            value = form.name,
            onValueChange = vm::updateName,
            label = "Name",
            placeholder = "My server",
            singleLine = true,
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        ChuTextField(
            value = form.host,
            onValueChange = vm::updateHost,
            label = "Host",
            placeholder = "192.168.1.10",
            singleLine = true,
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChuTextField(
                value = form.port,
                onValueChange = vm::updatePort,
                label = "Port",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                autoFocus = false,
                modifier = Modifier.weight(0.3f),
            )
            ChuTextField(
                value = form.username,
                onValueChange = vm::updateUsername,
                label = "Username",
                placeholder = "root",
                singleLine = true,
                autoFocus = false,
                modifier = Modifier.weight(0.7f),
            )
        }

        SectionDivider()

        SectionHeader("TRANSPORT")
        ChuSegmentedControl(
            options = listOf(Transport.SSH, Transport.TailscaleSSH, Transport.Mosh),
            labels = mapOf(
                Transport.SSH to "ssh",
                Transport.TailscaleSSH to "tailscale",
                Transport.Mosh to "mosh",
            ),
            selected = form.transport,
            onSelect = vm::updateTransport,
        )
        if (form.transport == Transport.TailscaleSSH) {
            ChuText(
                "Connects through the Tailscale network. Use the host's Tailscale IP (100.x.x.x) or MagicDNS name.",
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }

        SectionDivider()

        SectionHeader("AUTHENTICATION")
        val authOptions = when (form.transport) {
            Transport.TailscaleSSH -> listOf(AuthMethod.Password, AuthMethod.Key, AuthMethod.None)
            else -> listOf(AuthMethod.Password, AuthMethod.Key)
        }
        if (form.authMethod == AuthMethod.None && form.transport != Transport.TailscaleSSH) {
            androidx.compose.runtime.SideEffect {
                vm.updateAuthMethod(AuthMethod.Password)
            }
        }
        val segmentSelected = if (form.authMethod == AuthMethod.KeyWithPassphrase) AuthMethod.Key else form.authMethod
        ChuSegmentedControl(
            options = authOptions,
            labels = mapOf(
                AuthMethod.Password to "password",
                AuthMethod.Key to "ssh key",
                AuthMethod.None to "none",
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
                    autoFocus = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AuthMethod.Key, AuthMethod.KeyWithPassphrase -> {
                KeyAuthSection(
                    form = form,
                    keys = keys,
                    onGenerate = { vm.generateKey(form.name) },
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
                    ChuText("set passphrase", style = typography.label)
                }
                if (form.authMethod == AuthMethod.KeyWithPassphrase) {
                    ChuTextField(
                        value = form.keyPassphrase,
                        onValueChange = vm::updateKeyPassphrase,
                        label = "Passphrase",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        autoFocus = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            AuthMethod.None -> {
                ChuText(
                    "no SSH auth uses Tailscale SSH policy (`tailscale up --ssh`) on the server. connect through Tailscale SSH instead of regular sshd credentials.",
                    style = typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }

        SectionDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            ChuButton(
                onClick = { showAdditionalSettings = !showAdditionalSettings },
                variant = ChuButtonVariant.Outlined,
                bracketed = true,
            ) {
                ChuText("additional settings", style = typography.label)
            }
        }
        if (showAdditionalSettings) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                ChuText("require biometric auth", style = typography.label)
                ChuSwitch(
                    checked = form.requireAuthOnConnect,
                    onCheckedChange = vm::updateRequireAuthOnConnect,
                )
            }
            SectionDivider()
            PostConnectActionSection(
                command = form.postConnectCommand,
                onCommandChange = vm::updatePostConnectCommand,
            )
            SectionDivider()
        }

        val canTest = form.host.isNotBlank() && form.username.isNotBlank()
        ChuButton(
            onClick = vm::testConnection,
            enabled = canTest,
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val label = when (testState.status) {
                ConnectionTestStatus.Running -> "testing…"
                else -> "test connection"
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
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            borderColor = colors.accent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChuText("save", style = typography.label, color = colors.accent)
        }
    }
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ChuColors.current.border),
    )
}

@Composable
private fun SectionHeader(label: String) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        ChuText("── ", style = typography.labelSmall, color = colors.textMuted)
        ChuText(label, style = typography.labelSmall, color = colors.textMuted)
        ChuText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(
            modifier = Modifier
                .height(1.dp)
                .background(colors.textMuted)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun KeyAuthSection(
    form: AddServerForm,
    keys: List<com.jossephus.chuchu.model.SshKey>,
    onGenerate: () -> Unit,
    onCopyPublicKey: () -> Unit,
) {
    val typography = ChuTypography.current
    val colors = ChuColors.current
    val selectedKey = keys.firstOrNull { it.id == form.keyId }

    if (selectedKey != null) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ChuText("● ", style = typography.body, color = colors.accent)
            ChuText(selectedKey.name, style = typography.body, color = colors.textPrimary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChuButton(
                onClick = onCopyPublicKey,
                variant = ChuButtonVariant.Outlined,
                bracketed = true,
                borderColor = colors.accent,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) {
                ChuText("copy public key", style = typography.labelSmall, color = colors.accent)
            }
            ChuButton(
                onClick = onGenerate,
                variant = ChuButtonVariant.Outlined,
                bracketed = true,
                modifier = Modifier.weight(1f),
            ) {
                ChuText("new key", style = typography.label)
            }
        }
    } else {
        ChuText(
            "generate an Ed25519 key, then copy the public key to ~/.ssh/authorized_keys on the remote host.",
            style = typography.bodySmall,
            color = colors.textMuted,
        )
        ChuButton(
            onClick = onGenerate,
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChuText("generate key", style = typography.label)
        }
    }
}

@Composable
private fun PostConnectActionSection(
    command: String,
    onCommandChange: (String) -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuText("auto-run on connect", style = typography.label)
    ChuTextField(
        value = command,
        onValueChange = onCommandChange,
        label = "",
        placeholder = "e.g. tmux attach -t main",
        showLabel = false,
        singleLine = true,
        autoFocus = false,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        ChuButton(
            onClick = { onCommandChange("") },
            enabled = command.isNotBlank(),
            variant = ChuButtonVariant.Ghost,
            bracketed = true,
            borderColor = colors.textMuted,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            ChuText("clear", style = typography.labelSmall, color = colors.textMuted)
        }
    }
}
