package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class BackupCodecTest {
    private val password get() = "Длинный пароль 2026!".toCharArray()
    @Test fun portableAuthenticatedRoundtripAndRejectedInputs() {
        val data = BackupData(1000, listOf(Observation(100,200,"TG_DOWN",-1,0)),
            mapOf("power_profile" to "ECONOMY", "interval_sec" to "60", "vibrate_partial" to "true"), listOf("Пример журнала"))
        val encrypted = BackupCodec.encrypt(data,password)
        assertEquals(data,BackupCodec.decrypt(encrypted,password))
        assertFalse(encrypted.contentEquals(BackupCodec.encrypt(data,password)))
        assertFalse(String(encrypted,Charsets.ISO_8859_1).contains("ECONOMY"))
        rejected { BackupCodec.decrypt(encrypted,"wrong password!".toCharArray()) }
        rejected { BackupCodec.decrypt(encrypted.copyOf(encrypted.size-1),password) }
        rejected { BackupCodec.decrypt(encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },password) }
        rejected { BackupCodec.decrypt(encrypted.copyOf().also { it[4] = 99 },password) }
        rejected { BackupCodec.encrypt(data.copy(settings=mapOf("enabled" to "true")),password) }
        rejected { BackupCodec.encrypt(data.copy(settings=mapOf("interval_sec" to "999")),password) }
        rejected { BackupCodec.encrypt(data.copy(settings=mapOf("vibrate" to "yes")),password) }
        rejected { BackupCodec.encrypt(data.copy(samples=listOf(Observation(200,100,"OK"))),password) }
        rejected { BackupCodec.encrypt(data,"short".toCharArray()) }
        rejected { BackupCodec.readBounded(ByteArrayInputStream(ByteArray(BackupCodec.MAX_BYTES+1))) }
        rejected { BackupCodec.decode(BackupCodec.encode(data) + byteArrayOf(0)) }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid backup must be rejected") } catch (_: IllegalArgumentException) {
        } catch (_: java.security.GeneralSecurityException) { } catch (_: java.io.IOException) { }
    }
}
