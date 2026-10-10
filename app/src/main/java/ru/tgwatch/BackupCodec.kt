package ru.tgwatch

import java.io.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupData(val createdAt: Long, val samples: List<Observation>,
    val settings: Map<String,String>, val log: List<String>)

/** Portable authenticated encryption. Neither a device key nor a password is persisted. */
object BackupCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val ITERATIONS = 600_000
    private val magic = byteArrayOf(84,71,87,66,1)
    private val random = SecureRandom()

    fun encrypt(data: BackupData, password: CharArray): ByteArray {
        require(password.size in 12..256) { "Пароль: от 12 до 256 символов" }
        val plain = encode(data)
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + nonce
        return try { header + cipher(Cipher.ENCRYPT_MODE,password,salt,nonce,header).doFinal(plain) }
        finally { plain.fill(0) }
    }
    fun decrypt(bytes: ByteArray, password: CharArray): BackupData {
        require(bytes.size in 49..MAX_BYTES && bytes.copyOfRange(0,5).contentEquals(magic)) { "Неподдерживаемый файл бэкапа" }
        require(password.size in 1..256) { "Неверный пароль" }
        val plain = cipher(Cipher.DECRYPT_MODE,password,bytes.copyOfRange(5,21),bytes.copyOfRange(21,33),
            bytes.copyOfRange(0,33)).doFinal(bytes,33,bytes.size-33)
        return try { decode(plain) } finally { plain.fill(0) }
    }
    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray, header: ByteArray): Cipher {
        val spec = PBEKeySpec(password,salt,ITERATIONS,256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try { Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce)); updateAAD(header)
        } } finally { key.fill(0) }
    }
    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Файл слишком большой" }
            output.write(buffer,0,count)
        }
        return output.toByteArray()
    }
    internal fun validate(data: BackupData) {
        require(data.createdAt in 1..253402300799999L && data.samples.size <= 65_000)
        BackupSettings.validate(data.settings)
        require(data.log.size <= 150 && data.log.all { it.length <= 512 && '\n' !in it })
        val epoch = data.samples.maxOfOrNull { it.clockEpoch } ?: 0
        val seen = HashSet<Pair<Long,Long>>()
        for (row in data.samples) {
            require(row.at >= 0 && row.until <= 253402300799999L && row.until > row.at && row.until-row.at <= 600_000)
            require(row.kind in Timeline.kinds && row.latencyMs in -1..600_000 && row.clockEpoch in 0..1_000_000_000)
            require(seen.add(row.clockEpoch to row.at))
            require(row.clockEpoch != epoch || row.until <= data.createdAt)
        }
    }
    internal fun encode(data: BackupData): ByteArray {
        validate(data)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x54474431); out.writeLong(data.createdAt)
            out.writeInt(data.settings.size)
            data.settings.toSortedMap().forEach { (key,value) -> out.writeUTF(key); out.writeUTF(value) }
            out.writeInt(data.samples.size)
            data.samples.forEach { out.writeLong(it.at); out.writeLong(it.until); out.writeUTF(it.kind)
                out.writeLong(it.latencyMs); out.writeLong(it.clockEpoch) }
            out.writeInt(data.log.size); data.log.forEach(out::writeUTF)
        }
        return bytes.toByteArray().also { require(it.size + 49 <= MAX_BYTES) }
    }
    internal fun decode(bytes: ByteArray): BackupData {
        require(bytes.size <= MAX_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0x54474431) { "Неподдерживаемая версия данных" }
        val created = input.readLong()
        val settings = linkedMapOf<String,String>()
        val count = input.readInt(); require(count in 0..BackupSettings.defaults.size)
        repeat(count) { val key=input.readUTF(); require(settings.put(key,input.readUTF()) == null) }
        val rows = input.readInt(); require(rows in 0..65_000)
        val samples = List(rows) { Observation(input.readLong(),input.readLong(),input.readUTF(),input.readLong(),input.readLong()) }
        val logs = input.readInt(); require(logs in 0..150)
        val log = List(logs) { input.readUTF() }
        require(input.read() == -1) { "Лишние данные в бэкапе" }
        return BackupData(created,samples,settings,log).also(::validate)
    }
}

/** Live state, monitoring enablement and OS permissions cannot be imported. */
object BackupSettings {
    val defaults = mapOf("power_profile" to "BALANCED", "interval_sec" to "30", "keep_awake" to "true",
        "vibrate" to "true", "vibrate_partial" to "false", "vibrate_offline" to "false", "vibrate_recovery" to "true",
        "notify_recovery" to "true", "event_sound" to "false", "vibration_pattern" to "STANDARD",
        "quiet_hours" to "false", "quiet_start_hour" to "23", "quiet_end_hour" to "8",
        "notify_outage" to "true", "notify_partial" to "false", "sound_outage" to "false",
        "sound_partial" to "false", "sound_recovery" to "false")
    val integerKeys = setOf("interval_sec","quiet_start_hour","quiet_end_hour")
    val stringKeys = setOf("power_profile","vibration_pattern")
    fun validate(values: Map<String,String>) {
        require(values.keys.all { it in defaults })
        values.forEach { (key,value) -> require(when (key) {
            "interval_sec" -> value.toIntOrNull()?.let { it in 10..120 } == true
            "quiet_start_hour", "quiet_end_hour" -> value.toIntOrNull()?.let { it in 0..23 } == true
            "power_profile" -> value in setOf("CUSTOM","BALANCED","ECONOMY","FREQUENT")
            "vibration_pattern" -> value in setOf("STANDARD","SHORT","LONG")
            else -> value == "true" || value == "false"
        }) { "Некорректная настройка: $key" } }
    }
}
