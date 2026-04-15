package com.jossephus.chuchu.service.ssh

import com.jossephus.chuchu.model.SshKey
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class RsaKeyGenerator {
    fun generate(name: String, bits: Int = 3072): SshKey {
        val normalizedName = name.trim().ifBlank { "android-rsa" }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(bits)
        val pair = generator.generateKeyPair()
        val privatePem = encodePrivateKeyPem(pair.private.encoded)
        val publicOpenSsh = encodeRsaPublicKeyOpenSsh(pair.public as RSAPublicKey, "$normalizedName@chuchu")
        return SshKey(
            name = normalizedName,
            privateKeyPem = privatePem,
            publicKeyOpenSsh = publicOpenSsh,
            createdAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun encodePrivateKeyPem(privateDer: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privateDer)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }

    private fun encodeRsaPublicKeyOpenSsh(publicKey: RSAPublicKey, comment: String): String {
        val blob = ByteArrayOutputStream().use { out ->
            writeSshString(out, "ssh-rsa".toByteArray(StandardCharsets.US_ASCII))
            writeMpInt(out, publicKey.publicExponent.toByteArray())
            writeMpInt(out, publicKey.modulus.toByteArray())
            out.toByteArray()
        }
        val payload = Base64.getEncoder().encodeToString(blob)
        return "ssh-rsa $payload $comment"
    }

    private fun writeSshString(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeInt32(out, bytes.size)
        out.write(bytes)
    }

    private fun writeMpInt(out: ByteArrayOutputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size - 1 && bytes[offset] == 0.toByte()) {
            offset += 1
        }
        var body = bytes.copyOfRange(offset, bytes.size)
        if (body.isEmpty()) {
            body = byteArrayOf(0)
        }
        if (body[0].toInt() and 0x80 != 0) {
            body = byteArrayOf(0) + body
        }
        writeSshString(out, body)
    }

    private fun writeInt32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }
}
