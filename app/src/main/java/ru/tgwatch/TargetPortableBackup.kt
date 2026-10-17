package ru.tgwatch

import java.security.SecureRandom
import javax.crypto.Cipher

/** Credential-bearing legacy data has a separate private import channel and is never re-exported. */
internal data class TargetBackupImport(val snapshot: TargetBackupSnapshot, val legacyEndpoint: String? = null)

internal object TargetPortableBackup {
    private const val VERSION = 3
    private const val PREFIX_BYTES = 5
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val HEADER_BYTES = PREFIX_BYTES + SALT_BYTES + NONCE_BYTES
    private const val TAG_BYTES = 16
    private const val MIN_PASSWORD = 12
    private const val MAX_PASSWORD = 256
    private val magic = "TGWB".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun encrypt(snapshot: TargetBackupSnapshot, password: CharArray): ByteArray = checked {
        require(password.size in MIN_PASSWORD..MAX_PASSWORD)
        val plaintext = TargetBackupCodec.encode(snapshot)
        try {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val header = magic + byteArrayOf(VERSION.toByte()) + salt + nonce
            header + BackupCrypto.crypt(Cipher.ENCRYPT_MODE, plaintext, 0, plaintext.size,
                password, salt, nonce, header)
        } finally { plaintext.fill(0) }
    }

    fun decryptImport(bytes: ByteArray, password: CharArray): TargetBackupImport = checked {
        require(bytes.size in PREFIX_BYTES..PortableBackupCodec.MAX_ENVELOPE_BYTES)
        val v3 = magic.indices.all { bytes[it] == magic[it] } && bytes[magic.size] == VERSION.toByte()
        if (!v3) return@checked legacy(PortableBackupCodec.decrypt(bytes, password))
        require(password.size in MIN_PASSWORD..MAX_PASSWORD)
        val maximum = TargetBackupCodec.MAX_PLAINTEXT_BYTES + HEADER_BYTES + TAG_BYTES
        require(bytes.size in HEADER_BYTES + TAG_BYTES..maximum)
        val header = bytes.copyOfRange(0, HEADER_BYTES)
        val salt = header.copyOfRange(PREFIX_BYTES, PREFIX_BYTES + SALT_BYTES)
        val nonce = header.copyOfRange(PREFIX_BYTES + SALT_BYTES, HEADER_BYTES)
        // The complete tag and authenticated header pass doFinal before the JSON reader is constructed.
        val plaintext = BackupCrypto.crypt(Cipher.DECRYPT_MODE, bytes, HEADER_BYTES, bytes.size - HEADER_BYTES,
            password, salt, nonce, header)
        try { TargetBackupImport(TargetBackupCodec.decode(plaintext)) } finally { plaintext.fill(0) }
    }

    internal fun legacy(snapshot: BackupSnapshot): TargetBackupImport {
        val settings = snapshot.settings - TargetBackupCodec.LEGACY_ENDPOINT_KEY
        val definitions = TargetId.entries.map { id ->
            val defaults = TargetDefinition.default(id)
            if (id != TargetId.TELEGRAM) defaults else defaults.copy(
                intervalSec = settings["interval_sec"] as? Int ?: TargetDefinition.DEFAULT_TELEGRAM_SEC,
                alerts = TargetAlertSettings(settings["notify_outage"] as? Boolean ?: true,
                    settings["notify_recovery"] as? Boolean ?: true, settings["notify_slow"] as? Boolean ?: true))
        }
        val observations = TargetId.entries.associateWith { id ->
            if (id != TargetId.TELEGRAM) emptyList() else snapshot.observations.map { row ->
                TargetObservation(if (row.kind == "TG_DOWN") row.copy(kind = "DOWN") else row, 0L)
            }
        }
        val public = TargetBackupSnapshot(snapshot.createdAt, settings, definitions, observations, snapshot.log)
        TargetBackupCodec.validate(public)
        val privateEndpoint = (snapshot.settings[TargetBackupCodec.LEGACY_ENDPOINT_KEY] as? String)
            ?.takeIf { it.isNotEmpty() }
        return TargetBackupImport(public, privateEndpoint)
    }

    private inline fun <T> checked(block: () -> T): T = try { block() }
        catch (_: Exception) { throw IllegalArgumentException("Invalid backup or password") }
}
