package ru.tgwatch.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** A private installation capability prevents forged exported-activity intents from running checks. */
internal object WatchSurfaceAction {
    const val EXTRA = "ru.tgwatch.wear.SURFACE_CHECK"
    @Volatile var ticket: String? = null
        private set
    fun initialize(context: Context) {
        val file = AtomicFile(File(context.filesDir, "surface-capability-v1"))
        ticket = runCatching {
            file.openRead().use { stream ->
                val bytes = ByteArray(32)
                java.io.DataInputStream(stream).readFully(bytes)
                require(stream.read() == -1)
                bytes.toString(Charsets.US_ASCII).also { require(it.matches(Regex("[0-9a-f]{32}"))) }
            }
        }.getOrElse {
            val value = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val output = file.startWrite()
            try { output.write(value.toByteArray(Charsets.US_ASCII)); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
            value
        }
    }
    fun valid(value: String?): Boolean {
        val expected = ticket ?: return false
        return value?.length == expected.length && MessageDigest.isEqual(expected.toByteArray(), value.toByteArray())
    }
    fun intent(context: Context): Intent = Intent(context, WatchActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply { ticket?.let { putExtra(EXTRA, it) } }
    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 1, intent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
