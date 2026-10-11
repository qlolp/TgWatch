package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCryptoTest {
    private fun password() = charArrayOf('t', 'e', 's', 't', 'o', 'n', 'l', 'y')
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid archive accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun roundTripEncryptsUtf8AndLeavesCallerPasswordAlone() {
        val plain = "Конфигурация 🛰️".toByteArray(Charsets.UTF_8)
        val password = password()
        val envelope = BackupCrypto.encrypt(plain, password)
        assertArrayEquals(plain, BackupCrypto.decrypt(envelope, password))
        assertArrayEquals(password(), password)
        assertEquals(plain.size + 56, envelope.size)
        assertFalse(envelope.toString(Charsets.UTF_8).contains("Конфигурация"))
    }
    @Test fun headerMatchesVersionOneAndIndependentAesGcmImplementation() {
        val plain = byteArrayOf(0, 1, 2, 3)
        val envelope = BackupCrypto.encrypt(plain, password())
        assertEquals(60, envelope.size)
        assertEquals("TGWBKUP1", envelope.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertEquals(600_000, ByteBuffer.wrap(envelope, 8, 4).int)
        val spec = PBEKeySpec(password(), envelope.copyOfRange(12, 28), 600_000, 256)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, envelope.copyOfRange(28, 40)))
                cipher.updateAAD(envelope.copyOfRange(0, 40))
                assertArrayEquals(plain, cipher.doFinal(envelope.copyOfRange(40, envelope.size)))
            } finally { key.fill(0) }
        } finally { spec.clearPassword() }
    }
    @Test fun everyExportUsesDistinctSaltAndNonce() {
        val first = BackupCrypto.encrypt(byteArrayOf(1), password())
        val second = BackupCrypto.encrypt(byteArrayOf(1), password())
        assertEquals(57, first.size)
        assertEquals(57, second.size)
        assertFalse(first.copyOfRange(12, 28).contentEquals(second.copyOfRange(12, 28)))
        assertFalse(first.copyOfRange(28, 40).contentEquals(second.copyOfRange(28, 40)))
    }
    @Test fun wrongPasswordAndTamperingNeverReturnPlaintext() {
        val envelope = BackupCrypto.encrypt("snapshot".toByteArray(), password())
        rejected { BackupCrypto.decrypt(envelope, charArrayOf('w', 'r', 'o', 'n', 'g', 'o', 'n', 'e')) }
        for (offset in listOf(0, 7, 8, 11, 12, 27, 28, 39, 40, envelope.lastIndex)) {
            val corrupt = envelope.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
            rejected { BackupCrypto.decrypt(corrupt, password()) }
        }
        for (size in listOf(0, 7, 39, 55, envelope.size - 1)) rejected { BackupCrypto.decrypt(envelope.copyOf(size), password()) }
        rejected { BackupCrypto.decrypt(envelope + byteArrayOf(0), password()) }
    }
    @Test fun boundsRejectPasswordsPayloadsAndHostileIterationCounts() {
        for (size in listOf(0, 7, 1025)) {
            rejected { BackupCrypto.encrypt(byteArrayOf(1), CharArray(size) { 'x' }) }
            rejected { BackupCrypto.decrypt(ByteArray(56), CharArray(size) { 'x' }) }
        }
        rejected { BackupCrypto.encrypt(ByteArray(BackupCrypto.MAX_PLAINTEXT_BYTES + 1), password()) }
        rejected { BackupCrypto.decrypt(ByteArray(BackupCrypto.MAX_ENVELOPE_BYTES + 1), password()) }
        val header = ByteArray(56)
        "TGWBKUP1".toByteArray().copyInto(header)
        for (iterations in listOf(0, -1, 599_999, 600_001, Int.MAX_VALUE)) {
            ByteBuffer.wrap(header, 8, 4).putInt(iterations)
            rejected { BackupCrypto.decrypt(header, password()) }
        }
    }
    @Test fun exactSizeAndPasswordBoundariesAndEmptyPlaintextRoundTrip() {
        for (size in listOf(8, 1024)) {
            val password = CharArray(size) { 'я' }
            assertArrayEquals(byteArrayOf(), BackupCrypto.decrypt(BackupCrypto.encrypt(byteArrayOf(), password), password))
        }
        val plain = ByteArray(BackupCrypto.MAX_PLAINTEXT_BYTES) { (it % 251).toByte() }
        val envelope = BackupCrypto.encrypt(plain, password())
        assertEquals(BackupCrypto.MAX_ENVELOPE_BYTES, envelope.size)
        assertArrayEquals(plain, BackupCrypto.decrypt(envelope, password()))
    }
    @Test fun authenticatedSnapshotRoundTripAndCorruptFailuresAreGeneric() {
        val snapshot = BackupSnapshot(123, mapOf("power_profile" to "BALANCED", "interval_sec" to 30,
            "quiet_start_minute" to 1379, "vibrate" to true),
            listOf(Observation(0, 1, "OK", 7), Observation(1, 2, "TG_DOWN", clockEpoch = 1)),
            listOf("Связь 🛰️", "строка\nжурнала"))
        val envelope = BackupCrypto.encrypt(BackupCodec.encode(snapshot), password())
        assertEquals(snapshot, BackupCodec.decode(BackupCrypto.decrypt(envelope, password())))
        for (corrupt in listOf(envelope.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }, byteArrayOf())) {
            try { BackupCrypto.decrypt(corrupt, password()); fail("Corruption accepted") }
            catch (error: IllegalArgumentException) {
                assertEquals("Invalid backup or password", error.message)
                assertNull(error.cause)
            }
        }
    }
}
