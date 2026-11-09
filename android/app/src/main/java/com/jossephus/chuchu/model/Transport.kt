package com.jossephus.chuchu.model

enum class Transport(
    val needsUsername: Boolean,
    val supportsSftp: Boolean,
    val hostsMultiplexer: Boolean,
) {
    SSH(needsUsername = true, supportsSftp = true, hostsMultiplexer = true),
    TailscaleSSH(needsUsername = true, supportsSftp = true, hostsMultiplexer = true),
    Mosh(needsUsername = true, supportsSftp = true, hostsMultiplexer = false),
    LocalShell(needsUsername = false, supportsSftp = false, hostsMultiplexer = false),
    Plugin(needsUsername = false, supportsSftp = false, hostsMultiplexer = false),
    ;

    fun endpoint(username: String, host: String, port: Int): String =
        if (needsUsername) "$username@$host:$port" else host
}
