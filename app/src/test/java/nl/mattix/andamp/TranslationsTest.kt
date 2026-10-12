// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What a translation has to get right for the app to run with it: only keys the English has, the
 * same placeholders, and a plural form for every count. See docs/translating.md.
 *
 * A translation may be incomplete: a key it leaves out is shown in English.
 */
class TranslationsTest {
    /** One `<string>`, or one `<item>` of a `<plurals>`. */
    private data class Entry(
        val key: String,
        /** The plural form, or null for a `<string>`. */
        val quantity: String?,
        val text: String,
    )

    private val english: List<Entry> = entriesIn(resDir().resolve("values"))

    private val translations: Map<String, List<Entry>> =
        resDir()
            .listFiles { dir -> dir.isDirectory && dir.name.startsWith("values-") }
            .orEmpty()
            .associate { it.name to entriesIn(it) }
            .filterValues { it.isNotEmpty() }

    @Test
    fun `the English strings are found`() {
        // every other test would pass over nothing
        assertTrue("strings in res/values: ${english.size}", english.size >= ENGLISH_AT_LEAST)
    }

    @Test
    fun `a translation has no key the English lacks`() {
        val known = english.map { it.key }.toSet()
        val strays =
            translations.flatMap { (language, entries) ->
                entries.filter { it.key !in known }.map { "$language: ${it.key}" }
            }
        assertEquals("keys with no English string, which nothing reads", emptyList<String>(), strays.distinct())
    }

    @Test
    fun `a translated string keeps the placeholders of the English one`() {
        val wanted = english.filter { it.quantity == null }.associate { it.key to placeholdersOf(it.text) }
        val wrong =
            translations.flatMap { (language, entries) ->
                entries
                    .filter { it.quantity == null && it.key in wanted }
                    .filter { placeholdersOf(it.text) != wanted.getValue(it.key) }
                    .map { "$language: ${it.key} has ${placeholdersOf(it.text)}, the English ${wanted.getValue(it.key)}" }
            }
        assertEquals("a placeholder dropped, added or of another kind fails when the string is shown", emptyList<String>(), wrong)
    }

    @Test
    fun `a translated plural has a form for every count, with the placeholders of the English one`() {
        val wanted = english.filter { it.quantity == OTHER }.associate { it.key to placeholdersOf(it.text) }
        val wrong =
            translations.flatMap { (language, entries) ->
                val plurals = entries.filter { it.quantity != null && it.key in wanted }.groupBy { it.key }
                plurals.flatMap { (key, forms) ->
                    val english = wanted.getValue(key)
                    val other = forms.firstOrNull { it.quantity == OTHER }
                    listOfNotNull(
                        "$language: $key has no \"other\" form, which every language needs".takeIf { other == null },
                        "$language: $key \"other\" has ${other?.let { placeholdersOf(it.text) }}, the English $english"
                            .takeIf { other != null && placeholdersOf(other.text) != english },
                    ) +
                        // a form for one may spell the number out, so it may leave placeholders out
                        forms
                            .filter { !english.containsAll(placeholdersOf(it.text)) }
                            .map { "$language: $key \"${it.quantity}\" has a placeholder the English lacks" }
                }
            }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `the English strings number their placeholders`() {
        // an unnumbered one cannot change places in a language that orders its words differently
        val unnumbered =
            english
                .filter { entry ->
                    placeholdersIn(entry.text).any { it.groupValues[1].isEmpty() }
                }.map { it.key }
        assertEquals(emptyList<String>(), unnumbered.distinct())
    }

    /** Each placeholder as its number and what it formats, sorted: `%2$d` is "2d". Flags and widths may differ. */
    private fun placeholdersOf(text: String): List<String> =
        placeholdersIn(text)
            .map { it.groupValues[1].removeSuffix("$") + it.groupValues[2] }
            .sorted()
            .toList()

    /** The placeholders of [text]. A `%%` is a percent sign and is taken out first. */
    private fun placeholdersIn(text: String): Sequence<MatchResult> = PLACEHOLDER.findAll(text.replace("%%", ""))

    private fun entriesIn(values: File): List<Entry> =
        values
            .listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }
            .orEmpty()
            .sorted()
            .flatMap(::entriesOf)

    private fun entriesOf(file: File): List<Entry> {
        val root =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .documentElement
        return root.elements().flatMap { element ->
            val key = element.getAttribute("name")
            when (element.tagName) {
                "string" -> listOf(Entry(key, null, element.textContent))
                "plurals" -> element.elements().map { Entry(key, it.getAttribute("quantity"), it.textContent) }
                else -> emptyList()
            }
        }
    }

    private fun Element.elements(): List<Element> =
        (0 until childNodes.length).map(childNodes::item).filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }

    /** `app/src/main/res`, wherever the test was run from. */
    private fun resDir(): File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { it.resolve("app/src/main/res") }
            .firstOrNull { it.isDirectory }
            ?: error("no app/src/main/res above ${File(".").absolutePath}")

    private companion object {
        /** Low enough not to need changing with every string, high enough to catch a scan that reads nothing. */
        const val ENGLISH_AT_LEAST = 400

        const val OTHER = "other"

        /** A format placeholder: an optional `1$`, flags and a width, then what it formats. */
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])""")
    }
}
