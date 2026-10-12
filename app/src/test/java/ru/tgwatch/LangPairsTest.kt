package ru.tgwatch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Text built in code is bilingual by construction: Lang.t(russian, english).
 * A call with both halves in the same alphabet silently ships a mixed-language UI.
 */
class LangPairsTest {
    private val cyrillic = Regex("[А-Яа-яЁё]")

    private fun sources(): List<File> {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Split the arguments of a Lang.t( call at the top nesting level. */
    private fun arguments(text: String, from: Int): List<String>? {
        var depth = 0
        var quote = false
        var escape = false
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var index = from
        while (index < text.length) {
            val c = text[index]
            when {
                escape -> { current.append(c); escape = false }
                c == '\\' && quote -> { current.append(c); escape = true }
                c == '"' -> { quote = !quote; current.append(c) }
                !quote && c == '(' -> { depth++; current.append(c) }
                !quote && c == ')' -> {
                    if (depth == 0) {
                        parts += current.toString()
                        return parts
                    }
                    depth--; current.append(c)
                }
                !quote && c == ',' && depth == 0 -> { parts += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
            index++
        }
        return null
    }

    @Test fun everyLangCallHasOneRussianAndOneEnglishHalf() {
        val bad = mutableListOf<String>()
        var calls = 0
        for (file in sources()) {
            val text = file.readText(Charsets.UTF_8)
            var at = text.indexOf("Lang.t(")
            while (at >= 0) {
                val args = arguments(text, at + "Lang.t(".length)
                if (args != null && args.size == 2) {
                    calls++
                    val russian = cyrillic.containsMatchIn(args[0])
                    val english = cyrillic.containsMatchIn(args[1])
                    if (russian == english) {
                        bad += "${file.name}: ${args.joinToString(" | ").trim()}"
                    }
                }
                at = text.indexOf("Lang.t(", at + 1)
            }
        }
        assertTrue("Expected bilingual Lang.t calls, found $calls", calls > 50)
        assertTrue("Both halves of Lang.t must differ in language:\n${bad.joinToString("\n")}", bad.isEmpty())
    }
}
