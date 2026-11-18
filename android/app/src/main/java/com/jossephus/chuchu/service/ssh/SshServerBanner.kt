package com.jossephus.chuchu.service.ssh

internal object SshServerBanner {
    fun isWindowsOpenSsh(banner: String?): Boolean =
        banner?.contains("OpenSSH_for_Windows", ignoreCase = true) == true
}
