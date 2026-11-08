package com.careclose.core.sync.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FamilyCrypto(
    private val keyBase64: String,
) {
    private val secretKey: SecretKey? by lazy {
        if (keyBase64.isBlank()) {
            null
        } else {
            val raw = Base64.getDecoder().decode(keyBase64.trim())
            require(raw.size == 32) { "AES-256 key must be 32 bytes, got ${raw.size}" }
            SecretKeySpec(raw, "AES")
        }
    }

    private val random = SecureRandom()

    fun encryptBytes(plain: ByteArray): ByteArray {
        val key = secretKey ?: return plain
        val iv = ByteArray(IV_LENGTH_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val encrypted = cipher.doFinal(plain)
        return MAGIC_HEADER + iv + encrypted
    }

    fun decryptBytes(payload: ByteArray): ByteArray {
        val key = secretKey ?: return payload
        if (payload.size < MAGIC_HEADER.size + IV_LENGTH_BYTES + 16) {
            throw IllegalArgumentException("Encrypted payload too short")
        }
        for (i in MAGIC_HEADER.indices) {
            if (payload[i] != MAGIC_HEADER[i]) {
                throw IllegalArgumentException("Invalid encrypted payload header")
            }
        }
        val ivStart = MAGIC_HEADER.size
        val cipherStart = ivStart + IV_LENGTH_BYTES
        val iv = payload.copyOfRange(ivStart, cipherStart)
        val ciphertext = payload.copyOfRange(cipherStart, payload.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun encryptTextLine(plainLine: String): String {
        if (secretKey == null) return plainLine
        val encrypted = encryptBytes(plainLine.toByteArray(Charsets.UTF_8))
        return LINE_PREFIX + Base64.getEncoder().encodeToString(encrypted)
    }

    fun decryptTextLine(line: String): String {
        if (secretKey == null) return line
        if (!line.startsWith(LINE_PREFIX)) {
            throw IllegalArgumentException("Missing encrypted line prefix")
        }
        val raw = Base64.getDecoder().decode(line.removePrefix(LINE_PREFIX))
        return decryptBytes(raw).toString(Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private const val LINE_PREFIX = "CC1:"
        private val MAGIC_HEADER = byteArrayOf('C'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte(), 1)

        fun generateNewKeyBase64(): String {
            val generator = KeyGenerator.getInstance("AES")
            generator.init(256)
            val key = generator.generateKey()
            return Base64.getEncoder().encodeToString(key.encoded)
        }
    }
}

