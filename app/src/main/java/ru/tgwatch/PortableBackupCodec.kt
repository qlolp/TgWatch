package ru.tgwatch

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.SecureRandom
import javax.crypto.Cipher

/** Portable archive format negotiation, separate from the private JSON journal codec. */
object PortableBackupCodec {
    const val MAX_ENVELOPE_BYTES = BackupCrypto.MAX_ENVELOPE_BYTES
    private const val PUBLISHED_MAX_BYTES = 4 * 1024 * 1024
    private const val HEADER_BYTES = 33
    private const val TAG_BYTES = 16
    private val magic = byteArrayOf(84, 71, 87, 66)
    private val localMagic = "TGWBKUP1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()
    private val publishedDefaults: Map<String, Any> = linkedMapOf(
        "power_profile" to "BALANCED", "interval_sec" to 30, "keep_awake" to true,
        "vibrate" to true, "vibrate_partial" to false, "vibrate_offline" to false, "vibrate_recovery" to true,
        "notify_recovery" to true, "event_sound" to false, "vibration_pattern" to "STANDARD",
        "quiet_hours" to false, "quiet_start_hour" to 23, "quiet_end_hour" to 8,
        "notify_outage" to true, "notify_partial" to false, "sound_outage" to false,
        "sound_partial" to false, "sound_recovery" to false)
    private val publishedIntegerKeys = setOf("interval_sec", "quiet_start_hour", "quiet_end_hour")
    private val publishedStringKeys = setOf("power_profile", "vibration_pattern")

    /** Version 2 intentionally writes typed JSON; version 1 remains import-only. */
    fun encrypt(snapshot: BackupSnapshot, password: CharArray): ByteArray = checked {
        require(password.size in 12..256)
        val plaintext = BackupCodec.encode(snapshot)
        try {
            val salt = ByteArray(16).also { random.nextBytes(it) }
            val nonce = ByteArray(12).also { random.nextBytes(it) }
            val header = magic + byteArrayOf(2) + salt + nonce
            header + BackupCrypto.crypt(Cipher.ENCRYPT_MODE, plaintext, 0, plaintext.size, password, salt, nonce, header)
        } finally { plaintext.fill(0) }
    }

    fun decrypt(bytes: ByteArray, password: CharArray): BackupSnapshot = checked {
        require(bytes.size in 5..MAX_ENVELOPE_BYTES)
        if (bytes.size >= localMagic.size && localMagic.indices.all { bytes[it] == localMagic[it] }) {
            val plaintext = BackupCrypto.decrypt(bytes, password)
            try { return@checked migrateEvents(BackupCodec.decode(plaintext)) } finally { plaintext.fill(0) }
        }
        require(magic.indices.all { bytes[it] == magic[it] })
        val version = bytes[4].toInt() and 0xff
        when (version) {
            1 -> require(password.size in 1..256 && bytes.size in HEADER_BYTES + TAG_BYTES..PUBLISHED_MAX_BYTES)
            2 -> require(password.size in 12..256 && bytes.size in HEADER_BYTES + TAG_BYTES..BackupCodec.MAX_PLAINTEXT_BYTES + HEADER_BYTES + TAG_BYTES)
            else -> invalid()
        }
        val header = bytes.copyOfRange(0, HEADER_BYTES)
        val plaintext = BackupCrypto.crypt(Cipher.DECRYPT_MODE, bytes, HEADER_BYTES, bytes.size - HEADER_BYTES,
            password, header.copyOfRange(5,21), header.copyOfRange(21,33), header)
        // Authentication must finish before either binary or JSON parsing is entered.
        try { if (version == 1) decodePublishedPayload(plaintext) else BackupCodec.decode(plaintext) }
        finally { plaintext.fill(0) }
    }

    /** The published 1.10 private restore.pending journal held this plaintext binary payload. */
    fun decodePublishedPayload(bytes: ByteArray): BackupSnapshot = checked {
        require(bytes.size in 24..PUBLISHED_MAX_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0x54474431)
        val createdAt = input.readLong()
        require(createdAt in 1..253402300799999L)
        val count = input.readInt()
        require(count in 0..publishedDefaults.size)
        val supplied = linkedMapOf<String, Any>()
        repeat(count) {
            val key = input.readUTF()
            require(key in publishedDefaults && key !in supplied)
            val value = input.readUTF()
            supplied[key] = when (key) {
                in publishedIntegerKeys -> value.toIntOrNull() ?: invalid()
                in publishedStringKeys -> value
                else -> when (value) { "true" -> true; "false" -> false; else -> invalid() }
            }
        }
        val rows = input.readInt()
        require(rows in 0..BackupCodec.MAX_OBSERVATIONS && input.available() >= 4 && rows <= (input.available() - 4) / 34)
        val observations = ArrayList<Observation>(rows)
        val seen = HashSet<Pair<Long, Long>>()
        repeat(rows) {
            val row = Observation(input.readLong(), input.readLong(), input.readUTF(), input.readLong(), input.readLong())
            require(row.at >= 0 && row.until > row.at && row.until <= 253402300799999L && row.until - row.at <= 600_000)
            require(row.kind in Timeline.kinds && row.latencyMs in -1..600_000 && row.clockEpoch in 0..1_000_000_000)
            require(seen.add(row.clockEpoch to row.at))
            observations += row
        }
        val epoch = observations.maxOfOrNull { it.clockEpoch } ?: 0
        require(observations.none { it.clockEpoch == epoch && it.until > createdAt })
        val lines = input.readInt()
        require(lines in 0..BackupCodec.MAX_LOG_LINES && lines <= input.available() / 2)
        val log = ArrayList<String>(lines)
        repeat(lines) { log += input.readUTF().also { require(it.length <= 512 && '\n' !in it) } }
        require(input.read() == -1)
        val eventSound = supplied["event_sound"] as? Boolean ?: false
        val settings = publishedDefaults + mapOf("sound_outage" to eventSound, "sound_recovery" to eventSound) + supplied
        val migrated = settings + mapOf("quiet_start_minute" to (settings.getValue("quiet_start_hour") as Int) * 60,
            "quiet_end_minute" to (settings.getValue("quiet_end_hour") as Int) * 60)
        val snapshot = BackupSnapshot(createdAt, migrated, observations, log)
        // Apply the strict shared type/range/Unicode rules too. Clear temporary JSON bytes.
        BackupCodec.encode(snapshot).fill(0)
        snapshot
    }

    private fun migrateEvents(snapshot: BackupSnapshot): BackupSnapshot {
        val legacySound = snapshot.settings["event_sound"] as? Boolean ?: false
        val settings = mapOf("notify_outage" to true, "notify_partial" to false,
            "sound_outage" to legacySound, "sound_partial" to false, "sound_recovery" to legacySound) + snapshot.settings
        return snapshot.copy(settings = settings)
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid backup or password")
    private inline fun <T> checked(block: () -> T): T = try { block() } catch (_: Exception) { invalid() }
}
