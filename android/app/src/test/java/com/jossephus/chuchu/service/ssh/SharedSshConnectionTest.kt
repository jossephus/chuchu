package com.jossephus.chuchu.service.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedSshConnectionTest {
    @Test
    fun exitEnvelopePreservesCommandAndExitStatus() {
        assertEquals(
            "herdr focus-tab abc; printf '\nCHUCHU_EXIT:%s\n' \"\$?\"",
            SharedSshConnection.withExitEnvelope("herdr focus-tab abc"),
        )
    }

    @Test
    fun parsesExitEnvelopeAndKeepsOutput() {
        val result = SharedSshConnection.parseCommandEnvelope("done\nCHUCHU_EXIT:7\n")

        assertEquals(7, result.exitCode)
        assertEquals("done", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun missingExitEnvelopeIsAProtocolFailure() {
        val result = SharedSshConnection.parseCommandEnvelope("partial output")

        assertEquals(125, result.exitCode)
        assertEquals("partial output", result.stdout)
        assertEquals("Missing command exit marker", result.stderr)
    }
}
