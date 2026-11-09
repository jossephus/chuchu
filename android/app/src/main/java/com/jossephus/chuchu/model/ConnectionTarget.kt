package com.jossephus.chuchu.model

fun connectionTarget(transport: Transport, username: String, host: String, port: Int): String =
    if (transport == Transport.Plugin) host else "$username@$host:$port"
