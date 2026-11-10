package com.jossephus.chuchu.ui.screens.AddServer

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.SshKey
import com.jossephus.chuchu.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddServerCopyTest {
    private val key =
        SshKey(
            id = 7L,
            name = "shared",
            privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----",
            publicKeyOpenSsh = "ssh-ed25519 AAAA",
            createdAtEpochMs = 0L,
        )

    private val source =
        HostProfile(
            id = 3L,
            name = "docker-1",
            host = "10.0.0.5",
            port = 2222,
            username = "deploy",
            password = "hunter2",
            keyId = key.id,
            keyPassphrase = "pass",
            transport = Transport.SSH,
            authMethod = AuthMethod.KeyWithPassphrase,
            requireAuthOnConnect = true,
            postConnectCommand = "cd /srv",
            multiplexer = MultiplexerType.Tmux,
        )

    @Test
    fun copyIsANewHostThatKeepsEverySetting() {
        val copy = AddServerForm.copyOf(source, key, "docker-1 copy")

        assertNull(copy.id)
        assertEquals(
            AddServerForm.fromProfile(source, key).copy(id = null, name = "docker-1 copy"),
            copy,
        )
        assertEquals(key.id, copy.keyId)
        assertEquals("hunter2", copy.password)
        assertEquals("2222", copy.port)
    }

    @Test
    fun copyNameAppendsTheLabelWhenFree() {
        assertEquals("docker-1 copy", copyHostName("docker-1", "copy", setOf("docker-1")))
    }

    @Test
    fun copyNameIsNumberedWhenTheLabelIsTaken() {
        val taken = setOf("docker-1", "docker-1 copy", "docker-1 copy 2")
        assertEquals("docker-1 copy 3", copyHostName("docker-1", "copy", taken))
    }
}
