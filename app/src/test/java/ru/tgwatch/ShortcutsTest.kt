package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** App-icon shortcuts must reach the non-exported ShortcutActivity with the actions it handles. */
class ShortcutsTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }

    private fun elements(file: File, tag: String): List<Element> {
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun strings(folder: String): Map<String, String> =
        elements(File(main, "res/$folder/strings.xml"), "string").associate { it.getAttribute("name") to it.textContent }

    @Test fun shortcutsTargetTheShortcutActivityWithKnownActions() {
        val intents = elements(File(main, "res/xml/shortcuts.xml"), "intent")
        assertEquals(setOf(ShortcutActivity.ACTION_CHECK_NOW, ShortcutActivity.ACTION_MUTE),
            intents.map { it.getAttribute("android:action") }.toSet())
        for (intent in intents) {
            assertEquals("ru.tgwatch", intent.getAttribute("android:targetPackage"))
            assertEquals(ShortcutActivity::class.java.name, intent.getAttribute("android:targetClass"))
        }
        val activity = elements(File(main, "AndroidManifest.xml"), "activity")
            .single { it.getAttribute("android:name") == ".ShortcutActivity" }
        assertEquals("Launchers start shortcuts as the app; nothing else may", "false",
            activity.getAttribute("android:exported"))
    }

    @Test fun labelsFitWhatLaunchersShow() {
        val shortcuts = elements(File(main, "res/xml/shortcuts.xml"), "shortcut")
        for (folder in listOf("values", "values-ru")) {
            val text = strings(folder)
            for (shortcut in shortcuts) {
                val short = text.getValue(shortcut.getAttribute("android:shortcutShortLabel").removePrefix("@string/"))
                val long = text.getValue(shortcut.getAttribute("android:shortcutLongLabel").removePrefix("@string/"))
                assertTrue("$folder short label '$short'", short.length <= 10)
                assertTrue("$folder long label '$long'", long.length <= 25)
            }
        }
    }
}
