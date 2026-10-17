package ru.tgwatch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Capture the selected goal before SAF; rotation or later selection changes cannot switch the export. */
internal class TargetCsvExportUi(private val activity: Activity, saved: Bundle?) {
    private var days = saved?.getInt("export_days", MAX_DAYS) ?: MAX_DAYS
    private var targetId = TargetId.fromWire(saved?.getString("export_target").orEmpty()) ?: TargetId.TELEGRAM
    private companion object {
        const val REQUEST = 20
        const val MAX_DAYS = 7
        val writing = AtomicBoolean()
    }
    fun export(id: TargetId, window: Int) {
        if (writing.get()) return
        targetId = id
        days = window.coerceIn(1, MAX_DAYS)
        try {
            activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv")
                .putExtra(Intent.EXTRA_TITLE, "tgwatch-${id.wire}-${if (days == 1) "day" else "week"}.csv"), REQUEST)
        } catch (_: Exception) {
            message(Lang.t("Не удалось открыть сохранение файла", "Couldn’t open the file save dialog"))
        }
    }
    fun save(out: Bundle) { out.putInt("export_days", days); out.putString("export_target", targetId.wire) }
    fun onResult(code: Int, result: Int, data: Intent?): Boolean {
        if (code != REQUEST) return false
        val uri = data?.data
        if (result == Activity.RESULT_OK && uri != null && writing.compareAndSet(false, true)) write(uri)
        return true
    }
    private fun write(uri: android.net.Uri) {
        val id = targetId
        val window = days
        val app = activity.applicationContext
        val screen = WeakReference(activity)
        Thread({
            val success = try {
                check(!BackupManager.hasPending())
                val now = System.currentTimeMillis()
                val rows = TargetArchive.rows(app, id, now)
                val from = (now - window * CoverageStats.DAY_MS).coerceAtLeast(0)
                val csv = TargetCsv.encode(mapOf(id to rows), from, now)
                app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)
                    ?.use { it.write(csv) } ?: error("No output")
                true
            } catch (_: Exception) { false }
            finally { writing.set(false) }
            screen.get()?.runOnUiThread {
                screen.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let {
                    Toast.makeText(it, if (success) Lang.t("CSV сохранён", "CSV saved")
                        else Lang.t("Не удалось сохранить CSV", "Couldn’t save the CSV"), Toast.LENGTH_LONG).show()
                }
            }
        }, "tg-export").start()
    }
    private fun message(value: String) = Toast.makeText(activity, value, Toast.LENGTH_LONG).show()
}
