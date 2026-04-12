package com.jossephus.chuchu.service.ssh

fun interface HostKeyPolicy {
    fun verify(host: String, port: Int, algorithm: String, keyBytes: ByteArray): Boolean
}
