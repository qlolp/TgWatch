package ru.tgwatch

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCrypto {
    const val MAX_PLAINTEXT_BYTES = BackupCodec.MAX_PLAINTEXT_BYTES
    const val MAX_ENVELOPE_BYTES = MAX_PLAINTEXT_BYTES + 56
    const val ITERATIONS = 600_000
    private const val HEADER_BYTES = 40
    private const val TAG_BYTES = 16
    private val magic = "TGWBKUP1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray = checked {
        require(password.size in 8..1024 && plaintext.size <= MAX_PLAINTEXT_BYTES)
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_BYTES).put(magic).putInt(ITERATIONS).put(salt).put(nonce).array()
        val ciphertext = crypt(Cipher.ENCRYPT_MODE, plaintext, 0, plaintext.size, password, salt, nonce, header)
        header + ciphertext
    }

    fun decrypt(envelope: ByteArray, password: CharArray): ByteArray = checked {
        // Reject lengths and unsupported KDF headers before copies or expensive derivation.
        require(password.size in 8..1024 && envelope.size in HEADER_BYTES + TAG_BYTES..MAX_ENVELOPE_BYTES)
        require(magic.indices.all { envelope[it] == magic[it] })
        require(ByteBuffer.wrap(envelope, 8, 4).int == ITERATIONS)
        val header = envelope.copyOfRange(0, HEADER_BYTES)
        val salt = header.copyOfRange(12, 28)
        val nonce = header.copyOfRange(28, 40)
        // doFinal verifies the complete GCM tag before any plaintext reaches the caller.
        crypt(Cipher.DECRYPT_MODE, envelope, HEADER_BYTES, envelope.size - HEADER_BYTES, password, salt, nonce, header)
    }

    private fun crypt(mode: Int, bytes: ByteArray, offset: Int, length: Int, password: CharArray,
        salt: ByteArray, nonce: ByteArray, header: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(header)
            return cipher.doFinal(bytes, offset, length)
        } finally { key.fill(0) }
    }

    private inline fun <T> checked(block: () -> T): T = try { block() }
        catch (_: Exception) { throw IllegalArgumentException("Invalid backup or password") }
}
