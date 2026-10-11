package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Validate the shipped Android strings, including separate feature resource files. */
class ResourceTranslationTest {
    private val resources = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val placeholder = Regex("%(?:[1-9][0-9]*\\$)?[-#+ 0,(]*[0-9]*(?:\\.[0-9]+)?[a-zA-Z]")

    private fun strings(folder: String): Map<String, String> {
        val values = mutableMapOf<String, String>()
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        for (file in File(resources, folder).listFiles().orEmpty().filter { it.extension == "xml" }) {
            val elements = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (i in 0 until elements.length) {
                val node = elements.item(i)
                val name = node.attributes.getNamedItem("name").nodeValue
                assertTrue("Duplicate $name in $folder", !values.containsKey(name))
                values[name] = node.textContent
            }
        }
        return values
    }

    @Test fun englishAndRussianCoverSameStringsWithCompatibleFormatArguments() {
        val english = strings("values")
        val russian = strings("values-ru")
        assertTrue("Expected real application strings", english.size >= 70)
        assertEquals("Each language must cover every shipped string", english.keys, russian.keys)
        for ((name, text) in english) {
            fun arguments(value: String) = placeholder.findAll(value.replace("%%", "")).map { it.value }.sorted().toList()
            assertEquals("Formatting arguments for $name", arguments(text), arguments(russian.getValue(name)))
        }
    }
}
