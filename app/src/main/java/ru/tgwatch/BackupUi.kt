package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/** Passwords stay in transient dialog/worker memory; file IO and PBKDF2 run off the UI thread. */
class BackupUi(private val activity: Activity) {
    private val busy = AtomicBoolean(false)
    fun selectFile(restore: Boolean) {
        if (busy.get()) { message("Операция ещё выполняется"); return }
        if (restore && !stopped()) return
        val intent = Intent(if (restore) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (restore) "*/*" else "application/octet-stream"
            if (!restore) putExtra(Intent.EXTRA_TITLE,"tgwatch-backup.tgwatch")
        }
        try { activity.startActivityForResult(intent,if (restore) 22 else 21) }
        catch (_: Exception) { message("Не удалось открыть выбор файла") }
    }
    fun result(request: Int, result: Int, intent: Intent?): Boolean {
        if (request !in 21..22) return false
        if (result != Activity.RESULT_OK) return true
        val uri = intent?.data ?: return true
        if (request == 22 && !stopped()) return true
        passwordDialog(uri,request == 22)
        return true
    }
    private fun stopped(): Boolean {
        if (!MonitorService.running && !Prefs.isEnabled(activity)) return true
        message("Для восстановления сначала нажмите «Остановить»")
        return false
    }
    private fun passwordDialog(uri: Uri, restore: Boolean) {
        val fields = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(32,8,32,8) }
        fun field(hintText: String) = EditText(activity).apply {
            hint=hintText; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters=arrayOf(InputFilter.LengthFilter(256)); setSaveEnabled(false); fields.addView(this)
        }
        val password = field("Пароль бэкапа")
        val confirmation = if (restore) null else field("Повторите пароль (минимум 12 символов)")
        val dialog = AlertDialog.Builder(activity).setTitle(if (restore) "Открыть бэкап" else "Зашифровать бэкап")
            .setMessage(if (restore) "Введите пароль, заданный при сохранении файла."
                else "Сохраните пароль отдельно. Без него восстановить этот файл нельзя.")
            .setView(fields).setPositiveButton("Продолжить",null).setNegativeButton("Отмена",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chars = CharArray(password.length()) { password.text[it] }
                if (!restore && (chars.size < 12 || password.text.toString() != confirmation!!.text.toString())) {
                    chars.fill('\u0000'); password.error="Пароли должны совпадать и содержать минимум 12 символов"
                    return@setOnClickListener
                }
                password.text.clear(); confirmation?.text?.clear(); dialog.dismiss()
                operation(uri,restore,chars)
            }
        }
        dialog.setOnDismissListener { password.text.clear(); confirmation?.text?.clear() }
        dialog.show()
    }
    private fun operation(uri: Uri, restore: Boolean, password: CharArray) {
        if (!busy.compareAndSet(false,true)) { password.fill('\u0000'); message("Операция ещё выполняется"); return }
        message(if (restore) "Расшифровываю…" else "Сохраняю бэкап…")
        val app = activity.applicationContext
        Thread({
            try {
                if (restore) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use(BackupCodec::readBounded) ?: error("Нет файла")
                    val data = BackupCodec.decrypt(bytes,password)
                    activity.runOnUiThread { if (alive()) confirmRestore(data) }
                } else {
                    val bytes = BackupCodec.encrypt(BackupStore.snapshot(app),password)
                    app.contentResolver.openOutputStream(uri,"wt")?.use { it.write(bytes) } ?: error("Нет файла")
                    activity.runOnUiThread { if (alive()) message("Зашифрованный бэкап сохранён") }
                }
            } catch (_: Exception) {
                activity.runOnUiThread { if (alive()) message(if (restore)
                    "Не удалось открыть бэкап: проверьте пароль, формат и целостность файла. Данные не изменены."
                    else "Не удалось сохранить бэкап") }
            } finally { password.fill('\u0000'); busy.set(false) }
        },"tg-backup").start()
    }
    private fun confirmRestore(data: BackupData) {
        if (!stopped()) return
        AlertDialog.Builder(activity).setTitle("Заменить данные приложения?")
            .setMessage("В бэкапе: ${data.samples.size} проверок, ${data.log.size} событий. Текущие история, журнал и настройки будут заменены. Мониторинг останется остановленным; хранится история за последние 7 дней.")
            .setNegativeButton("Отмена",null).setPositiveButton("Восстановить") { _,_ ->
                if (!stopped() || !busy.compareAndSet(false,true)) return@setPositiveButton
                Thread({
                    val success = try { BackupStore.restore(activity.applicationContext,data); true } catch (_: Exception) { false }
                    activity.runOnUiThread { if (alive()) {
                        message(if (success) "Данные восстановлены. Нажмите «Запустить», когда будете готовы."
                            else "Не удалось завершить восстановление. При следующем запуске оно будет повторено.")
                        if (success) activity.recreate()
                    } }
                    busy.set(false)
                },"tg-restore").start()
            }.show()
    }
    private fun alive() = !activity.isFinishing && !activity.isDestroyed
    private fun message(text: String) { Toast.makeText(activity,text,Toast.LENGTH_LONG).show() }
}
