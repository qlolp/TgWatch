package ru.tgwatch

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One physical settings job and one waiting job survive activity rotation without retaining the screen. */
internal object TargetSettingsExecutor {
    private val busy = ConcurrentHashMap.newKeySet<TargetId>()
    private val ui = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "tg-target-settings").apply { isDaemon = true } })
    fun busy(id: TargetId) = id in busy

    fun submit(activity: Activity, id: TargetId, operation: () -> Boolean): Boolean {
        if (!busy.add(id)) return false
        val screen = WeakReference(activity)
        return try {
            worker.execute {
                val result = try { execute(operation) }
                    finally { busy.remove(id) }
                ui.post { screen.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let {
                    Toast.makeText(it, if (result) R.string.target_saved else R.string.target_settings_failed,
                        Toast.LENGTH_LONG).show()
                } }
            }
            true
        } catch (_: RejectedExecutionException) { busy.remove(id); false }
    }

    private fun execute(operation: () -> Boolean): Boolean = try { operation() }
        catch (_: RuntimeException) { false }
}
