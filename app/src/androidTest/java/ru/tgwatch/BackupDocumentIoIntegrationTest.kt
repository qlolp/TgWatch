package ru.tgwatch

import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDocumentIoIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realProviderDescriptorsRoundTripAndEnforceByteLimit() {
        val file = document()
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".status-share", file)
            val bytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
            BackupDocumentIo.write(ctx, uri, bytes, control())
            assertArrayEquals(bytes, BackupDocumentIo.read(ctx, uri, bytes.size, control()))
            try { BackupDocumentIo.read(ctx, uri, bytes.size - 1, control()); fail("Byte budget must hold") }
            catch (_: IllegalArgumentException) { }
        } finally { file.delete() }
    }

    @Test fun cancelledWriteNeverOpensOrTruncatesTheDocument() {
        val file = document()
        val original = "existing backup".toByteArray()
        file.writeBytes(original)
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".status-share", file)
            val cancelled = control()
            cancelled.stop(BackupJobPhase.CANCELLED)
            try { BackupDocumentIo.write(ctx, uri, byteArrayOf(1), cancelled); fail("Cancelled write admitted") }
            catch (_: BackupJobStopped) { }
            assertArrayEquals(original, file.readBytes())
        } finally { file.delete() }
    }

    private fun document(): File = File(ctx.cacheDir, "status-share").let { directory ->
        check(directory.isDirectory || directory.mkdirs())
        File.createTempFile("backup-io-", ".fixture", directory)
    }
    private fun control() = BackupJobControl(SystemClock::elapsedRealtime,
        SystemClock.elapsedRealtime() + 5000, Executor { it.run() })
}
