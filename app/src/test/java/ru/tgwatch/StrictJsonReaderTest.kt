package ru.tgwatch

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class StrictJsonReaderTest {
    private fun reader(text: String) = StrictJsonReader(StringReader(text))
    @Test fun readsTypedValuesWithoutAJsonTree() {
        val parsed = mutableListOf<Any>()
        val reader = reader("{\"rows\":[123, true, null, \"ok\"],\"extra\":{\"x\":[1.5e2]}}")
        reader.objectFields { key ->
            if (key == "rows") reader.arrayValues { index -> when (index) {
                0 -> parsed.add(reader.longValue())
                1 -> parsed.add(reader.booleanValue())
                2 -> { reader.nullValue(); parsed.add("null") }
                else -> parsed.add(reader.string())
            } } else reader.skipValue()
        }
        reader.finish()
        assertEquals(listOf(123L, true, "null", "ok"), parsed)
    }
    @Test fun rejectsDuplicateKeysTrailingCommasRelaxedSyntaxAndTrailingData() {
        for (text in listOf("{\"a\":1,\"a\":2}", "[1,]", "{\"a\":01}", "{a:1}",
            "{'a':1}", "true false", "\"\\uD800\"", "\"\\uDC00\"", "{\"a\":NaN}")) {
            assertThrows(text, IllegalArgumentException::class.java) {
                reader(text).apply { skipValue(); finish() }
            }
        }
    }
    @Test fun depthTenIsBoundedAndElevenRejected() {
        reader("[".repeat(10) + "null" + "]".repeat(10)).apply { skipValue(); finish() }
        assertThrows(IllegalArgumentException::class.java) {
            reader("[".repeat(11) + "null" + "]".repeat(11)).skipValue()
        }
    }
    @Test fun integerFieldsRejectDecimalExponentOverflowAndCoercion() {
        for (text in listOf("1.0", "1e0", "9223372036854775808", "\"1\"", "true")) {
            assertThrows(text, IllegalArgumentException::class.java) { reader(text).longValue() }
        }
        assertEquals(Long.MIN_VALUE, reader(Long.MIN_VALUE.toString()).longValue())
    }
    @Test fun stringsAreBoundedAndEscapesValidated() {
        assertEquals("a\n😀", reader("\"a\\n\\ud83d\\ude00\"").string())
        assertThrows(IllegalArgumentException::class.java) { reader("\"abcd\"").string(3) }
        assertThrows(IllegalArgumentException::class.java) { reader("\"\\x\"").string() }
    }
}
