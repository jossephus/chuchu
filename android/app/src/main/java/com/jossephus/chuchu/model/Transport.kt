package com.jossephus.chuchu.model

enum class Transport(
    val needsUsername: Boolean,
    val supportsSftp: Boolean,
    val hostsMultiplexer: Boolean,
    val supportsJumpHost: Boolean,
) {
    SSH(needsUsername = true, supportsSftp = true, hostsMultiplexer = true, supportsJumpHost = true),
    TailscaleSSH(needsUsername = true, supportsSftp = true, hostsMultiplexer = true, supportsJumpHost = true),
    Mosh(needsUsername = true, supportsSftp = true, hostsMultiplexer = false, supportsJumpHost = false),
    LocalShell(needsUsername = false, supportsSftp = false, hostsMultiplexer = false, supportsJumpHost = false),
    Plugin(needsUsername = false, supportsSftp = false, hostsMultiplexer = false, supportsJumpHost = false),
    ;

    fun endpoint(username: String, host: String, port: Int): String =
        if (needsUsername) "$username@$host:$port" else host
}
