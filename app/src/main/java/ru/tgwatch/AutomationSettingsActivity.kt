package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Private settings screen; never places the token into saved view/instance state. */
class AutomationSettingsActivity : Activity() {
    companion object {
        const val ID_ENABLE = 0x00a71001
        const val ID_COPY = 0x00a71002
        const val ID_TOKEN = 0x00a71003
        const val ID_WEBHOOK_ENABLE = 0x00a71004
        const val ID_WEBHOOK_URL = 0x00a71005
        const val ID_WEBHOOK_SET = 0x00a71006
        const val ID_WEBHOOK_TEST = 0x00a71007
        private const val SECTION_TEXT_SP = 20f
        private const val SECTION_MARGIN_DP = 24
        private const val CLIP_LIFETIME_MS = 45_000L
        private const val SCREEN_PADDING_DP = 20
        private const val TEXT_PADDING_DP = 8
        private const val TOKEN_PADDING_DP = 12
        private const val SWITCH_HEIGHT_DP = 56
        private const val BUTTON_HEIGHT_DP = 48
        private const val BODY_TEXT_SP = 16f
        private const val TITLE_TEXT_SP = 24f
        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, AutomationSettingsActivity::class.java).apply {
                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private lateinit var toggle: Switch
    private lateinit var tokenView: TextView
    private lateinit var copy: Button
    private lateinit var regenerate: Button
    private lateinit var webhookToggle: Switch
    private lateinit var webhookUrlView: TextView
    private lateinit var webhookTest: Button
    private var refreshing = false

    // Native view construction and listeners are kept together to make token storage rules visible.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.update(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(SCREEN_PADDING_DP), dp(SCREEN_PADDING_DP), dp(SCREEN_PADDING_DP), dp(SCREEN_PADDING_DP))
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        fun text(resource: Int, size: Float = BODY_TEXT_SP) = TextView(this).apply {
            setText(resource)
            textSize = size
            setPadding(0, dp(TEXT_PADDING_DP), 0, dp(TEXT_PADDING_DP))
            isSaveEnabled = false
            column.addView(this)
        }
        text(R.string.automation_title, TITLE_TEXT_SP)
        text(R.string.automation_description)
        toggle = Switch(this).apply {
            id = ID_ENABLE
            setText(R.string.automation_enable)
            minHeight = dp(SWITCH_HEIGHT_DP)
            isSaveEnabled = false
            column.addView(this)
        }
        text(R.string.automation_token)
        tokenView = TextView(this).apply {
            id = ID_TOKEN
            isSaveEnabled = false
            setTextIsSelectable(false)
            setPadding(0, dp(TOKEN_PADDING_DP), 0, dp(TOKEN_PADDING_DP))
            textSize = BODY_TEXT_SP
            column.addView(this)
        }
        copy = button(column, R.string.automation_copy).apply { id = ID_COPY }
        regenerate = button(column, R.string.automation_regenerate)
        text(R.string.webhook_title, SECTION_TEXT_SP).setPadding(0, dp(SECTION_MARGIN_DP), 0, dp(TEXT_PADDING_DP))
        text(R.string.webhook_description)
        webhookToggle = Switch(this).apply {
            id = ID_WEBHOOK_ENABLE
            setText(R.string.webhook_enable)
            minHeight = dp(SWITCH_HEIGHT_DP)
            isSaveEnabled = false
            column.addView(this)
        }
        webhookUrlView = text(R.string.webhook_url_unset).apply { id = ID_WEBHOOK_URL }
        button(column, R.string.webhook_set_url).apply { id = ID_WEBHOOK_SET }.setOnClickListener { editWebhookUrl() }
        webhookTest = button(column, R.string.webhook_test).apply { id = ID_WEBHOOK_TEST }
        webhookTest.setOnClickListener { sendWebhookTest() }
        button(column, R.string.automation_done).setOnClickListener { finish() }
        val scroll = ScrollView(this).apply { addView(column); isSaveEnabled = false }
        // Android 15 uses edge-to-edge; apply fresh padding instead of accumulating insets.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        toggle.setOnCheckedChangeListener { _, enabled ->
            if (!refreshing) {
                val previous = AutomationSettings.token(this)
                if (!AutomationSettings.setEnabled(this, enabled)) storageError()
                if (!enabled && previous != null) clearOwnedClipboard(applicationContext, previous)
                refresh()
            }
        }
        webhookToggle.setOnCheckedChangeListener { _, enabled ->
            if (!refreshing) {
                if (!WebhookSettings.setEnabled(this, enabled)) {
                    val message = if (enabled) R.string.webhook_url_required else R.string.automation_storage_error
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
                WebhookNotifier.reset()
                refresh()
            }
        }
        copy.setOnClickListener { copyToken() }
        regenerate.setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.automation_regenerate_title)
                .setMessage(R.string.automation_regenerate_message)
                .setNegativeButton(R.string.automation_cancel, null)
                .setPositiveButton(R.string.automation_replace) { _, _ ->
                    val previous = AutomationSettings.token(this)
                    if (AutomationSettings.regenerate(this) == null) storageError()
                    if (previous != null) clearOwnedClipboard(applicationContext, previous)
                    refresh()
                }.show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::toggle.isInitialized) refresh()
    }

    private fun refresh() {
        val token = AutomationSettings.token(this)
        refreshing = true
        toggle.isChecked = token != null
        refreshing = false
        tokenView.text = token ?: getString(R.string.automation_disabled)
        copy.isEnabled = token != null
        regenerate.isEnabled = token != null
        val webhook = WebhookSettings.url(this)
        refreshing = true
        webhookToggle.isChecked = WebhookSettings.enabled(this)
        refreshing = false
        // Host only: the path of an ntfy topic URL is the secret.
        webhookUrlView.text = if (webhook == null) getString(R.string.webhook_url_unset)
            else getString(R.string.webhook_url_saved, HttpsUrlPolicy.hostLabel(webhook))
        webhookTest.isEnabled = webhook != null
    }

    /** The current address is never prefilled; dialogs are secure windows of their own. */
    private fun editWebhookUrl() {
        val input = EditText(this).apply {
            setHint(R.string.webhook_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(WebhookPolicy.MAX_URL_LENGTH))
            setSingleLine(true)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val frame = LinearLayout(this).apply {
            setPadding(dp(SCREEN_PADDING_DP), dp(TEXT_PADDING_DP), dp(SCREEN_PADDING_DP), 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.webhook_set_url)
            .setMessage(R.string.webhook_url_message).setView(frame)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setNeutralButton(R.string.dialog_remove) { _, _ ->
                if (!WebhookSettings.clear(this)) storageError()
                WebhookNotifier.reset()
                refresh()
            }
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                if (!WebhookSettings.setUrl(this, input.text.toString())) {
                    Toast.makeText(this, R.string.webhook_url_invalid, Toast.LENGTH_LONG).show()
                }
                WebhookNotifier.reset()
                refresh()
            }.create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun sendWebhookTest() {
        webhookTest.isEnabled = false
        WebhookNotifier.sendTest(this) { result ->
            if (!isDestroyed) {
                Toast.makeText(this, when (result) {
                    WebhookNotifier.TestResult.SENT -> R.string.webhook_test_sent
                    WebhookNotifier.TestResult.FAILED -> R.string.webhook_test_failed
                    WebhookNotifier.TestResult.BUSY -> R.string.webhook_test_busy
                    WebhookNotifier.TestResult.NOT_CONFIGURED -> R.string.webhook_url_required
                }, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun copyToken() {
        val token = AutomationSettings.token(this) ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(getString(R.string.automation_token), token)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clip.description.extras = PersistableBundle().apply {
                // Literal supports the same Android 13 sensitive flag on older releases.
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        val app = applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ clearOwnedClipboard(app, token) }, CLIP_LIFETIME_MS)
        Toast.makeText(this, R.string.automation_copied, Toast.LENGTH_SHORT).show()
    }

    private fun clearOwnedClipboard(ctx: Context, token: String) {
        try {
            val clipboard = ctx.getSystemService(ClipboardManager::class.java)
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount != 1 || clip.getItemAt(0).text?.toString() != token) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) {
            // Android may prevent background clipboard access; the system also expires clips on API 33+.
        }
    }

    private fun button(parent: LinearLayout, resource: Int): Button = Button(this).apply {
        setText(resource)
        minHeight = dp(BUTTON_HEIGHT_DP)
        isSaveEnabled = false
        parent.addView(this, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun storageError() = Toast.makeText(this, R.string.automation_storage_error, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
