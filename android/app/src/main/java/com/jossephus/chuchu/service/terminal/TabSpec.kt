package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.tmux.TmuxCommandBuilder

data class TabSpec(
    val hostId: Long? = null,
    val displayName: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val authMethod: AuthMethod = AuthMethod.Password,
    val publicKeyOpenSsh: String = "",
    val privateKeyPem: String = "",
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
    val postConnectCommand: String? = null,
    val startInTmux: Boolean = false,
    val tmuxSessionName: String? = null,
    val tmuxCreateIfMissing: Boolean = true,
) {
    val sessionKey: String
        get() = hostId?.let { "host:$it" } ?: "${transport.name}:$username@$host:$port"

    val notificationLabel: String
        get() {
            val target = "$username@$host:$port"
            return if (displayName.isNotBlank()) "$displayName  ·  $target" else target
        }

    val tabLabel: String
        get() = displayName.takeIf { it.isNotBlank() } ?: "$username@$host"

    val tmuxStartupCommand: String?
        get() {
            if (!startInTmux || transport == Transport.Mosh) return null
            val name = tmuxSessionName?.takeIf { it.isNotBlank() } ?: return null
            return if (tmuxCreateIfMissing) {
                TmuxCommandBuilder.interactiveAttachOrSwitchCommand(name, trustedRemoteName = true)
            } else {
                TmuxCommandBuilder.interactiveAttachExistingCommand(name, trustedRemoteName = true)
            }
        }

    companion object {
        fun fromHostProfile(
            host: HostProfile,
            publicKeyOpenSsh: String = "",
            privateKeyPem: String = "",
            keyPassphrase: String = "",
        ): TabSpec = TabSpec(
            hostId = host.id,
            displayName = host.name,
            host = host.host,
            port = host.port,
            username = host.username,
            password = host.password,
            authMethod = host.authMethod,
            publicKeyOpenSsh = publicKeyOpenSsh,
            privateKeyPem = privateKeyPem,
            keyPassphrase = keyPassphrase,
            transport = host.transport,
            postConnectCommand = host.postConnectCommand,
            startInTmux = host.startInTmux && host.transport != Transport.Mosh,
        )
    }
}
