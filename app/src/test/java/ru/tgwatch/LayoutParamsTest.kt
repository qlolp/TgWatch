package ru.tgwatch

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** A view without layout_width/height (directly or via its style) crashes the screen on inflation. */
class LayoutParamsTest {
    private val resources = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    private val exempt = setOf("merge", "include", "requestFocus", "tag")

    private fun styles(): Map<String, Pair<String?, Set<String>>> {
        val result = mutableMapOf<String, Pair<String?, Set<String>>>()
        for (file in File(resources, "values").listFiles().orEmpty().filter { it.extension == "xml" }) {
            val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("style")
            for (i in 0 until nodes.length) {
                val style = nodes.item(i) as Element
                val items = style.getElementsByTagName("item")
                val names = (0 until items.length).map { (items.item(it) as Element).getAttribute("name") }.toSet()
                val parent = style.getAttribute("parent").takeIf { it.isNotEmpty() }
                    ?: style.getAttribute("name").substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
                result[style.getAttribute("name")] = parent to names
            }
        }
        return result
    }

    @Test fun everyViewDeclaresItsSize() {
        val styles = styles()
        fun styleItems(name: String?): Set<String> {
            val local = name?.removePrefix("@style/")?.let { styles[it] } ?: return emptySet()
            return local.second + styleItems(local.first)
        }
        val layouts = File(resources, "layout").listFiles().orEmpty().filter { it.extension == "xml" }
        assertTrue(layouts.isNotEmpty())
        for (file in layouts) {
            val all = factory.newDocumentBuilder().parse(file).getElementsByTagName("*")
            for (i in 0 until all.length) {
                val view = all.item(i) as Element
                if (view.tagName in exempt) continue
                val inherited = styleItems(view.getAttribute("style").takeIf { it.isNotEmpty() })
                for (attribute in listOf("android:layout_width", "android:layout_height")) {
                    val id = view.getAttribute("android:id").ifEmpty { view.tagName }
                    assertTrue("${file.name}: $id has no $attribute",
                        view.hasAttribute(attribute) || attribute in inherited)
                }
            }
        }
    }
}
