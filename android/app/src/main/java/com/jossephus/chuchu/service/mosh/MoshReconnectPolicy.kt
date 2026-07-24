package com.jossephus.chuchu.service.mosh

/**
 * Keeps transient network loss inside the existing Mosh session.
 *
 * The server expires before the client declares a prolonged outage fatal, so
 * timeout-driven recovery does not replace a remote session that is still alive.
 */
internal object MoshReconnectPolicy {
    const val SERVER_NETWORK_TIMEOUT_SECONDS = 30L * 24 * 60 * 60
    const val CLIENT_NETWORK_TIMEOUT_MS = (SERVER_NETWORK_TIMEOUT_SECONDS + 24 * 60 * 60) * 1_000

    // The bundled client also fails permanently when an unacknowledged state
    // reaches this count. Keep that guard beyond the network timeout window,
    // even at the client's normalized minimum retransmit interval.
    const val CLIENT_MIN_RETRANSMIT_INTERVAL_MS = 50L
    const val CLIENT_MAX_RETRANSMIT_COUNT =
        CLIENT_NETWORK_TIMEOUT_MS / CLIENT_MIN_RETRANSMIT_INTERVAL_MS + 1

    fun bootstrapCommand(): String =
        "env LANG=C.UTF-8 LC_ALL=C.UTF-8 " +
            "MOSH_SERVER_NETWORK_TMOUT=$SERVER_NETWORK_TIMEOUT_SECONDS " +
            "PATH=\"/opt/homebrew/bin:/usr/local/bin:/home/linuxbrew/.linuxbrew/bin:/opt/local/bin:\$PATH\" " +
            "mosh-server new -s -c 256"
}
