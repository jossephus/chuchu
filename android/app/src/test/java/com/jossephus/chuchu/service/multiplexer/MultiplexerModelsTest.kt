package com.jossephus.chuchu.service.multiplexer

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Multiplexer
import com.jossephus.chuchu.model.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiplexerModelsTest {
    @Test
    fun connectionSpecToStringRedactsSecrets() {
        val text = MultiplexerConnectionSpec(
            multiplexer = Multiplexer.Tmux,
            host = "example.com",
            port = 22,
            username = "salem",
            password = "super-secret-password",
            authMethod = AuthMethod.KeyWithPassphrase,
            publicKeyOpenSsh = "ssh-ed25519 public-key",
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----",
            keyPassphrase = "key-passphrase",
            transport = Transport.SSH,
        ).toString()

        assertTrue(text.contains("host=example.com"))
        assertTrue(text.contains("username=salem"))
        assertTrue(text.contains("password=<redacted>"))
        assertTrue(text.contains("publicKeyOpenSsh=<redacted>"))
        assertTrue(text.contains("privateKeyPem=<redacted>"))
        assertTrue(text.contains("keyPassphrase=<redacted>"))
        assertFalse(text.contains("super-secret-password"))
        assertFalse(text.contains("public-key"))
        assertFalse(text.contains("BEGIN PRIVATE KEY"))
        assertFalse(text.contains("key-passphrase"))
    }
}
