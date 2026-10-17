package ru.tgwatch

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/** User-initiated local export only. SAF chooses the destination; no upload or raw files. */
object LocalDiagnostics {
    const val EXPORT_REQUEST_CODE = 4313
    private val writing = AtomicBoolean(false)

    fun export(activity: Activity) {
        Lang.update(activity)
        try {
            activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "TgWatch-diagnostics.json"), EXPORT_REQUEST_CODE)
        } catch (_: Exception) {
            toast(activity, Lang.t("Не удалось открыть выбор файла", "Could not open the file picker"))
        }
    }

    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != EXPORT_REQUEST_CODE) return false
        val uri = data?.data
        if (resultCode == Activity.RESULT_OK && uri != null && writing.compareAndSet(false, true)) {
            write(activity, uri)
        }
        return true
    }

    @Suppress("TooGenericExceptionCaught") // Best-effort boundary; only the exception class is logged.
    private fun write(activity: Activity, uri: Uri) {
        val ctx = activity.applicationContext
        Thread({
            val success = try {
                check(!BackupManager.hasPending())
                val bytes = snapshot(ctx).toJson().toByteArray(Charsets.UTF_8)
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: error("No output stream")
                true
            } catch (e: Exception) {
                // The class only: messages from providers can carry document paths.
                Log.w("TgWatch", "diagnostics export: ${e.javaClass.simpleName}"); false
            } finally { writing.set(false) }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Lang.update(activity)
                    toast(activity, if (success) Lang.t("Диагностика сохранена", "Diagnostics saved")
                        else Lang.t("Не удалось сохранить диагностику", "Could not save diagnostics"))
                }
            }
        }, "diagnostic-export").start()
    }

    internal fun snapshot(ctx: Context): DiagnosticSnapshot {
        val app = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val alarms = ctx.getSystemService(AlarmManager::class.java)
        val notifications = ctx.getSystemService(NotificationManager::class.java)
        val current = MonitorService.state
        val stats = History.readStats(ctx)
        return DiagnosticSnapshot(
            appVersion = app.versionName.orEmpty(),
            appVersionCode = if (Build.VERSION.SDK_INT >= 28) app.longVersionCode
                else @Suppress("DEPRECATION") app.versionCode.toLong(),
            sdkInt = Build.VERSION.SDK_INT, running = MonitorService.running, status = current,
            stale = !MonitorService.running || current.checkedAt <= 0 ||
                current.isStale(System.currentTimeMillis()),
            notificationsAllowed = notifications.areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < 33 ||
                    ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED),
            exactAlarmsAllowed = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms(),
            batteryExempt = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            deviceIdleObserved = pm.isDeviceIdleMode, powerSave = pm.isPowerSaveMode,
            userUnlocked = ctx.getSystemService(UserManager::class.java).isUserUnlocked,
            dailyStats = (stats as? HistoryRead.Ready)?.value, endpoints = MonitorService.endpointOutcomes(),
            monitorHealth = MonitorService.healthSnapshot(),
            scheduling = MonitorService.schedulingDiagnostics(),
        )
    }

    private fun toast(activity: Activity, message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
