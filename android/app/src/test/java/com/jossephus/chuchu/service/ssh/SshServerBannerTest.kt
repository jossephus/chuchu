package com.jossephus.chuchu.service.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshServerBannerTest {
    @Test
    fun detectsWindowsOpenSsh() {
        assertTrue(SshServerBanner.isWindowsOpenSsh("SSH-2.0-OpenSSH_for_Windows_9.5"))
        assertTrue(SshServerBanner.isWindowsOpenSsh("SSH-2.0-OpenSSH_for_Windows_8.1p1"))
    }

    @Test
    fun ignoresOtherServers() {
        assertFalse(SshServerBanner.isWindowsOpenSsh("SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13"))
        assertFalse(SshServerBanner.isWindowsOpenSsh("SSH-2.0-dropbear_2022.83"))
        assertFalse(SshServerBanner.isWindowsOpenSsh(""))
        assertFalse(SshServerBanner.isWindowsOpenSsh(null))
    }
}
