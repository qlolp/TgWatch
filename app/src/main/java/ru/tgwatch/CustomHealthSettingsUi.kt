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
import ru.tgwatch.probe.HttpProbeCondition

internal data class HealthCriteriaEdit(val replace: Boolean, val condition: HttpProbeCondition?)

/** Ephemeral editor; saved credentials are never read into the view hierarchy. */
internal class CustomHealthSettingsUi(activity: Activity, content: LinearLayout) {
    private val code = input(content, R.id.targetHealthCodeInput, R.string.target_health_code, CODE_CHARS,
        InputType.TYPE_CLASS_NUMBER)
    private val field = input(content, R.id.targetHealthFieldInput, R.string.target_health_field, FIELD_CHARS)
    private val value = input(content, R.id.targetHealthValueInput, R.string.target_health_value, VALUE_CHARS,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    private val clear = CheckBox(activity).apply {
        id = R.id.targetHealthClear
        setText(R.string.target_health_clear)
        isSaveEnabled = false
        content.addView(this)
    }
    init { content.addView(TextView(activity).apply { setText(R.string.target_health_help) }) }

    fun selection(): HealthCriteriaEdit? {
        val rawCode = code.text.toString()
        val name = field.text.toString()
        val expected = value.text.toString()
        val status = rawCode.takeIf { it.isNotEmpty() }?.toIntOrNull()
        return when {
            clear.isChecked -> HealthCriteriaEdit(true, null)
            rawCode.isEmpty() && name.isEmpty() && expected.isEmpty() -> HealthCriteriaEdit(false, null)
            rawCode.isNotEmpty() && (status == null || status !in FIRST_CODE..LAST_CODE) -> {
                code.error = code.context.getString(R.string.target_health_invalid_code)
                null
            }
            name.isEmpty() && expected.isNotEmpty() -> {
                field.error = field.context.getString(R.string.target_health_invalid_field)
                null
            }
            else -> HealthCriteriaEdit(true, HttpProbeCondition(status, name.takeIf { it.isNotEmpty() },
                expected.takeIf { name.isNotEmpty() }))
        }
    }
    fun clear() { listOf(code, field, value).forEach { it.text.clear() } }

    private companion object {
        const val CODE_CHARS = 3
        const val FIELD_CHARS = 64
        const val VALUE_CHARS = 256
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
