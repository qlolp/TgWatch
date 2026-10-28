package ru.tgwatch

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import ru.tgwatch.probe.HttpJsonCondition
import ru.tgwatch.probe.HttpProbeCondition
import ru.tgwatch.probe.HttpRequestHeader

internal data class HealthCriteriaEdit(val replace: Boolean, val condition: HttpProbeCondition?)

/** Ephemeral editor; saved credentials are never read into the view hierarchy. */
internal class CustomHealthSettingsUi(activity: Activity, private val content: LinearLayout) {
    private val code = input(content, R.id.targetHealthCodeInput, R.string.target_health_code, CODE_CHARS,
        InputType.TYPE_CLASS_NUMBER)
    private val field = input(content, R.id.targetHealthFieldInput, R.string.target_health_field, FIELD_CHARS)
    private val value = input(content, R.id.targetHealthValueInput, R.string.target_health_value, VALUE_CHARS,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    private val additionalJson = (2..MAX_JSON_CONDITIONS).map { slot ->
        val fieldId = when (slot) {
            2 -> R.id.targetHealthField2Input
            3 -> R.id.targetHealthField3Input
            else -> R.id.targetHealthField4Input
        }
        val valueId = when (slot) {
            2 -> R.id.targetHealthValue2Input
            3 -> R.id.targetHealthValue3Input
            else -> R.id.targetHealthValue4Input
        }
        content.addView(TextView(activity).apply { setText(R.string.target_health_more_condition) })
        input(content, fieldId, R.string.target_health_field, FIELD_CHARS) to
            input(content, valueId, R.string.target_health_value, VALUE_CHARS,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    }
    private val bodySubstring = input(content, R.id.targetHealthBodyInput, R.string.target_health_body,
        BODY_CHARS, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    private val headers = input(content, R.id.targetHealthHeadersInput, R.string.target_health_headers,
        HEADER_TEXT_CHARS, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
    private val clear = CheckBox(activity).apply {
        id = R.id.targetHealthClear
        setText(R.string.target_health_clear)
        isSaveEnabled = false
        content.addView(this)
    }

    init { content.addView(TextView(activity).apply { setText(R.string.target_health_help) }) }

    fun selection(): HealthCriteriaEdit? {
        val rawCode = code.text.toString()
        val status = rawCode.takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (clear.isChecked) return HealthCriteriaEdit(true, null)
        if (rawCode.isNotEmpty() && (status == null || status !in FIRST_CODE..LAST_CODE)) {
            code.error = code.context.getString(R.string.target_health_invalid_code)
            return null
        }
        val fields = listOf(field to value) + additionalJson
        val json = mutableListOf<HttpJsonCondition>()
        for ((pathInput, valueInput) in fields) {
            val path = pathInput.text.toString()
            val expected = valueInput.text.toString()
            if (path.isEmpty() && expected.isEmpty()) continue
            if (path.isEmpty()) {
                pathInput.error = pathInput.context.getString(R.string.target_health_invalid_field)
                return null
            }
            try { json += HttpJsonCondition(path, expected) } catch (_: IllegalArgumentException) {
                pathInput.error = pathInput.context.getString(R.string.target_health_invalid_condition)
                return null
            }
        }
        val parsedHeaders = parseHeaders() ?: return null
        val body = bodySubstring.text.toString().takeIf { it.isNotEmpty() }
        if (rawCode.isEmpty() && json.isEmpty() && parsedHeaders.isEmpty() && body == null)
            return HealthCriteriaEdit(false, null)
        val first = json.firstOrNull()
        val remaining = if (first == null) json else json.drop(1)
        return try {
            HealthCriteriaEdit(true, HttpProbeCondition(status, first?.field, first?.expectedValue, body,
                remaining, parsedHeaders))
        } catch (_: IllegalArgumentException) {
            headers.error = headers.context.getString(R.string.target_health_invalid_headers)
            null
        }
    }

    fun clear() {
        listOf(code, field, value, bodySubstring, headers).forEach { it.text.clear() }
        additionalJson.forEach { (path, expected) -> path.text.clear(); expected.text.clear() }
        clear.isChecked = false
    }

    /** Pre-fill from a status-page preset; the user can still edit every field. */
    fun apply(condition: HttpProbeCondition) {
        clear()
        condition.expectedCode?.let { code.setText(it.toString()) }
        condition.jsonField?.let {
            field.setText(it)
            value.setText(condition.expectedValue.orEmpty())
        }
        condition.jsonConditions.zip(additionalJson).forEach { (item, inputs) ->
            inputs.first.setText(item.field)
            inputs.second.setText(item.expectedValue)
        }
        condition.bodySubstring?.let(bodySubstring::setText)
    }

    private fun parseHeaders(): List<HttpRequestHeader>? {
        val lines = headers.text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size > MAX_HEADERS) {
            headers.error = headers.context.getString(R.string.target_health_invalid_headers)
            return null
        }
        return try {
            lines.map { line ->
                val separator = line.indexOf(':')
                require(separator > 0)
                val name = line.substring(0, separator).trim()
                val rawValue = line.substring(separator + 1)
                HttpRequestHeader(name, rawValue.removePrefix(" "))
            }
        } catch (_: IllegalArgumentException) {
            headers.error = headers.context.getString(R.string.target_health_invalid_headers)
            null
        }
    }

    private companion object {
        const val CODE_CHARS = 3
        const val FIELD_CHARS = 64
        const val VALUE_CHARS = 256
        const val BODY_CHARS = 256
        const val HEADER_TEXT_CHARS = 8 * (64 + 256 + 2)
        const val MAX_JSON_CONDITIONS = 4
        const val MAX_HEADERS = 8
        const val FIRST_CODE = 200
        const val LAST_CODE = 599

        fun input(content: LinearLayout, viewId: Int, hint: Int, limit: Int,
            type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) =
            EditText(content.context).apply {
                id = viewId
                inputType = type
                setHint(hint)
                filters = arrayOf(InputFilter.LengthFilter(limit))
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                isSaveEnabled = false
                isSaveFromParentEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                content.addView(this)
            }
    }
}
