package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout

/** SAF documents and password entry; expensive crypto/storage work never runs on the UI thread. */
class BackupUi(private val activity: Activity) {
    private val dialogs = mutableListOf<Dialog>()
    private val jobs = BackupJobUi(activity, ::dismissDialogs, ::previewTargets, ::previewCsv)

    fun close() { jobs.close(); dismissDialogs() }
    fun resume() = jobs.onBackupChanged()
    private fun dismissDialogs() {
        val old = dialogs.toList()
        dialogs.clear()
        old.forEach { it.dismiss() }
    }

    fun export() {
        if (!jobs.available()) return
        AlertDialog.Builder(activity).setTitle(activity.renderString(R.string.copy_backup_ui_backup_format))
            .setItems(arrayOf(activity.renderString(R.string.copy_backup_ui_all_targets_v),
                activity.renderString(R.string.copy_backup_ui_telegram_only_compatible_v))) { _, index ->
                document(Intent.ACTION_CREATE_DOCUMENT, if (index == 0) EXPORT else EXPORT_LEGACY,
                    "application/octet-stream", if (index == 0) "tgwatch.tgwb" else "tgwatch-telegram-v2.tgwb")
            }.show().also { dialogs.add(it) }
    }
    fun restore() {
        if (!jobs.available()) return
        if (BackupManager.hasPending()) {
            AlertDialog.Builder(activity).setTitle(activity.renderString(R.string.copy_backup_ui_finish_restoring))
                .setMessage(activity.renderString(R.string.copy_backup_ui_the_previous_restore_was))
                .setPositiveButton(activity.renderString(R.string.copy_backup_ui_continue)) { _, _ ->
                    if (!BackupJobs.recover(activity.applicationContext)) jobs.busyMessage()
                }
                .setNegativeButton(activity.renderString(R.string.copy_backup_ui_cancel), null).show(
                    ).also { dialogs.add(it) }
            return
        }
        document(Intent.ACTION_OPEN_DOCUMENT, RESTORE, "*/*")
    }
    fun importCsv() = document(Intent.ACTION_OPEN_DOCUMENT, CSV, "*/*")

    @Suppress("DEPRECATION", "TooGenericExceptionCaught") // SAF failures collapse to one toast.
    private fun document(action: String, code: Int, type: String, name: String? = null) {
        if (!jobs.available()) return
        try {
            activity.startActivityForResult(Intent(action).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                this.type = type
                if (name != null) putExtra(Intent.EXTRA_TITLE, name)
            }, code)
        } catch (error: Exception) {
            UiErrors.show(activity, R.string.copy_backup_ui_couldn_t_open_the, error)
        }
    }

    fun onResult(code: Int, result: Int, data: Intent?): Boolean {
        if (code !in EXPORT..EXPORT_LEGACY) return false
        if (result != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        when (code) {
            EXPORT, EXPORT_LEGACY -> password(true) { pass ->
                if (!BackupJobs.export(activity.applicationContext, uri, pass, code == EXPORT_LEGACY))
                    jobs.busyMessage()
            }
            RESTORE -> password(false) { pass ->
                if (!BackupJobs.readBackup(activity.applicationContext, uri, pass)) jobs.busyMessage()
            }
            CSV -> if (!BackupJobs.readCsv(activity.applicationContext, uri)) jobs.busyMessage()
        }
        return true
    }

    private fun password(confirm: Boolean, action: (CharArray) -> Unit) {
        if (!jobs.available()) return
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

    private fun previewTargets(id: Long, imported: TargetBackupImport) {
        val snapshot = imported.snapshot
        val count = snapshot.observations.values.sumOf { it.size }
        AlertDialog.Builder(activity).setTitle(activity.renderString(R.string.copy_backup_ui_restore_this_backup))
            .setMessage(activity.renderString(
                R.string.copy_backup_ui_targets_history_records_data,
                snapshot.targets.size,
                count))
            .setPositiveButton(activity.renderString(R.string.copy_backup_ui_replace_data)) { _, _ -> jobs.apply(id) }
            .setNegativeButton(activity.renderString(R.string.copy_backup_ui_cancel)) { _, _ -> jobs.discard(id) }
            .setOnCancelListener { jobs.discard(id) }.show().also { dialogs.add(it) }
    }

    private fun previewCsv(id: Long, document: BackupJobResult.Csv) {
        val rows = document.rows
        val names = rows.keys.joinToString(", ") { TargetNames.label(activity, it) }
        val count = rows.values.sumOf { it.size }
        AlertDialog.Builder(activity).setTitle(activity.renderString(R.string.copy_backup_ui_import_csv))
            .setMessage(activity.renderString(R.string.copy_backup_ui_targets_records_history_for, names, count))
            .setPositiveButton(activity.renderString(R.string.copy_backup_ui_replace_data)) { _, _ -> jobs.apply(id) }
            .setNegativeButton(activity.renderString(R.string.copy_backup_ui_cancel)) { _, _ -> jobs.discard(id) }
            .setOnCancelListener { jobs.discard(id) }.show().also { dialogs.add(it) }
    }

    companion object {
        private const val EXPORT = 30
        private const val RESTORE = 31
        private const val CSV = 32
        private const val EXPORT_LEGACY = 33
    }
}
