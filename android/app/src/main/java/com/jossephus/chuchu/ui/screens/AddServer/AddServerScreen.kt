package com.jossephus.chuchu.ui.screens.AddServer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jossephus.chuchu.R
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.canServeAsJumpHostFor
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
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
    isJumpServer: Boolean = false,
    onSaved: (Long) -> Unit = { onBack() },
    onAddJumpServer: () -> Unit = {},
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val testState by vm.testState.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val jumpHostCandidates by vm.jumpHostCandidates.collectAsStateWithLifecycle()
    val colors = ChuColors.current
    val typography = ChuTypography.current

    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var showAdditionalSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.errorMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(scrollState)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ChuText("$ ", style = typography.headline, color = colors.textMuted)
            ChuText(
                when {
                    form.id != null -> stringResource(R.string.add_server_title_edit)
                    isJumpServer -> stringResource(R.string.add_server_title_jump)
                    else -> stringResource(R.string.add_server_title_new)
                },
                style = typography.headline,
            )
        }

        SectionHeader(stringResource(R.string.section_connection))
        ChuTextField(
            value = form.name,
            onValueChange = vm::updateName,
            label = stringResource(R.string.field_name),
            placeholder = stringResource(R.string.add_server_name_placeholder),
            singleLine = true,
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        ChuTextField(
            value = form.host,
            onValueChange = vm::updateHost,
            label = stringResource(R.string.field_host),
            placeholder = "192.168.1.10",
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChuTextField(
                value = form.port,
                onValueChange = vm::updatePort,
                label = stringResource(R.string.field_port),
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                autoFocus = false,
                modifier = Modifier.weight(0.3f),
            )
            ChuTextField(
                value = form.username,
                onValueChange = vm::updateUsername,
                label = stringResource(R.string.field_username),
                placeholder = "root",
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                autoFocus = false,
                modifier = Modifier.weight(0.7f),
            )
        }

        SectionDivider()

        SectionHeader(stringResource(R.string.section_transport))
        ChuSegmentedControl(
            options = listOf(Transport.SSH, Transport.TailscaleSSH, Transport.Mosh),
            labels =
                mapOf(
                    Transport.SSH to "ssh",
                    Transport.TailscaleSSH to "tailscale",
                    Transport.Mosh to "mosh",
                ),
            selected = form.transport,
            onSelect = vm::updateTransport,
        )
        if (form.transport == Transport.TailscaleSSH) {
            ChuText(
                stringResource(R.string.add_server_tailscale_hint),
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }

        SectionDivider()

        SectionHeader(stringResource(R.string.section_authentication))
        val authOptions =
            when (form.transport) {
                Transport.TailscaleSSH ->
                    listOf(AuthMethod.Password, AuthMethod.Key, AuthMethod.None)
                else -> listOf(AuthMethod.Password, AuthMethod.Key)
            }
        if (form.authMethod == AuthMethod.None && form.transport != Transport.TailscaleSSH) {
            androidx.compose.runtime.SideEffect { vm.updateAuthMethod(AuthMethod.Password) }
        }
        val segmentSelected =
            if (form.authMethod == AuthMethod.KeyWithPassphrase) AuthMethod.Key else form.authMethod
        ChuSegmentedControl(
            options = authOptions,
            labels =
                mapOf(
                    AuthMethod.Password to stringResource(R.string.auth_password),
                    AuthMethod.Key to stringResource(R.string.auth_ssh_key),
                    AuthMethod.None to stringResource(R.string.auth_none),
                ),
            selected = segmentSelected,
            onSelect = vm::updateAuthMethod,
        )

        when (form.authMethod) {
            AuthMethod.Password -> {
                ChuTextField(
                    value = form.password,
                    onValueChange = vm::updatePassword,
                    label = stringResource(R.string.field_password),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    autoFocus = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AuthMethod.Key,
            AuthMethod.KeyWithPassphrase -> {
                KeyAuthSection(
                    form = form,
                    keys = keys,
                    onGenerate = { vm.generateKey(form.name) },
                    onSelectKey = { vm.selectStoredKey(it) },
                    onCopyPublicKey = {
                        if (form.publicKeyOpenSsh.isBlank()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.add_server_no_public_key),
                                Toast.LENGTH_SHORT,
                            )
                                .show()
                        } else {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    context.getString(R.string.add_server_clipboard_label),
                                    form.publicKeyOpenSsh,
                                )
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.add_server_public_key_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
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
                    ChuText(stringResource(R.string.add_server_set_passphrase), style = typography.label)
                }
                if (form.authMethod == AuthMethod.KeyWithPassphrase) {
                    ChuTextField(
                        value = form.keyPassphrase,
                        onValueChange = vm::updateKeyPassphrase,
                        label = stringResource(R.string.field_passphrase),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        autoFocus = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            AuthMethod.None -> {
                ChuText(
                    stringResource(R.string.add_server_ssh_key_only_hint),
                    style = typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }

        SectionDivider()

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ChuButton(
                onClick = { showAdditionalSettings = !showAdditionalSettings },
                variant = ChuButtonVariant.Outlined,
                bracketed = true,
            ) {
                ChuText(stringResource(R.string.add_server_additional_settings), style = typography.label)
            }
        }
        if (showAdditionalSettings) {
            if (form.transport != Transport.Mosh && !isJumpServer) {
                JumpHostSection(
                    selectedId = form.jumpHostId,
                    candidates =
                        jumpHostCandidates.filter {
                            it.canServeAsJumpHostFor(form.id) || it.id == form.jumpHostId
                        },
                    onSelect = vm::updateJumpHost,
                    onAddJumpServer = onAddJumpServer,
                )
                SectionDivider()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                ChuText(stringResource(R.string.add_server_require_biometric), style = typography.label)
                ChuSwitch(
                    checked = form.requireAuthOnConnect,
                    onCheckedChange = vm::updateRequireAuthOnConnect,
                )
            }
            SectionDivider()
            MultiplexerSection(selected = form.multiplexer, onSelect = vm::updateMultiplexer)
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
            val label =
                when (testState.status) {
                    ConnectionTestStatus.Idle -> stringResource(R.string.add_server_test_idle)
                    ConnectionTestStatus.Running -> stringResource(R.string.add_server_test_running)
                    ConnectionTestStatus.Success -> stringResource(R.string.add_server_test_success)
                    ConnectionTestStatus.Error -> stringResource(R.string.add_server_test_error)
                }
            ChuText(label, style = typography.label)
        }
        if (testState.status == ConnectionTestStatus.Error && testState.message != null) {
            ChuText(testState.message ?: "", style = typography.bodySmall, color = colors.error)
        }
        ChuButton(
            onClick = { vm.save(onSaved) },
            enabled = form.canSave(),
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            borderColor = colors.accent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChuText(stringResource(R.string.action_save), style = typography.label, color = colors.accent)
        }
    }
}

@Composable
private fun SectionDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ChuColors.current.border))
}

@Composable
private fun SectionHeader(label: String) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        ChuText("── ", style = typography.labelSmall, color = colors.textMuted)
        ChuText(label, style = typography.labelSmall, color = colors.textMuted)
        ChuText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(modifier = Modifier.height(1.dp).background(colors.textMuted).fillMaxWidth())
    }
}

@Composable
private fun KeyAuthSection(
    form: AddServerForm,
    keys: List<com.jossephus.chuchu.model.SshKey>,
    onGenerate: () -> Unit,
    onSelectKey: (Long?) -> Unit,
    onCopyPublicKey: () -> Unit,
) {
    val typography = ChuTypography.current
    val colors = ChuColors.current
    val selectedKey = keys.firstOrNull { it.id == form.keyId }
    var pickerExpanded by remember { mutableStateOf(false) }

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
                ChuText(
                    stringResource(R.string.add_server_copy_public_key),
                    style = typography.labelSmall,
                    color = colors.accent,
                )
            }
            ChuButton(
                onClick = onGenerate,
                variant = ChuButtonVariant.Outlined,
                bracketed = true,
                modifier = Modifier.weight(1f),
            ) {
                ChuText(stringResource(R.string.add_server_new_key), style = typography.label)
            }
        }
    } else {
        ChuText(
            stringResource(R.string.add_server_generate_key_hint),
            style = typography.bodySmall,
            color = colors.textMuted,
        )
        ChuButton(
            onClick = onGenerate,
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChuText(stringResource(R.string.add_server_generate_key), style = typography.label)
        }
    }

    if (keys.isNotEmpty()) {
        ChuButton(
            onClick = { pickerExpanded = !pickerExpanded },
            variant = ChuButtonVariant.Ghost,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            ChuText(
                text =
                    if (selectedKey != null) {
                        stringResource(R.string.add_server_change_key)
                    } else {
                        stringResource(R.string.add_server_use_existing_key)
                    },
                style = typography.labelSmall,
                color = colors.accent,
            )
        }
        if (pickerExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                keys.forEach { key ->
                    val isSelected = key.id == form.keyId
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable {
                                    onSelectKey(key.id)
                                    pickerExpanded = false
                                }
                                .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        ChuText(
                            text = (if (isSelected) "● " else "  ") + key.name,
                            style = typography.body,
                            color = if (isSelected) colors.accent else colors.textPrimary,
                        )
                        ChuText(
                            key.algorithm,
                            style = typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JumpHostSection(
    selectedId: Long?,
    candidates: List<HostProfile>,
    onSelect: (Long?) -> Unit,
    onAddJumpServer: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current

    SectionHeader(stringResource(R.string.section_connect_via))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        JumpHostOption(
            label = stringResource(R.string.add_server_jump_direct),
            detail = "",
            isSelected = selectedId == null,
            onClick = { onSelect(null) },
        )
        candidates.forEach { host ->
            JumpHostOption(
                label = host.name,
                detail = "${host.username}@${host.host}",
                isSelected = host.id == selectedId,
                onClick = { onSelect(host.id) },
            )
        }
    }
    if (selectedId != null && candidates.none { it.id == selectedId }) {
        ChuText(
            stringResource(R.string.add_server_jump_unavailable),
            style = typography.bodySmall,
            color = colors.error,
        )
    }
    ChuButton(
        onClick = onAddJumpServer,
        variant = ChuButtonVariant.Ghost,
        bracketed = true,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        ChuText(stringResource(R.string.add_server_jump_new), style = typography.labelSmall, color = colors.accent)
    }
}

@Composable
private fun JumpHostOption(label: String, detail: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        ChuText(
            text = (if (isSelected) "● " else "○ ") + label,
            style = typography.body,
            color = if (isSelected) colors.accent else colors.textPrimary,
        )
        ChuText(detail, style = typography.bodySmall, color = colors.textMuted)
    }
}

// The segmented control requires a non-null selection, but "no persistence" is
// modeled as a null MultiplexerType, so wrap the choices in a UI-only enum.
private enum class MultiplexerOption(val type: MultiplexerType?) {
    Off(null),
    Tmux(MultiplexerType.Tmux),
    Zellij(MultiplexerType.Zellij),
    Zmx(MultiplexerType.Zmx),
}

@Composable
private fun MultiplexerSection(selected: MultiplexerType?, onSelect: (MultiplexerType?) -> Unit) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    SectionHeader(stringResource(R.string.section_session_persistence))
    val options = MultiplexerOption.entries.toList()
    val selectedOption = options.firstOrNull { it.type == selected } ?: MultiplexerOption.Off
    ChuSegmentedControl(
        options = options,
        labels =
            mapOf(
                MultiplexerOption.Off to stringResource(R.string.label_off),
                MultiplexerOption.Tmux to "tmux",
                MultiplexerOption.Zellij to "zellij",
                MultiplexerOption.Zmx to "zmx",
            ),
        selected = selectedOption,
        onSelect = { onSelect(it.type) },
        disabledOptions = setOf(MultiplexerOption.Zellij),
    )
}

@Composable
private fun PostConnectActionSection(command: String, onCommandChange: (String) -> Unit) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuText(stringResource(R.string.add_server_autorun), style = typography.label)
    ChuTextField(
        value = command,
        onValueChange = onCommandChange,
        label = "",
        placeholder = stringResource(R.string.add_server_autorun_placeholder),
        showLabel = false,
        singleLine = true,
        autoFocus = false,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        ChuButton(
            onClick = { onCommandChange("") },
            enabled = command.isNotBlank(),
            variant = ChuButtonVariant.Ghost,
            bracketed = true,
            borderColor = colors.textMuted,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            ChuText(stringResource(R.string.action_clear), style = typography.labelSmall, color = colors.textMuted)
        }
    }
}
