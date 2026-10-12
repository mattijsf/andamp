// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The English strings, read from `app/src/main/res/values/strings*.xml` on disk, for tests of the
 * state layer that run without an Android runtime. A resource id is mapped to its name through
 * the generated `R.string` and `R.plurals` classes, which are plain Java.
 */
object EnglishWords : Words {
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()

    private val stringNames: Map<Int, String> = namesOf(R.string::class.java)
    private val pluralNames: Map<Int, String> = namesOf(R.plurals::class.java)

    init {
        val values = valuesDir()
        values
            .listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }
            .orEmpty()
            .sorted()
            .forEach(::read)
    }

    override fun get(
        id: Int,
        vararg args: Any,
    ): String {
        val name = stringNames[id] ?: error("no string resource with id $id")
        val text = strings[name] ?: error("no English text for string $name")
        return if (args.isEmpty()) text else String.format(Locale.US, text, *args)
    }

    override fun plural(
        id: Int,
        count: Int,
        vararg args: Any,
    ): String {
        val name = pluralNames[id] ?: error("no plurals resource with id $id")
        val forms = plurals[name] ?: error("no English forms for plurals $name")
        // English: one, and everything else
        val text = (if (count == 1) forms["one"] else null) ?: forms["other"] ?: error("plurals $name has no other form")
        return String.format(Locale.US, text, count, *args)
    }

    private fun namesOf(klass: Class<*>): Map<Int, String> =
        klass.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }

    private fun read(file: File) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            val element = node as org.w3c.dom.Element
            val name = element.getAttribute("name")
            when (element.tagName) {
                "string" -> {
                    strings[name] = unescape(element.textContent)
                }

                "plurals" -> {
                    val items = element.getElementsByTagName("item")
                    val forms = HashMap<String, String>()
                    for (j in 0 until items.length) {
                        val item = items.item(j) as org.w3c.dom.Element
                        forms[item.getAttribute("quantity")] = unescape(item.textContent)
                    }
                    plurals[name] = forms
                }
            }
        }
    }

    /**
     * What aapt does to a resource's text: a value wholly wrapped in double quotes loses them, and
     * then the backslash escapes are read.
     */
    private fun unescape(raw: String): String {
        val wrapped = raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"") && !raw.endsWith("\\\"")
        val text = if (wrapped) raw.substring(1, raw.length - 1) else raw
        return text
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\@", "@")
            .replace("\\?", "?")
    }

    /** `app/src/main/res/values`, wherever the test was run from. */
    private fun valuesDir(): File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { it.resolve("app/src/main/res/values") }
            .firstOrNull { it.isDirectory }
            ?: error("no app/src/main/res/values above ${File(".").absolutePath}")
}
