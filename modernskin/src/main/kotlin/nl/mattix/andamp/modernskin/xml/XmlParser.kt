// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.xml

/** An element of a skin XML file. Names and attribute keys are lowercase; values are as written. */
class XmlNode(
    val name: String,
    val attrs: Map<String, String>,
    val children: List<XmlNode>,
) {
    operator fun get(key: String): String? = attrs[key]

    override fun toString() = "<$name ${attrs.entries.joinToString(" ") { "${it.key}=\"${it.value}\"" }}>"
}

/**
 * A forgiving XML reader for skin files.
 *
 * Skin XML is often not well formed: several roots in one included file, unquoted or repeated
 * attributes, stray `&`, and a byte-order mark. Every file is read as one list of top-level nodes.
 * Unknown entities are left as they are, an unclosed element ends at its parent's close tag, and a
 * close tag that matches nothing is dropped.
 */
object XmlParser {
    fun parse(text: String): List<XmlNode> = Reader(text.removePrefix("")).parseAll()

    private class Open(
        val name: String,
        val attrs: Map<String, String>,
    ) {
        val children = ArrayList<XmlNode>()
    }

    private class Reader(
        private val s: String,
    ) {
        private var i = 0
        private val stack = ArrayList<Open>()
        private val roots = ArrayList<XmlNode>()

        fun parseAll(): List<XmlNode> {
            while (i < s.length) {
                val lt = s.indexOf('<', i)
                if (lt < 0) break
                i = lt + 1
                when {
                    s.startsWith("!--", i) -> {
                        skipPast("-->")
                    }

                    s.startsWith("![CDATA[", i) -> {
                        skipPast("]]>")
                    }

                    s.startsWith("!", i) || s.startsWith("?", i) -> {
                        skipPast(">")
                    }

                    s.startsWith("/", i) -> {
                        i++
                        val name = readName().lowercase()
                        skipPast(">")
                        close(name)
                    }

                    else -> {
                        openTag()
                    }
                }
            }
            while (stack.isNotEmpty()) finish(stack.removeAt(stack.size - 1))
            return roots
        }

        private fun skipPast(end: String) {
            val at = s.indexOf(end, i)
            i = if (at < 0) s.length else at + end.length
        }

        private fun readName(): String {
            val start = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/' && s[i] != '=') i++
            return s.substring(start, i)
        }

        private fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun openTag() {
            val name = readName().lowercase()
            val attrs = LinkedHashMap<String, String>()
            var selfClose = false
            while (i < s.length) {
                skipSpace()
                if (i >= s.length) break
                val c = s[i]
                if (c == '>') {
                    i++
                    break
                }
                if (c == '/') {
                    selfClose = true
                    i++
                    continue
                }
                if (c == '<') break
                val key = readName().lowercase()
                if (key.isEmpty()) {
                    i++
                    continue
                }
                skipSpace()
                var value = ""
                if (i < s.length && s[i] == '=') {
                    i++
                    skipSpace()
                    value =
                        if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                            val q = s[i]
                            val end = s.indexOf(q, i + 1).let { if (it < 0) s.length else it }
                            val v = s.substring(i + 1, end)
                            i = minOf(end + 1, s.length)
                            v
                        } else {
                            val start = i
                            while (i < s.length && !s[i].isWhitespace() && s[i] != '>' &&
                                !(s[i] == '/' && s.getOrNull(i + 1) == '>')
                            ) {
                                i++
                            }
                            s.substring(start, i)
                        }
                }
                if (key !in attrs) attrs[key] = unescape(value)
            }
            val open = Open(name, attrs)
            if (selfClose) {
                finish(open)
            } else {
                stack.add(open)
            }
        }

        private fun close(name: String) {
            val at = stack.indexOfLast { it.name == name }
            if (at < 0) return
            while (stack.size > at) finish(stack.removeAt(stack.size - 1))
        }

        private fun finish(open: Open) {
            val node = XmlNode(open.name, open.attrs, open.children)
            if (stack.isEmpty()) roots.add(node) else stack.last().children.add(node)
        }

        private fun unescape(v: String): String {
            if ('&' !in v) return v
            return v
                .replace(
                    "&quot;",
                    "\"",
                ).replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
        }
    }
}
