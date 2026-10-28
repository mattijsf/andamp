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
 * Every text a listener meets around modern skins, in each language Andamp ships: the switch and
 * its notes on Preferences > Player, the offer, the notices, the skin menu title, Color Themes, Skin
 * Settings and the row suffix. Another translation may be incomplete (see `TranslationsTest`), but a gap here
 * would show English in the middle of a translated screen about modern skins.
 */
class ModernSkinTranslationsTest {
    private val english: Map<String, String> = stringsIn("values")

    /** The English keys of modern-skin text, found by name so that a new one is covered too. */
    private val modernKeys: List<String> = english.keys.filter { MODERN.matches(it) }

    @Test
    fun `the modern-skin strings are found`() {
        // every other test would pass over nothing
        assertTrue("modern-skin strings: $modernKeys", modernKeys.size >= MODERN_AT_LEAST)
    }

    @Test
    fun `every shipped language has every modern-skin string`() {
        val missing =
            LANGUAGES.flatMap { language ->
                val keys = stringsIn(language).keys
                modernKeys.filter { it !in keys }.map { "$language: $it" }
            }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `the offer names the page with the switch as each language calls it`() {
        val wrong =
            (listOf("values") + LANGUAGES).mapNotNull { dir ->
                val strings = stringsIn(dir)
                val path = "${strings.getValue("prefs_title")} > ${strings.getValue("prefs_page_player")}"
                "$dir: the offer does not name \"$path\"".takeUnless { path in strings.getValue("skin_modern_offer_body") }
            }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `the Webamp notice credits the script calls in every language`() {
        // std.mi is a file name and reads the same in every language
        val stale = LANGUAGES.filter { "std.mi" !in stringsIn(it).getValue("prefs_notice_webamp_what") }
        assertEquals(emptyList<String>(), stale)
    }

    /** The `<string>` entries of every strings file in `res/[dir]`, by key. */
    private fun stringsIn(dir: String): Map<String, String> =
        resDir()
            .resolve(dir)
            .listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }
            .orEmpty()
            .flatMap { file ->
                val root =
                    DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(file)
                        .documentElement
                (0 until root.childNodes.length)
                    .map(root.childNodes::item)
                    .filter { it.nodeType == Node.ELEMENT_NODE && it.nodeName == "string" }
                    .map { (it as Element).getAttribute("name") to it.textContent }
            }.toMap()

    /** `app/src/main/res`, wherever the test was run from. */
    private fun resDir(): File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { it.resolve("app/src/main/res") }
            .firstOrNull { it.isDirectory }
            ?: error("no app/src/main/res above ${File(".").absolutePath}")

    private companion object {
        /** The translations Andamp ships. A language added later may start incomplete. */
        val LANGUAGES = listOf("values-de", "values-fr", "values-nl", "values-zh-rCN")

        /**
         * Keys of modern-skin text: the prompts and the row suffix, the skin menu title, Color Themes and
         * Skin Settings, and the Skins section on Preferences > Player with its lines in the Player row's summary.
         */
        val MODERN =
            Regex(
                "skin_modern_.*|skin_row_modern|menu_skin_menu|menu_color_themes|menu_skin_settings.*|" +
                    "prefs_modern_.*|prefs_sharp_.*|" +
                    "prefs_player_skins_.*|prefs_player_summary_.*(modern|sharp|_skins).*",
            )

        /**
         * Low enough to hold when a modern-skin string is removed, high enough to catch a pattern
         * that finds few or none of them.
         */
        const val MODERN_AT_LEAST = 20
    }
}
