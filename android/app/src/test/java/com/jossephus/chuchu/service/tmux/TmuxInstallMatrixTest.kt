package com.jossephus.chuchu.service.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmuxInstallMatrixTest {
    @Test
    fun debianUsesAptGet() {
        val candidate = TmuxInstallMatrix.fromProbeOutput(
            "ID=debian\nPRETTY_NAME=\"Debian GNU/Linux\"\nCHUCHU_UNAME=Linux\nCHUCHU_COMMANDS=apt-get sudo\n",
        )

        assertEquals("sudo apt-get update && sudo apt-get install -y tmux", candidate.command)
        assertEquals("apt-get", candidate.packageManager)
    }

    @Test
    fun rhelPrefersDnfWhenAvailable() {
        val candidate = TmuxInstallMatrix.fromProbeOutput(
            "ID=rocky\nID_LIKE=\"rhel fedora\"\nCHUCHU_COMMANDS=dnf yum sudo\n",
        )

        assertEquals("sudo dnf install -y tmux", candidate.command)
        assertEquals("dnf", candidate.packageManager)
    }

    @Test
    fun alpineCanUseDoas() {
        val candidate = TmuxInstallMatrix.fromProbeOutput(
            "ID=alpine\nCHUCHU_COMMANDS=apk doas\n",
        )

        assertEquals("doas apk add tmux", candidate.command)
        assertEquals("apk", candidate.packageManager)
    }

    @Test
    fun unknownOsHasManualFallback() {
        val candidate = TmuxInstallMatrix.fromProbeOutput("CHUCHU_UNAME=Plan9\n")

        assertNull(candidate.command)
        assertEquals("Install tmux with this host's package manager, then reconnect.", candidate.guidance)
    }
}
