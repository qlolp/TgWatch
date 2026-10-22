package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** SAF documents and password entry; expensive crypto/storage work never runs on the UI thread. */
class BackupUi(private val activity: Activity) {
    private data class CsvDocument(val rows: Map<TargetId, List<TargetObservation>>, val legacy: Boolean)
    private var busy = false
    private val dialogs = mutableListOf<Dialog>()

    fun close() { dialogs.forEach { it.dismiss() }; dialogs.clear() }

    fun export() {
        AlertDialog.Builder(activity).setTitle(activity.renderString(R.string.copy_backup_ui_backup_format))
            .setItems(arrayOf(activity.renderString(R.string.copy_backup_ui_all_targets_v),
                activity.renderString(R.string.copy_backup_ui_telegram_only_compatible_v))) { _, index ->
                document(Intent.ACTION_CREATE_DOCUMENT, if (index == 0) EXPORT else EXPORT_LEGACY,
                    "application/octet-stream", if (index == 0) "tgwatch.tgwb" else "tgwatch-telegram-v2.tgwb")
            }.show().also { dialogs.add(it) }
    }
    fun restore() {
        if (BackupManager.hasPending()) {
            AlertDialog.Builder(activity).setTitle(activity.renderString(R.string.copy_backup_ui_finish_restoring))
                .setMessage(activity.renderString(R.string.copy_backup_ui_the_previous_restore_was))
                .setPositiveButton(activity.renderString(R.string.copy_backup_ui_continue)) { _, _ -> work(
                    activity.renderString(
                    R.string.copy_backup_ui_restoring), {
                    BackupManager.recoverPending(activity.applicationContext)
                }, { activity.recreate() }, R.string.copy_backup_ui_couldn_t_finish_restoring) }
                .setNegativeButton(activity.renderString(R.string.copy_backup_ui_cancel), null).show(
                    ).also { dialogs.add(it) }
            return
        }
        document(Intent.ACTION_OPEN_DOCUMENT, RESTORE, "*/*")
    }
    fun importCsv() = document(Intent.ACTION_OPEN_DOCUMENT, CSV, "*/*")

    @Suppress("DEPRECATION", "TooGenericExceptionCaught") // SAF failures collapse to one toast.
    private fun document(action: String, code: Int, type: String, name: String? = null) {
        if (busy) return
        try {
            activity.startActivityForResult(Intent(action).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                this.type = type
                if (name != null) putExtra(Intent.EXTRA_TITLE, name)
            }, code)
        } catch (e: Exception) {
            UiErrors.show(activity, R.string.copy_backup_ui_couldn_t_open_the, e)
        }
    }

    fun onResult(code: Int, result: Int, data: Intent?): Boolean {
        if (code !in EXPORT..EXPORT_LEGACY) return false
        if (result != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        when (code) {
            EXPORT, EXPORT_LEGACY -> password(true) { pass -> work(activity.renderString(
                R.string.copy_backup_ui_creating_backup), {
                try {
                    val archive = if (code == EXPORT) BackupManager.export(activity.applicationContext, pass)
                        else BackupManager.exportTelegram(activity.applicationContext, pass)
                    activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(archive) }
                        ?: error("No output")
                } finally { pass.fill('\u0000') }
            }, { message(activity.renderString(R.string.copy_backup_ui_encrypted_backup_saved_you)) },
                R.string.copy_backup_ui_couldn_t_save_the) }
            RESTORE -> password(false) { pass -> work(activity.renderString(
                R.string.copy_backup_ui_checking_backup), {
                try { PortableBackupCodec.decryptTargetImport(read(uri, PortableBackupCodec.MAX_ENVELOPE_BYTES), pass) }
                finally { pass.fill('\u0000') }
            }, { previewTargets(it) }, R.string.copy_backup_ui_wrong_password_or_damaged) }
            CSV -> work(activity.renderString(R.string.copy_backup_ui_checking_csv), {
                val bytes = read(uri, TargetBackupCodec.MAX_PLAINTEXT_BYTES)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                CsvDocument(TargetCsv.parse(text), TargetCsv.isLegacy(text))
            }, { previewCsv(it) }, R.string.copy_backup_ui_the_csv_is_damaged)
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
            minHeight = resources.getDimensionPixelSize(R.dimen.touch_target_min)
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            layout.addView(this)
        }
        val first = field(if (confirm) activity.renderString(
            R.string.copy_backup_ui_password_to_characters) else activity.renderString(
            R.string.copy_backup_ui_password_for_this_backup))
        val second = if (confirm) field(activity.renderString(R.string.copy_backup_ui_repeat_the_password)) else null
        val dialog = AlertDialog.Builder(activity).setTitle(if (confirm) activity.renderString(
            R.string.copy_backup_ui_backup_password) else activity.renderString(
                R.string.copy_backup_ui_open_backup))
            .setMessage(if (confirm) activity.renderString(
                R.string.copy_backup_ui_remember_the_password_it) else activity.renderString(
                R.string.copy_backup_ui_enter_the_password_for))
            .setView(layout).setPositiveButton(activity.renderString(R.string.copy_backup_ui_continue),
                null).setNegativeButton(activity.renderString(
                R.string.copy_backup_ui_cancel), null).create()
        dialog.setOnDismissListener { first.text.clear(); second?.text?.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pass = first.text.toString().toCharArray()
                val repeated = second?.text?.toString()?.toCharArray()
                val valid = pass.size in (if (confirm) 12..256 else 1..1024) &&
                    (repeated == null || pass.contentEquals(repeated))
                repeated?.fill('\u0000')
                if (!valid) { pass.fill('\u0000'); first.error = activity.renderString(
                    R.string.copy_backup_ui_check_the_password_length) }
                else { dialog.dismiss(); action(pass) }
            }
        }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialogs.add(dialog)
    }

    private fun previewTargets(imported: TargetBackupImport) {
        val snapshot = imported.snapshot
        val count = snapshot.observations.values.sumOf { it.size }
        AlertDialog.Builder(activity).setTitle(activity.renderString(R.string.copy_backup_ui_restore_this_backup))
            .setMessage(activity.renderString(
                R.string.copy_backup_ui_targets_history_records_data,
                snapshot.targets.size,
                count))
            .setPositiveButton(activity.renderString(R.string.copy_backup_ui_replace_data)) { _, _ ->
                work(activity.renderString(R.string.copy_backup_ui_restoring), {
                    TargetRestore.stage(activity.applicationContext, imported)
                    BackupManager.recoverPending(activity.applicationContext)
                }, { message(activity.renderString(
                    R.string.copy_backup_ui_data_restored_all_targets)); activity.recreate() },
                    R.string.copy_backup_ui_restore_interrupted_please_retry)
            }.setNegativeButton(activity.renderString(R.string.copy_backup_ui_cancel), null).show(
                ).also { dialogs.add(it) }
    }

    private fun previewCsv(document: CsvDocument) {
        val rows = document.rows
        val names = rows.keys.joinToString(", ") { TargetNames.label(activity, it) }
        val count = rows.values.sumOf { it.size }
        AlertDialog.Builder(activity).setTitle(activity.renderString(R.string.copy_backup_ui_import_csv))
            .setMessage(activity.renderString(R.string.copy_backup_ui_targets_records_history_for, names, count))
            .setPositiveButton(activity.renderString(R.string.copy_backup_ui_replace_data)) { _, _ ->
                work(activity.renderString(R.string.copy_backup_ui_restoring), {
                    if (document.legacy) BackupManager.importHistory(activity.applicationContext,
                        rows.getValue(TargetId.TELEGRAM).map { value ->
                            value.row.copy(kind = Timeline.canonicalKind(value.row.kind)) })
                    else BackupManager.importHistories(activity.applicationContext, rows)
                }, { message(activity.renderString(
                    R.string.copy_backup_ui_history_restored_all_targets)); activity.recreate() },
                    R.string.copy_backup_ui_restore_interrupted_please_retry)
            }.setNegativeButton(activity.renderString(R.string.copy_backup_ui_cancel), null).show(
                ).also { dialogs.add(it) }
    }

    @Suppress("DEPRECATION")
    private fun <T> work(title: String, block: () -> T, success: (T) -> Unit, failure: Int) {
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
                    result.fold(success) { error -> UiErrors.show(activity, failure, error) }
                }
            }
        }, "tg-backup").start()
    }

    private fun message(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    companion object {
        private const val EXPORT = 30
        private const val RESTORE = 31
        private const val CSV = 32
        private const val EXPORT_LEGACY = 33
    }
}
