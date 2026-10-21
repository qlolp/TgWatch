package ru.tgwatch

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor

/** A provider owns open/read/write/close latency; cancellation never releases its worker early. */
internal object BackupDocumentIo {
    private const val BUFFER_BYTES = 8192
    fun read(ctx: Context, uri: Uri, maxBytes: Int, control: BackupJobControl): ByteArray {
        val descriptor = open(ctx, uri, "r", control)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            control.onStop { input.close() }
            BoundedInput.read(input, maxBytes, control::check)
        }
    }

    fun write(ctx: Context, uri: Uri, bytes: ByteArray, control: BackupJobControl) {
        val descriptor = open(ctx, uri, "wt", control)
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            control.onStop { output.close() }
            var offset = 0
            while (offset < bytes.size) {
                control.check()
                val length = minOf(BUFFER_BYTES, bytes.size - offset)
                output.write(bytes, offset, length)
                offset += length
            }
            control.check()
            output.flush()
        }
    }

    private fun open(ctx: Context, uri: Uri, mode: String, control: BackupJobControl): ParcelFileDescriptor {
        control.check()
        val signal = CancellationSignal()
        control.onStop { signal.cancel() }
        val descriptor = ctx.contentResolver.openFileDescriptor(uri, mode, signal) ?: error("No document")
        // Register before checking again: an open which returns after timeout must also be closed.
        control.onStop { descriptor.close() }
        try { control.check() } catch (error: BackupJobStopped) { descriptor.close(); throw error }
        return descriptor
    }
}
