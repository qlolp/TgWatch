package ru.tgwatch

/** Public code metadata only: never copy arbitrary worker names or source paths into diagnostics. */
internal object CrashMetadata {
    private const val MAX_TYPE = 160
    private const val MAX_FRAME = 200
    private const val NATIVE_LINE = -2
    private const val REDACTED = "redacted"
    private val identifier = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    private val threads = setOf("main", "tg-check", "tg-webhook", "tg-webhook-deadline", "tg-webhook-cancel",
        "tg-target-settings", "tg-target-wait", "tg-summary", "tg-summary-deadline", "tg-status-card",
        "tg-history", "tg-recovery", "tg-extra-results", "tg-automation-state", "tg-state-deadline",
        "tg-component-deadline", "tg-backup", "tg-csv-export", "tg-diagnostics")
    private val namespaces = listOf("ru.tgwatch.", "java.", "javax.", "android.", "androidx.",
        "com.android.", "kotlin.", "kotlinx.", "org.json.")

    fun thread(raw: String): String = raw.takeIf { it in threads } ?: "other"

    fun type(raw: String): String = knownClass(raw)?.name ?: "other"

    private fun knownClass(raw: String): Class<*>? {
        val valid = raw.length <= MAX_TYPE && namespaces.any(raw::startsWith) &&
            raw.split('.').all(identifier::matches)
        if (!valid) return null
        return try {
            Class.forName(raw, false, CrashMetadata::class.java.classLoader)
        } catch (_: ClassNotFoundException) { null
        } catch (_: LinkageError) { null
        } catch (_: SecurityException) { null }
    }

    private fun knownMethod(owner: Class<*>, method: String): Boolean = try {
        method == "<init>" || method == "<clinit>" || owner.declaredMethods.any { it.name == method }
    } catch (_: LinkageError) { false
    } catch (_: SecurityException) { false }

    fun frame(element: StackTraceElement): String {
        val owner = knownClass(element.className)
        val method = element.methodName
        val validMethod = method.length <= MAX_TYPE &&
            (identifier.matches(method) || method == "<init>" || method == "<clinit>")
        if (owner == null || !validMethod) return REDACTED
        val safeMethod = if (knownMethod(owner, method)) method else REDACTED
        val source = owner.name.substringAfterLast('.').substringBefore('$').removeSuffix("Kt")
        val file = element.fileName?.takeIf { it == "$source.kt" || it == "$source.java" }
            ?: element.fileName?.let { REDACTED }
        val rendered = StackTraceElement(owner.name, safeMethod, file, element.lineNumber).toString()
        return rendered.takeIf { it.length <= MAX_FRAME } ?: REDACTED
    }

    /** Old on-device records receive the same policy as new exceptions, before export or rotation. */
    fun frame(raw: String): String {
        if (raw.length > MAX_FRAME || !raw.endsWith(')')) return REDACTED
        val head = raw.substringBefore('(')
        val owner = head.substringBeforeLast('.', "")
        val method = head.substringAfterLast('.')
        val location = raw.substringAfter('(', "").dropLast(1)
        val line = location.substringAfterLast(':', "").toIntOrNull()
        val native = location == "Native Method"
        val file = if (native || location == "Unknown Source") null else location.substringBeforeLast(':', location)
        return frame(StackTraceElement(owner, method, file, line ?: if (native) NATIVE_LINE else -1))
    }
}
