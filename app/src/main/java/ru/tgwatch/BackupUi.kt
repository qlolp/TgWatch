package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.DateFormat
import java.util.Date

/** SAF documents and password entry; expensive crypto/storage work never runs on the UI thread. */
class BackupUi(private val activity: Activity) {
    private var busy = false
    private val dialogs = mutableListOf<Dialog>()

    fun close() { dialogs.forEach { it.dismiss() }; dialogs.clear() }

    fun export() = document(Intent.ACTION_CREATE_DOCUMENT, EXPORT, "application/octet-stream", "tgwatch.tgwb")
    fun restore() {
        if (BackupManager.hasPending(activity)) {
            AlertDialog.Builder(activity).setTitle(Lang.t("Завершить восстановление?", "Finish restoring?"))
                .setMessage(Lang.t("Предыдущее восстановление было прервано. Мониторинг останется выключен до завершения.", "The previous restore was interrupted. Monitoring stays off until it is finished."))
                .setPositiveButton(Lang.t("Продолжить", "Continue")) { _, _ -> work(Lang.t("Восстановление…", "Restoring…"), {
                    BackupManager.recoverPending(activity.applicationContext)
                }, { activity.recreate() }, Lang.t("Не удалось завершить восстановление. Повторите попытку.", "Couldn’t finish restoring. Please try again.")) }
                .setNegativeButton(Lang.t("Отмена", "Cancel"), null).show().also { dialogs.add(it) }
            return
        }
        document(Intent.ACTION_OPEN_DOCUMENT, RESTORE, "*/*")
    }
    fun importCsv() = document(Intent.ACTION_OPEN_DOCUMENT, CSV, "*/*")

    @Suppress("DEPRECATION")
    private fun document(action: String, code: Int, type: String, name: String? = null) {
        if (busy) return
        try {
            activity.startActivityForResult(Intent(action).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                this.type = type
                if (name != null) putExtra(Intent.EXTRA_TITLE, name)
            }, code)
        } catch (_: Exception) { message(Lang.t("Не удалось открыть выбор файла", "Couldn’t open the file picker")) }
    }

    fun onResult(code: Int, result: Int, data: Intent?): Boolean {
        if (code !in EXPORT..CSV) return false
        if (result != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        when (code) {
            EXPORT -> password(true) { pass -> work(Lang.t("Создание копии…", "Creating backup…"), {
                try {
                    val archive = BackupManager.export(activity.applicationContext, pass)
                    activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(archive) }
                        ?: error("No output")
                } finally { pass.fill('\u0000') }
            }, { message(Lang.t("Зашифрованная копия сохранена. Для восстановления потребуется пароль.", "Encrypted backup saved. You will need the password to restore it.")) },
                Lang.t("Не удалось сохранить копию", "Couldn’t save the backup")) }
            RESTORE -> password(false) { pass -> work(Lang.t("Проверка копии…", "Checking backup…"), {
                try { BackupManager.decrypt(read(uri, BackupCrypto.MAX_ENVELOPE_BYTES), pass) }
                finally { pass.fill('\u0000') }
            }, { preview(it, Lang.t("Восстановить копию?", "Restore this backup?")) }, Lang.t("Неверный пароль или повреждённая копия", "Wrong password or damaged backup")) }
            CSV -> work(Lang.t("Проверка CSV…", "Checking CSV…"), {
                val bytes = read(uri, BackupCodec.MAX_PLAINTEXT_BYTES)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                BackupSnapshot(System.currentTimeMillis(), emptyMap(), CsvImport.parse(text), emptyList())
            }, { preview(it, Lang.t("Импортировать CSV?", "Import CSV?"), true) }, Lang.t("CSV повреждён или имеет неподдерживаемый формат", "The CSV is damaged or in an unsupported format"))
        }
        return true
    }

    private fun read(uri: Uri, maxBytes: Int): ByteArray = activity.contentResolver.openInputStream(uri)
        ?.use { BoundedInput.read(it, maxBytes) } ?: error("No input")

    private fun password(confirm: Boolean, action: (CharArray) -> Unit) {
        if (busy) return
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        fun field(hint: String) = EditText(activity).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            layout.addView(this)
        }
        val first = field(if (confirm) Lang.t("Пароль: от 12 до 256 символов", "Password: 12 to 256 characters") else Lang.t("Пароль этой копии", "Password for this backup"))
        val second = if (confirm) field(Lang.t("Повторите пароль", "Repeat the password")) else null
        val dialog = AlertDialog.Builder(activity).setTitle(if (confirm) Lang.t("Пароль копии", "Backup password") else Lang.t("Открыть копию", "Open backup"))
            .setMessage(if (confirm) Lang.t("Запомните пароль: восстановить его невозможно.", "Remember the password: it cannot be recovered.") else Lang.t("Введите пароль этой копии.", "Enter the password for this backup."))
            .setView(layout).setPositiveButton(Lang.t("Продолжить", "Continue"), null).setNegativeButton(Lang.t("Отмена", "Cancel"), null).create()
        dialog.setOnDismissListener { first.text.clear(); second?.text?.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pass = first.text.toString().toCharArray()
                val repeated = second?.text?.toString()?.toCharArray()
                val valid = pass.size in (if (confirm) 12..256 else 1..1024) &&
                    (repeated == null || pass.contentEquals(repeated))
                repeated?.fill('\u0000')
                if (!valid) { pass.fill('\u0000'); first.error = Lang.t("Проверьте длину и совпадение паролей", "Check the password length and that both entries match") }
                else { dialog.dismiss(); action(pass) }
            }
        }
        dialog.show()
        dialogs.add(dialog)
    }

    private fun preview(snapshot: BackupSnapshot, title: String, csv: Boolean = false) {
        val date = DateFormat.getDateTimeInstance().format(Date(snapshot.createdAt))
        AlertDialog.Builder(activity).setTitle(title)
            .setMessage(Lang.t("Дата: $date\nЗаписей истории: ${snapshot.observations.size}\n", "Date: $date\nHistory records: ${snapshot.observations.size}\n") +
                (if (csv) Lang.t("Настройки и журнал сохранятся. Текущая история будет заменена.\n\n", "Settings and the log will be kept. The current history will be replaced.\n\n") else
                    Lang.t("Записей журнала: ${snapshot.log.size}\n\nТекущие данные будут заменены. ", "Log records: ${snapshot.log.size}\n\nThe current data will be replaced. ")) +
                Lang.t("Мониторинг будет остановлен; включите его вручную после восстановления.", "Monitoring will be stopped; turn it back on manually after restoring."))
            .setPositiveButton(Lang.t("Заменить данные", "Replace data")) { _, _ -> work(Lang.t("Восстановление…", "Restoring…"), {
                if (csv) BackupManager.importHistory(activity.applicationContext, snapshot.observations)
                else BackupManager.restore(activity.applicationContext, snapshot)
            }, { message(Lang.t("Данные восстановлены. Мониторинг выключен.", "Data restored. Monitoring is off.")); activity.recreate() },
                Lang.t("Восстановление прервано. Нажмите «Восстановить резервную копию» для повторной попытки.", "Restore interrupted. Tap “Restore backup” to try again.")) }
            .setNegativeButton(Lang.t("Отмена", "Cancel"), null).show().also { dialogs.add(it) }
    }

    @Suppress("DEPRECATION")
    private fun <T> work(title: String, block: () -> T, success: (T) -> Unit, failure: String) {
        if (busy) return
        busy = true
        val progress = ProgressDialog(activity).apply { setMessage(title); setCancelable(false); show() }
        dialogs.add(progress)
        Thread({
            val result = runCatching(block)
            activity.runOnUiThread {
                busy = false
                progress.dismiss()
                dialogs.remove(progress)
                if (!activity.isFinishing && !activity.isDestroyed) {
                    result.fold(success) { message(failure) }
                }
            }
        }, "tg-backup").start()
    }

    private fun message(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    companion object { private const val EXPORT = 30; private const val RESTORE = 31; private const val CSV = 32 }
}
