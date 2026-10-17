package ru.tgwatch

import java.util.Base64
import javax.crypto.Cipher
import org.junit.Assert.*
import org.junit.Test

class TargetPortableBackupTest {
    private val pass get() = "fixture-pass".toCharArray()
    private val published = Base64.getDecoder().decode("VEdXQgEJzs6tmn7q7KukUsbpt0VPRQDXl5sQEa8bhYDQIEXR5a1WhT0XbhGeEttHz6/mp+40AiAviIY0TY/X0u7aznAv5dFSi114Mng7RLdR8ANgMMOK3wW0m+Yc05vr2beMIqAV0rfi53w2xzP/wtbPW79Dc5wPOLF3mdDLwvV9gIKQP+xLN/XZGcKDn51dwwme+VHrALDWENEO+NN3XhAgF65b/eFlFwIkw/UKoCGT9axml6HAR0ZLCXO4dzMWhtofLRbNOn1Zg5XCbz0eml/u1yH5I9/2tP0velQxs8c228cpKXQJku8rfAAdEPj7VyPer2Su3mZ1z8wVdRyZnObct3EUXvQCyR7/xuDy")
    private fun snapshot() = TargetBackupSnapshot(10_000, mapOf("interval_sec" to 30),
        TargetId.entries.map(TargetDefinition::default), TargetId.entries.associateWith { id ->
            if (id == TargetId.TELEGRAM) listOf(TargetObservation(Observation(1_000, 2_000, "DOWN"), 0))
            else emptyList()
        }, listOf("fixture log"))

    @Test fun newContainerUsesVersionThreeAndFullAuthenticatedRoundTrip() {
        val original = snapshot()
        val before = pass
        val archive = PortableBackupCodec.encryptTargets(original, before)
        assertArrayEquals(byteArrayOf(84, 71, 87, 66, 3), archive.copyOfRange(0, 5))
        assertEquals(TargetBackupCodec.encode(original).size + 49, archive.size)
        assertEquals(original, PortableBackupCodec.decryptTargets(archive, before))
        assertArrayEquals(pass, before)
        val second = PortableBackupCodec.encryptTargets(original, pass)
        assertFalse(archive.copyOfRange(5, 33).contentEquals(second.copyOfRange(5, 33)))
    }

    @Test fun headerCipherTagAndWrongPasswordNeverReturnAParsedSnapshot() {
        val archive = PortableBackupCodec.encryptTargets(snapshot(), pass)
        for (index in listOf(0, 4, 5, 20, 21, 32, 33, archive.lastIndex)) {
            assertThrows(IllegalArgumentException::class.java) {
                PortableBackupCodec.decryptTargets(archive.copyOf().apply {
                    this[index] = (this[index].toInt() xor 1).toByte()
                }, pass)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCodec.decryptTargets(archive, "wrong-secret".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) { PortableBackupCodec.decryptTargets(archive + 0.toByte(), pass) }
    }

    @Test fun v1V2AndLocalImportMapToTelegramWithoutEnablingExtraTargets() {
        val imported = PortableBackupCodec.decryptTargets(published, pass)
        assertEquals("PARTIAL", imported.observations.getValue(TargetId.TELEGRAM).single().row.kind)
        assertTrue(imported.targets.filter { it.id != TargetId.TELEGRAM }.none { it.enabled })
        val old = BackupSnapshot(10_000, mapOf("extra_probe_matrix" to true, "extra_probe_custom" to true,
            "extra_probe_custom_url" to "https://private.example/path"),
            listOf(Observation(1_000, 2_000, "TG_DOWN")), emptyList())
        val typed = PortableBackupCodec.encrypt(old, pass)
        val localPass = "eightpwd".toCharArray()
        val local = BackupCrypto.encrypt(BackupCodec.encode(old), localPass)
        for ((bytes, password) in listOf(typed to pass, local to localPass)) {
            val restored = PortableBackupCodec.decryptTargets(bytes, password)
            assertEquals("DOWN", restored.observations.getValue(TargetId.TELEGRAM).single().row.kind)
            assertFalse(restored.globalSettings.containsKey("extra_probe_custom_url"))
            assertTrue(restored.targets.filter { it.id != TargetId.TELEGRAM }.none { it.enabled })
            val privateImport = PortableBackupCodec.decryptTargetImport(bytes, password)
            assertEquals("https://private.example/path", privateImport.legacyEndpoint)
            assertEquals(restored, privateImport.snapshot)
        }
    }

    @Test fun versionAndSizeLimitsRemainSpecificToTheContainer() {
        for (size in listOf(0, 11, 257)) assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCodec.encryptTargets(snapshot(), CharArray(size) { 'x' })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCodec.decryptTargets(ByteArray(8 * 1024 * 1024 + 50).apply {
                byteArrayOf(84, 71, 87, 66, 2).copyInto(this)
            }, pass)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCodec.decryptTargets(ByteArray(16 * 1024 * 1024 + 50).apply {
                byteArrayOf(84, 71, 87, 66, 3).copyInto(this)
            }, pass)
        }
        // An authenticated JSON2 payload in a v3 envelope is still a schema mismatch.
        val salt = ByteArray(16)
        val nonce = ByteArray(12)
        val header = byteArrayOf(84, 71, 87, 66, 3) + salt + nonce
        val oldJson = BackupCodec.encode(BackupSnapshot(0, emptyMap(), emptyList(), emptyList()))
        val ciphertext = BackupCrypto.crypt(Cipher.ENCRYPT_MODE, oldJson, 0, oldJson.size, pass, salt, nonce, header)
        assertThrows(IllegalArgumentException::class.java) { PortableBackupCodec.decryptTargets(header + ciphertext, pass) }
    }
}
