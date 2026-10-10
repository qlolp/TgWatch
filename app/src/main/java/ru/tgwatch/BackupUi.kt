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
            AlertDialog.Builder(activity).setTitle("Завершить восстановление?")
                .setMessage("Предыдущее восстановление было прервано. Мониторинг останется выключен до завершения.")
                .setPositiveButton("Продолжить") { _, _ -> work("Восстановление…", {
                    BackupManager.recoverPending(activity.applicationContext)
                }, { activity.recreate() }, "Не удалось завершить восстановление. Повторите попытку.") }
                .setNegativeButton("Отмена", null).show().also { dialogs.add(it) }
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
        } catch (_: Exception) { message("Не удалось открыть выбор файла") }
    }

    fun onResult(code: Int, result: Int, data: Intent?): Boolean {
        if (code !in EXPORT..CSV) return false
        if (result != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        when (code) {
            EXPORT -> password(true) { pass -> work("Создание копии…", {
                try {
                    val archive = BackupManager.export(activity.applicationContext, pass)
                    activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(archive) }
                        ?: error("No output")
                } finally { pass.fill('\u0000') }
            }, { message("Зашифрованная копия сохранена. Для восстановления потребуется пароль.") },
                "Не удалось сохранить копию") }
            RESTORE -> password(false) { pass -> work("Проверка копии…", {
                try { BackupManager.decrypt(read(uri, BackupCrypto.MAX_ENVELOPE_BYTES), pass) }
                finally { pass.fill('\u0000') }
            }, { preview(it, "Восстановить копию?") }, "Неверный пароль или повреждённая копия") }
            CSV -> work("Проверка CSV…", {
                val bytes = read(uri, BackupCodec.MAX_PLAINTEXT_BYTES)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                BackupSnapshot(System.currentTimeMillis(), emptyMap(), CsvImport.parse(text), emptyList())
            }, { preview(it, "Импортировать CSV?", true) }, "CSV повреждён или имеет неподдерживаемый формат")
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
        val first = field(if (confirm) "Пароль: от 12 до 256 символов" else "Пароль этой копии")
        val second = if (confirm) field("Повторите пароль") else null
        val dialog = AlertDialog.Builder(activity).setTitle(if (confirm) "Пароль копии" else "Открыть копию")
            .setMessage(if (confirm) "Запомните пароль: восстановить его невозможно." else "Введите пароль этой копии.")
            .setView(layout).setPositiveButton("Продолжить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnDismissListener { first.text.clear(); second?.text?.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pass = first.text.toString().toCharArray()
                val repeated = second?.text?.toString()?.toCharArray()
                val valid = pass.size in (if (confirm) 12..256 else 1..1024) &&
                    (repeated == null || pass.contentEquals(repeated))
                repeated?.fill('\u0000')
                if (!valid) { pass.fill('\u0000'); first.error = "Проверьте длину и совпадение паролей" }
                else { dialog.dismiss(); action(pass) }
            }
        }
        dialog.show()
        dialogs.add(dialog)
    }

    private fun preview(snapshot: BackupSnapshot, title: String, csv: Boolean = false) {
        val date = DateFormat.getDateTimeInstance().format(Date(snapshot.createdAt))
        AlertDialog.Builder(activity).setTitle(title)
            .setMessage("Дата: $date\nЗаписей истории: ${snapshot.observations.size}\n" +
                (if (csv) "Настройки и журнал сохранятся. Текущая история будет заменена.\n\n" else
                    "Записей журнала: ${snapshot.log.size}\n\nТекущие данные будут заменены. ") +
                "Мониторинг будет остановлен; включите его вручную после восстановления.")
            .setPositiveButton("Заменить данные") { _, _ -> work("Восстановление…", {
                if (csv) BackupManager.importHistory(activity.applicationContext, snapshot.observations)
                else BackupManager.restore(activity.applicationContext, snapshot)
            }, { message("Данные восстановлены. Мониторинг выключен."); activity.recreate() },
                "Восстановление прервано. Нажмите «Восстановить резервную копию» для повторной попытки.") }
            .setNegativeButton("Отмена", null).show().also { dialogs.add(it) }
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
