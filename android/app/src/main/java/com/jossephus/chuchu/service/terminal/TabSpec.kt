package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.plugin.Registry
import com.jossephus.chuchu.plugin.api.MultiplexerProvider
import com.jossephus.chuchu.service.ssh.SshJumpHost

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
    val multiplexer: MultiplexerType? = null,
    val multiplexerSessionName: String? = null,
    val multiplexerCreateIfMissing: Boolean = true,
    val jumpHost: SshJumpHost? = null,
    val transportId: String? = null,
    val transportTarget: TransportTarget? = null,
) {
    val attachedTargetHint: String?
        get() = transportTarget?.takeUnless { it.creates }?.value

    val sessionKey: String
        get() =
            when (transport) {
                Transport.LocalShell -> "local-shell"
                else -> hostId?.let { "host:$it" } ?: "${transport.name}:$username@$host:$port"
            }

    val notificationLabel: String
        get() {
            if (transport == Transport.LocalShell) {
                return displayName.takeIf { it.isNotBlank() } ?: "local shell"
            }
            val target = transport.endpoint(username, host, port)
            return if (displayName.isNotBlank()) "$displayName  ·  $target" else target
        }

    val tabLabel: String
        get() =
            when (transport) {
                Transport.LocalShell -> displayName.takeIf { it.isNotBlank() } ?: "local shell"
                else -> displayName.takeIf { it.isNotBlank() } ?: if (transport.needsUsername) "$username@$host" else host
            }

    val usesRuntimeMultiplexer: Boolean
        get() = multiplexer != null && transport.hostsMultiplexer

    fun withTarget(target: String, creates: Boolean): TabSpec =
        if (transportId != null) {
            copy(transportTarget = TransportTarget(target, creates))
        } else {
            copy(multiplexerSessionName = target, multiplexerCreateIfMissing = creates)
        }

    companion object {
        fun fromHostProfile(
            host: HostProfile,
            multiplexers: Registry<MultiplexerProvider>,
            publicKeyOpenSsh: String = "",
            privateKeyPem: String = "",
            keyPassphrase: String = "",
            jumpHost: SshJumpHost? = null,
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
            transportId = host.transportId,
            multiplexer = host.multiplexer?.takeIf { host.transport.hostsMultiplexer && multiplexers[it.id] != null },
            jumpHost = jumpHost,
        )
    }
}

data class TransportTarget(
    val value: String,
    val creates: Boolean = false,
)
