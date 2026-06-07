package com.jossephus.chuchu.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmuxInstallMatrixTest {
    @Test
    fun debianUsesAptGet() {
        val candidate = TmuxInstallMatrix.fromProbeOutput(
            "CHUCHU_ID=debian\nCHUCHU_UNAME=Linux\nCHUCHU_HAS_APT=1\n",
        )

        assertEquals("sudo apt-get update && sudo apt-get install -y tmux", candidate.command)
        assertEquals("apt", candidate.packageManager)
    }

    @Test
    fun rhelPrefersDnfWhenAvailable() {
        val candidate = TmuxInstallMatrix.fromProbeOutput(
            "CHUCHU_ID=rocky\nCHUCHU_ID_LIKE=rhel fedora\nCHUCHU_HAS_DNF=1\nCHUCHU_HAS_YUM=1\n",
        )

        assertEquals("sudo dnf install -y tmux", candidate.command)
        assertEquals("dnf", candidate.packageManager)
    }

    @Test
    fun alpineUsesApk() {
        val candidate = TmuxInstallMatrix.fromProbeOutput(
            "CHUCHU_ID=alpine\nCHUCHU_HAS_APK=1\n",
        )

        assertEquals("sudo apk add tmux", candidate.command)
        assertEquals("apk", candidate.packageManager)
    }

    @Test
    fun unknownOsHasManualFallback() {
        val candidate = TmuxInstallMatrix.fromProbeOutput("CHUCHU_UNAME=Plan9\n")

        assertNull(candidate.command)
        assertEquals("Install tmux with this host's package manager, then retry.", candidate.guidance)
    }
}
