package ru.tgwatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class PreparedStatusCard(val snapshot: StatusShareSnapshot, val text: String,
    val file: File?, val bitmap: Bitmap?)

/** Process-wide physical bound survives rotation; a dead activity never owns a worker or bitmap. */
internal object StatusShareJobs {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "tg-status-card").apply { isDaemon = true } })
    private val ui = Handler(Looper.getMainLooper())
    fun submit(activity: StatusShareActivity, id: TargetId, revision: Long): Boolean {
        val app = activity.applicationContext
        val screen = WeakReference(activity)
        return enqueue {
            val result = runCatching { prepare(app, id) }
            ui.post {
                val delivered = screen.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                    ?.accept(revision, result) ?: false
                if (!delivered) result.getOrNull()?.bitmap?.recycle()
            }
        }
    }
    fun cleanup(ctx: Context) {
        val app = ctx.applicationContext
        enqueue { runCatching { StatusShareCache.cleanup(app) } }
    }
    private fun enqueue(job: () -> Unit): Boolean = try { worker.execute(job); true }
        catch (_: RejectedExecutionException) { false }
    private fun prepare(ctx: Context, id: TargetId): PreparedStatusCard {
        val snapshot = StatusShareSnapshot.capture(ctx, id)
        val text = StatusCardRenderer.text(snapshot)
        val file = try { StatusShareCache.write(ctx, snapshot) }
            catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
        val bitmap = try { file?.let { BitmapFactory.decodeFile(it.absolutePath) } }
            catch (_: OutOfMemoryError) { null }
        return PreparedStatusCard(snapshot, text, file, bitmap)
    }
}
