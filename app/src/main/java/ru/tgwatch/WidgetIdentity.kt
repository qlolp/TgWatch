package ru.tgwatch

/** Public instance identity: a deleted widget's PendingIntent cannot act on its replacement. */
data class WidgetIdentity(val widgetId: Int, val targetId: TargetId, val action: String, val token: String) {
    init { require(widgetId > 0 && action in setOf("check", "mute") && Regex("[a-f0-9]{32}").matches(token)) }
    val uri: String get() = "tgwatch://widget/$widgetId/${targetId.wire}/$action?config=$token"
    companion object {
        private val pattern = Regex("tgwatch://widget/([1-9][0-9]{0,9})/([a-z]+)/([a-z]+)\\?config=([a-f0-9]{32})")
        fun parse(raw: String): WidgetIdentity? = pattern.matchEntire(raw)?.groupValues?.let {
            val fields = it.drop(1).iterator()
            val rawId = fields.next()
            val rawTarget = fields.next()
            val action = fields.next()
            val token = fields.next()
            val id = rawId.toIntOrNull()?.takeIf { value -> value > 0 }
            val target = TargetId.fromWire(rawTarget)
            if (id != null && target != null && action in setOf("check", "mute"))
                WidgetIdentity(id, target, action, token) else null
        }
    }
}
