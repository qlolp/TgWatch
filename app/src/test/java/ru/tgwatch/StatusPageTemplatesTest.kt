package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPageTemplatesTest {
    @Test fun everyPresetUsesHttpsWithoutCredentialsAndAStringCondition() {
        assertTrue(StatusPageTemplates.all.isNotEmpty())
        for (template in StatusPageTemplates.all) {
            assertTrue(template.url, template.url.startsWith("https://"))
            assertTrue(template.url, '@' !in template.url && '?' !in template.url && '#' !in template.url)
            val condition = template.condition
            assertTrue(condition.jsonField.orEmpty().isNotEmpty())
            assertTrue(condition.expectedValue.orEmpty().isNotEmpty())
            assertEquals(listOf(condition.jsonField), listOf(condition.pathSegments().joinToString(".")))
        }
    }

    @Test fun labelsAreUniqueTextKeysWithoutArguments() {
        val keys = StatusPageTemplates.all.map { it.labelKey }
        assertEquals(keys.toSet().size, keys.size)
        for (key in keys) assertEquals(0, textOf(key).args.size)
    }

    @Test fun statuspageIndicatorPathIsBounded() {
        for (template in StatusPageTemplates.all) {
            val path = template.condition.pathSegments()
            assertTrue(path.size <= 3)
            assertTrue(path.all { it.isNotEmpty() && it.length <= 64 })
        }
    }
}
