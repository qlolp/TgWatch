package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Secure dialog plumbing shared by the optional adapters; inputs never enter saved view state. */
private fun Activity.secureDialog(title: Int, message: Int, fields: List<EditText>,
    neutral: Pair<Int, () -> Unit>?, save: () -> Unit) {
    val frame = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (DIALOG_PADDING_DP * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        fields.forEach { addView(it, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
    }
    val builder = AlertDialog.Builder(this).setTitle(title).setMessage(message).setView(frame)
        .setNegativeButton(R.string.dialog_cancel, null)
        .setPositiveButton(R.string.dialog_save) { _, _ -> save() }
    neutral?.let { (label, action) -> builder.setNeutralButton(label) { _, _ -> action() } }
    val dialog = builder.create()
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    dialog.show()
}

private fun Activity.secretField(hint: Int, maxLength: Int, password: Boolean = false, uri: Boolean = false) =
    EditText(this).apply {
        setHint(hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0) or
            (if (uri) InputType.TYPE_TEXT_VARIATION_URI else 0)
        filters = arrayOf(InputFilter.LengthFilter(maxLength))
        setSingleLine(true)
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

private const val DIALOG_PADDING_DP = 20

/** ntfy convenience: composes a webhook address for the selected recipient; delivery stays the HTTPS webhook. */
internal fun Activity.showNtfyPreset(receiverId: Int, done: () -> Unit) {
    val server = secretField(R.string.ntfy_server_hint, WebhookPolicy.MAX_URL_LENGTH, uri = true)
    val topic = secretField(R.string.ntfy_topic_hint, NtfyPreset.MAX_TOPIC_LENGTH)
    val token = secretField(R.string.ntfy_token_hint, NtfyPreset.MAX_TOKEN_LENGTH, password = true)
    secureDialog(R.string.ntfy_preset, R.string.ntfy_preset_message, listOf(server, topic, token), null) {
        val url = NtfyPreset.url(server.text.toString(), topic.text.toString(), token.text.toString())
        val events = WebhookSettings.configuration(this, receiverId).events
        val result = if (url == null) WebhookSettings.WriteResult.INVALID
            else WebhookSettings.setUrlResult(this, receiverId, url)
        if (result == WebhookSettings.WriteResult.SAVED)
            WebhookSettings.setDeliveryResult(this, receiverId, WebhookFormat.TEXT, events)
        else Toast.makeText(this, if (result == WebhookSettings.WriteResult.INVALID) R.string.ntfy_invalid
            else R.string.automation_storage_error, Toast.LENGTH_LONG).show()
        done()
    }
}

/** Private MQTT section. Broker, topic and credentials are never prefilled or displayed. */
internal class MqttSettingsUi(private val activity: Activity) {
    private lateinit var toggle: Switch
    private lateinit var status: TextView
    private lateinit var health: TextView
    private lateinit var test: Button
    private var refreshing = false

    fun attach(column: LinearLayout) {
        fun text(resource: Int) = TextView(activity).apply {
            setText(resource)
            isSaveEnabled = false
            column.addView(this)
        }
        text(R.string.mqtt_title).textSize = SECTION_TEXT_SP
        text(R.string.mqtt_description)
        toggle = Switch(activity).apply {
            id = AutomationSettingsActivity.ID_MQTT_ENABLE
            setText(R.string.mqtt_enable)
            isSaveEnabled = false
            column.addView(this)
        }
        status = text(R.string.mqtt_unset).apply { id = AutomationSettingsActivity.ID_MQTT_STATUS }
        button(column, R.string.mqtt_set, AutomationSettingsActivity.ID_MQTT_SET) { edit() }
        test = button(column, R.string.mqtt_test, AutomationSettingsActivity.ID_MQTT_TEST) { sendTest() }
        health = text(R.string.mqtt_title)
        toggle.setOnCheckedChangeListener { _, enabled ->
            if (!refreshing) {
                when (MqttSettings.setEnabledResult(activity, enabled)) {
                    WebhookSettings.WriteResult.SAVED -> Unit
                    WebhookSettings.WriteResult.INVALID ->
                        Toast.makeText(activity, R.string.mqtt_required, Toast.LENGTH_SHORT).show()
                    WebhookSettings.WriteResult.STORAGE_FAILED -> storageError()
                }
                refresh()
            }
        }
        refresh()
    }

    fun refresh() {
        val host = MqttSettings.hostLabel(activity)
        refreshing = true
        toggle.isChecked = MqttSettings.enabled(activity)
        refreshing = false
        status.text = if (host == null) activity.getString(R.string.mqtt_unset)
            else activity.getString(R.string.mqtt_saved, host)
        test.isEnabled = host != null
        refreshHealth()
    }

    fun refreshHealth() {
        val text = AndroidText.resolve(activity,
            WebhookHealthText.format(MqttNotifier.health(activity), SystemClock.elapsedRealtime()))
        if (health.text.toString() != text) health.text = text
    }

    private fun edit() {
        val host = activity.secretField(R.string.mqtt_host_hint, MqttPolicy.MAX_HOST_LENGTH, uri = true)
        val port = activity.secretField(R.string.mqtt_port_hint, PORT_DIGITS).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val topic = activity.secretField(R.string.mqtt_topic_hint, MqttPolicy.MAX_TOPIC_BYTES)
        val user = activity.secretField(R.string.mqtt_username_hint, MqttPolicy.MAX_USERNAME_BYTES)
        val password = activity.secretField(R.string.mqtt_password_hint, MqttPolicy.MAX_PASSWORD_BYTES, password = true)
        activity.secureDialog(R.string.mqtt_set, R.string.mqtt_message, listOf(host, port, topic, user, password),
            R.string.dialog_remove to {
                if (!MqttSettings.clear(activity)) storageError()
                refresh()
            }) {
            when (MqttSettings.setEndpointResult(activity, host.text.toString(), port.text.toString(),
                topic.text.toString(), user.text.toString(), password.text.toString())) {
                WebhookSettings.WriteResult.SAVED -> Unit
                WebhookSettings.WriteResult.INVALID ->
                    Toast.makeText(activity, R.string.mqtt_invalid, Toast.LENGTH_LONG).show()
                WebhookSettings.WriteResult.STORAGE_FAILED -> storageError()
            }
            refresh()
        }
    }

    private fun sendTest() {
        test.isEnabled = false
        MqttNotifier.sendTest(activity) { result ->
            if (!activity.isDestroyed) {
                Toast.makeText(activity, when (result) {
                    WebhookNotifier.TestResult.SENT -> R.string.webhook_test_sent
                    WebhookNotifier.TestResult.FAILED, WebhookNotifier.TestResult.TIMED_OUT ->
                        R.string.webhook_test_failed
                    WebhookNotifier.TestResult.BUSY -> R.string.webhook_test_busy
                    WebhookNotifier.TestResult.NOT_CONFIGURED -> R.string.mqtt_required
                }, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun storageError() =
        Toast.makeText(activity, R.string.automation_storage_error, Toast.LENGTH_SHORT).show()

    private fun button(parent: LinearLayout, label: Int, viewId: Int, action: () -> Unit) = Button(activity).apply {
        id = viewId
        setText(label)
        minHeight = (BUTTON_DP * activity.resources.displayMetrics.density).toInt()
        isSaveEnabled = false
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private companion object {
        const val BUTTON_DP = 48
        const val PORT_DIGITS = 5
        const val SECTION_TEXT_SP = 20f
    }
}
