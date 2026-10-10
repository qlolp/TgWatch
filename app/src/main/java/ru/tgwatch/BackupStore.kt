package ru.tgwatch

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A private atomic journal makes an interrupted multi-file replacement replayable. */
object BackupStore {
    private fun journal(ctx: Context) = File(ctx.applicationContext.createDeviceProtectedStorageContext().filesDir,"restore.pending")
    @Synchronized fun snapshot(ctx: Context, now: Long = System.currentTimeMillis()): BackupData {
        finishPending(ctx)
        val preferences = Prefs.rawSp(ctx)
        val settings = BackupSettings.defaults.mapValues { (key,default) ->
            when {
                preferences.contains(key) -> preferences.all[key].toString()
                key == "power_profile" && preferences.contains("interval_sec") -> "CUSTOM"
                key in setOf("sound_outage","sound_recovery") -> preferences.getBoolean("event_sound",false).toString()
                else -> default
            }
        }
        val log = preferences.getString("log",null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        return BackupData(now,History.snapshot(ctx,now),settings,log).also(BackupCodec::validate)
    }
    @Synchronized fun restore(ctx: Context, data: BackupData) {
        BackupCodec.validate(data)
        check(!MonitorService.running && !Prefs.rawSp(ctx).getBoolean("enabled",true)) { "Сначала остановите мониторинг" }
        finishPending(ctx)
        val file = journal(ctx)
        val temp = File(file.parentFile,file.name + ".tmp")
        FileOutputStream(temp).use { it.write(BackupCodec.encode(data)); it.fd.sync() }
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        finishPending(ctx)
    }
    @Synchronized fun finishPending(ctx: Context) {
        val file = journal(ctx)
        if (!file.exists()) return
        check(!MonitorService.running) { "Восстановление требует остановки мониторинга" }
        val data = file.inputStream().use { BackupCodec.decode(BackupCodec.readBounded(it)) }
        History.replace(ctx,data.samples)
        val preferences = Prefs.rawSp(ctx)
        val editor = preferences.edit()
        BackupSettings.defaults.keys.forEach(editor::remove)
        preferences.all.keys.filter { it.startsWith("last_") }.forEach(editor::remove)
        (BackupSettings.defaults + data.settings).forEach { (key,value) -> when (key) {
            in BackupSettings.integerKeys -> editor.putInt(key,value.toInt())
            in BackupSettings.stringKeys -> editor.putString(key,value)
            else -> editor.putBoolean(key,value.toBooleanStrict())
        } }
        editor.putBoolean("enabled",false).putString("log",data.log.joinToString("\n"))
        check(editor.commit()) { "Не удалось сохранить настройки; восстановление будет повторено" }
        EventLog.invalidate()
        MonitorService.resetStoppedState()
        check(file.delete()) { "Не удалось завершить восстановление" }
    }
}
