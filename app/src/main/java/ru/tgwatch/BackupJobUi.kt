package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.widget.Toast

/** A disposable observer. The process job never retains this presenter or its Activity. */
internal class BackupJobUi(private val activity: Activity, private val dismissPreview: () -> Unit,
    private val previewImport: (Long, TargetBackupImport) -> Unit,
    private val previewCsv: (Long, BackupJobResult.Csv) -> Unit) : BackupJobs.Observer {
    private val dialogs = mutableListOf<Dialog>()
    private var closed = false
    private var shown: String? = null
    init { BackupJobs.attach(this) }
    fun close() { closed = true; BackupJobs.detach(this); dismissDialogs() }
    private fun dismissDialogs() {
        val old = dialogs.toList()
        dialogs.clear()
        old.forEach { it.dismiss() }
    }
    fun available(): Boolean = BackupJobs.available().also { if (!it) busyMessage() }
    fun busyMessage() = message(activity.getString(R.string.backup_job_busy))
    fun apply(id: Long) {
        if (!BackupJobs.apply(activity.applicationContext, id)) { shown = null; onBackupChanged(); busyMessage() }
    }
    fun discard(id: Long) { BackupJobs.coordinator.dismiss(id) }
    fun message(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    override fun onBackupChanged() {
        val state = BackupJobs.coordinator.snapshot()
        val key = state?.let { "${it.id}:${it.phase}:${it.physicalRunning}" }
        val visible = !closed && !activity.isFinishing && !activity.isDestroyed
        if (!visible || shown == key) return
        shown = key
        dismissDialogs()
        dismissPreview()
        if (state != null) when {
            state.phase == BackupJobPhase.RUNNING -> progress(state)
            state.physicalRunning -> AlertDialog.Builder(activity).setMessage(R.string.backup_job_stopping)
                .setPositiveButton(android.R.string.ok, null).show().also { dialogs.add(it) }
            state.phase == BackupJobPhase.SUCCEEDED -> success(state)
            else -> { discard(state.id); message(activity.getString(backupJobOutcome(state))) }
        }
    }
    private fun success(state: BackupJobSnapshot<BackupJobResult>) {
        when (val result = state.value) {
            is BackupJobResult.Import -> previewImport(state.id, result.data)
            is BackupJobResult.Csv -> previewCsv(state.id, result)
            BackupJobResult.Saved -> {
                discard(state.id)
                message(activity.renderString(R.string.copy_backup_ui_encrypted_backup_saved_you))
            }
            BackupJobResult.Applied -> {
                discard(state.id)
                message(activity.renderString(if (state.kind == BackupJobKind.APPLY_CSV)
                    R.string.copy_backup_ui_history_restored_all_targets
                    else R.string.copy_backup_ui_data_restored_all_targets))
                activity.recreate()
            }
            null -> { discard(state.id); message(activity.getString(backupJobOutcome(state))) }
        }
    }
    @Suppress("DEPRECATION")
    private fun progress(state: BackupJobSnapshot<BackupJobResult>) {
        val dialog = ProgressDialog(activity).apply {
            setMessage(activity.getString(backupJobTitle(state.kind)))
            setCancelable(state.cancellable)
            setCanceledOnTouchOutside(false)
            if (state.cancellable) {
                setButton(Dialog.BUTTON_NEGATIVE, activity.getString(R.string.copy_backup_ui_cancel)) { _, _ ->
                    BackupJobs.coordinator.cancel(state.id)
                }
                setOnCancelListener { BackupJobs.coordinator.cancel(state.id) }
            }
            show()
        }
        dialogs.add(dialog)
    }
}

private fun backupJobTitle(kind: BackupJobKind) = when (kind) {
    BackupJobKind.EXPORT, BackupJobKind.EXPORT_LEGACY -> R.string.copy_backup_ui_creating_backup
    BackupJobKind.READ_BACKUP -> R.string.copy_backup_ui_checking_backup
    BackupJobKind.READ_CSV -> R.string.copy_backup_ui_checking_csv
    else -> R.string.copy_backup_ui_restoring
}
private fun backupJobOutcome(state: BackupJobSnapshot<BackupJobResult>): Int = when (state.phase) {
    BackupJobPhase.CANCELLED -> R.string.backup_job_cancelled
    BackupJobPhase.TIMED_OUT -> R.string.backup_job_timed_out
    BackupJobPhase.EXPIRED -> R.string.backup_job_expired
    else -> when (state.kind) {
        BackupJobKind.EXPORT, BackupJobKind.EXPORT_LEGACY -> R.string.copy_backup_ui_couldn_t_save_the
        BackupJobKind.READ_BACKUP -> R.string.copy_backup_ui_wrong_password_or_damaged
        BackupJobKind.READ_CSV -> R.string.copy_backup_ui_the_csv_is_damaged
        BackupJobKind.RECOVER -> R.string.copy_backup_ui_couldn_t_finish_restoring
        else -> R.string.copy_backup_ui_restore_interrupted_please_retry
    }
}
