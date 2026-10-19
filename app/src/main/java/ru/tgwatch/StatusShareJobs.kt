package ru.tgwatch

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
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

internal class PreparedStatusCard(val document: StatusShareDocument, val file: File?, val bitmap: Bitmap?) {
    val snapshot: StatusShareSnapshot get() = document.snapshot
    val text: String get() = document.text
}

/** Process-wide physical bound survives rotation; a dead activity never owns a worker or bitmap. */
internal object StatusShareJobs {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "tg-status-card").apply { isDaemon = true } })
    private val ui = Handler(Looper.getMainLooper())
    fun submit(activity: StatusShareActivity, id: TargetId, revision: Long, localeTag: String): Boolean {
        val app = captureContext(activity, localeTag)
        val screen = WeakReference(activity)
        return enqueue(Preparation(screen) {
            val result = runCatching { prepare(app, id) }
            ui.post {
                val delivered = screen.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                    ?.accept(revision, result) ?: false
                if (!delivered) result.getOrNull()?.bitmap?.recycle()
            }
        })
    }
    internal fun captureContext(ctx: Context,
        localeTag: String = ctx.resources.configuration.locales[0].toLanguageTag()): Context {
        require(StatusShareSnapshot.validLocaleTag(localeTag))
        val config = Configuration(ctx.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(localeTag)))
        }
        return ctx.applicationContext.createConfigurationContext(config)
    }
    fun cleanup(ctx: Context) {
        val app = ctx.applicationContext
        enqueue { runCatching { StatusShareCache.cleanup(app) } }
    }
    /** Only queued work is discarded; an active physical render retains its worker until it returns. */
    internal fun discardQueued(activity: StatusShareActivity) {
        worker.queue.filterIsInstance<Preparation>().forEach { task ->
            if (task.screen.get() === activity) worker.remove(task)
        }
    }
    private class Preparation(val screen: WeakReference<StatusShareActivity>, private val work: () -> Unit) : Runnable {
        override fun run() = work()
    }
    private fun enqueue(job: Runnable): Boolean = try { worker.execute(job); true }
        catch (_: RejectedExecutionException) { false }
    private fun prepare(ctx: Context, id: TargetId): PreparedStatusCard {
        val snapshot = StatusShareSnapshot.capture(ctx, id)
        val document = StatusShareDocument.prepare(ctx, snapshot)
        val file = try { StatusShareCache.write(ctx, document) }
            catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
        val bitmap = try { file?.let { BitmapFactory.decodeFile(it.absolutePath) } }
            catch (_: OutOfMemoryError) { null }
        return PreparedStatusCard(document, file, bitmap)
    }
}
