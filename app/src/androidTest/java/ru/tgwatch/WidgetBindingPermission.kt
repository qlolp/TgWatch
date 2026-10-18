package ru.tgwatch

import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.UserManager

/** Disposable emulator fixture: shell identity adoption is only available from API29. */
internal class WidgetBindingPermission private constructor(private val release: () -> Unit) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            release()
        }
    }

    companion object {
        fun acquire(ctx: Context, automation: UiAutomation): WidgetBindingPermission {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                automation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                return WidgetBindingPermission { automation.dropShellPermissionIdentity() }
            }
            // appwidget grantbind/revokebind are supported on API26/27/28. Each fixture revokes it.
            require(ctx.packageName == "ru.tgwatch")
            // Android8's CLI kills the command for --user current; an explicit numeric user works.
            check(ctx.getSystemService(UserManager::class.java).isSystemUser) {
                "Legacy widget fixtures require the disposable emulator's owner user"
            }
            command(automation, "appwidget grantbind --package ru.tgwatch --user 0")
            return WidgetBindingPermission {
                command(automation, "appwidget revokebind --package ru.tgwatch --user 0")
            }
        }

        private fun command(automation: UiAutomation, value: String) {
            val result = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(value)).use {
                it.bufferedReader().readText()
            }
            check(result.isBlank()) { "Widget binding permission command did not succeed" }
        }
    }
}
