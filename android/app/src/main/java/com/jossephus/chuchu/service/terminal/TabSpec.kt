package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Transport

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
) {
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
            val target = "$username@$host:$port"
            return if (displayName.isNotBlank()) "$displayName  ·  $target" else target
        }

    val tabLabel: String
        get() =
            when (transport) {
                Transport.LocalShell -> displayName.takeIf { it.isNotBlank() } ?: "local shell"
                else -> displayName.takeIf { it.isNotBlank() } ?: "$username@$host"
            }
}
