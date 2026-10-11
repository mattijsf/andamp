// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * AVSTrans: a translator a preset can switch on for all of its code.
 *
 * Transcribed from vis_avs `e_eeltrans_translate.cpp` (BSD; see NOTICE.md),
 * which is TomyLobo's AVSTrans plug-in. With an AVSTrans component in a
 * preset, every piece of code in it is rewritten before it is compiled:
 *
 * - `#define name text` and `#define name(a, b) text` are macros, replaced
 *   wherever the name appears. Every AVSTrans component's own code is put in
 *   front of each piece of code, so a macro defined there is known everywhere.
 * - Comments are removed. A `//$avstrans_...=...` comment is a setting: the
 *   output form, whether the top level is rewritten, and further replacements.
 * - Inside a function's arguments several statements may be written with
 *   semicolons, and an assignment with `=`. Depending on the form they are
 *   left as they are, written as `assign(a, b)`, folded into `exec2` and
 *   `exec3`, or joined with `+`.
 *
 * `#include` reads a file from AVS's folder on the PC. There is none here and
 * the line is dropped. Code that starts with `//$notrans` is left alone.
 */
internal class AvsTrans(
    private var mode: Mode = Mode.LINEAR,
    private var translateTopLevel: Boolean = false,
) {
    internal enum class Mode { LINEAR, ASSIGN, EXEC, PLUS }

    private enum class Match { CASE_SENSITIVE, CASE_INSENSITIVE, C_STYLE }

    private class Replacement(
        val from: String,
        val to: String,
        val match: Match,
    )

    private val replacements = mutableListOf<Replacement>()

    /** [code] rewritten, with [prefix] (the AVSTrans components' own code) read first. */
    fun translate(
        prefix: String,
        code: String,
    ): String {
        val input = StringBuilder(crlf(prefix) + NEWLINE + crlf(code))
        readSettingsFromComments(input)
        var text = input.toString()
        for (replacement in replacements) text = replace(text, replacement)
        // the original ends every statement with a semicolon, the last one of
        // a function's argument too; this evaluator takes no empty statement
        // there, so that one is dropped
        return LAST_IN_ARGUMENT.replace(transform(text, 0, translateTopLevel), "")
    }

    private fun crlf(text: String) = text.replace("\r\n", "\n").replace("\n", NEWLINE)

    private fun replace(
        input: String,
        replacement: Replacement,
    ): String =
        when (replacement.match) {
            Match.CASE_SENSITIVE -> input.replace(replacement.from, replacement.to)
            Match.CASE_INSENSITIVE -> input.replace(replacement.from, replacement.to, ignoreCase = true)
            Match.C_STYLE -> macro(input, replacement)
        }

    /** A macro with parameters: each `name(...)` with the right number of arguments becomes its text. */
    private fun macro(
        text: String,
        replacement: Replacement,
    ): String {
        val open = replacement.from.indexOf('(')
        val name = replacement.from.substring(0, open)
        val parameters =
            replacement.from
                .substring(open + 1, replacement.from.length - 1)
                .split(",")
                .map(::trimmed)
        val to = replacement.to

        // the macro's text, cut at each parameter: the text before it, and which parameter it is
        val parts = mutableListOf<String>()
        val tokens = mutableListOf<Int>()
        var lastPart = 0
        var wasName = false
        for (at in to.indices) {
            val isName = to[at] in NAME_CHARACTERS
            if (isName && !wasName) {
                val which =
                    parameters.indexOfFirst { parameter ->
                        parameter.isNotEmpty() &&
                            to.startsWith(parameter, at) &&
                            (to.getOrNull(at + parameter.length)?.let { it !in NAME_CHARACTERS } ?: true)
                    }
                if (which >= 0) {
                    parts += withoutPaste(to.substring(lastPart, at), leading = parts.isNotEmpty())
                    tokens += which
                    lastPart = at + parameters[which].length
                }
            }
            wasName = isName
        }
        var rest = to.substring(lastPart)
        if (parts.isNotEmpty() &&
            trimmed(rest).startsWith(PASTE)
        ) {
            rest = trimmed(rest.substring(rest.indexOf(PASTE) + PASTE.length))
        }

        val out = StringBuilder()
        var input = text
        var at = input.indexOf("$name(")
        while (at >= 0) {
            out.append(input, 0, at)
            val call = input.substring(at)
            val close = closingBracket(call)
            if (close < 0) {
                // a bracket that never closes: the rest is left as it is
                out.append(call)
                return out.toString()
            }
            val arguments = arguments(call.substring(name.length + 1, close))
            if (arguments.size == parameters.size) {
                parts.forEachIndexed { index, part -> out.append(part).append(arguments[tokens[index]]) }
                out.append(rest)
            } else {
                out.append(call, 0, close + 1)
            }
            input = call.substring(close + 1)
            at = input.indexOf("$name(")
        }
        return out.append(input).toString()
    }

    /** A piece of a macro's text without the `##` that pastes it to a parameter. */
    private fun withoutPaste(
        piece: String,
        leading: Boolean,
    ): String {
        var out = piece
        val bare = trimmed(piece)
        if (leading && bare.startsWith(PASTE)) out = trimmed(out.substring(out.indexOf(PASTE) + PASTE.length))
        if (bare.length > PASTE.length && bare.endsWith(PASTE)) out = trimmed(out.substring(0, out.lastIndexOf(PASTE)))
        return out
    }

    /** [text] cut at each comma that is not inside brackets. */
    private fun arguments(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var from = 0
        text.forEachIndexed { at, character ->
            when (character) {
                '(' -> {
                    depth++
                }

                ')' -> {
                    depth--
                }

                ',' -> {
                    if (depth == 0) {
                        out += text.substring(from, at)
                        from = at + 1
                    }
                }
            }
        }
        return out + text.substring(from)
    }

    /** Statements, cut at each semicolon outside brackets. Unbalanced brackets give one marked statement. */
    private fun statements(text: String): List<String> {
        val input = "$text;"
        val out = mutableListOf<String>()
        var depth = 0
        var from = 0
        input.forEachIndexed { at, character ->
            when (character) {
                '(' -> {
                    depth++
                }

                ')' -> {
                    depth--
                }

                ';' -> {
                    if (depth == 0) {
                        out += input.substring(from, at)
                        from = at + 1
                    }
                }
            }
        }
        return if (depth == 0) out else listOf("BRACKETS[$depth]->${defused(input)}<-BRACKETS")
    }

    /** Code that cannot be parsed, with what would be read as syntax taken out, as the original marks it. */
    private fun defused(text: String) =
        text
            .replace("=", "dummyequaldummy")
            .replace(";", "dummysemicolondummy")
            .replace("(", "[")
            .replace(")", "]")

    private fun transform(
        input: String,
        indent: Int,
        parse: Boolean,
    ): String {
        val linear = StringBuilder()
        val assigned = StringBuilder()
        val out = mutableListOf<String>()
        for (statement in statements(input)) {
            var item = trimmed(statement)
            if (item.isEmpty()) continue
            linear.append(item).append(";").append(NEWLINE)
            item = command(item, indent)
            val sides = item.split("=", limit = 2)
            if (sides.size == 2) {
                val (left, right) = sides
                if ("=" in right) {
                    item = "assign($left,${transform(right, indent, true)})"
                } else if (parse || "megabuf[" in left || "megabuf(" in left) {
                    item = "assign($left,$right)"
                }
            }
            assigned.append(item).append(";").append(NEWLINE)
            out += item
        }
        val plus: String
        val exec: String
        if (parse) {
            plus = out.joinToString("+" + NEWLINE + " ".repeat(indent))
            exec = folded(out)
        } else {
            plus = out.joinToString(";$NEWLINE")
            exec = plus
        }
        return when (mode) {
            Mode.LINEAR -> linear.toString()
            Mode.ASSIGN -> assigned.toString()
            Mode.EXEC -> exec
            Mode.PLUS -> plus
        }
    }

    /** Statements folded into nested `exec3` and `exec2` calls until one expression is left. */
    private fun folded(statements: List<String>): String {
        var items = statements
        while (items.size > 1) {
            val next = mutableListOf<String>()
            var at = 0
            while (at + 2 < items.size) {
                next += "exec3(${items[at]},${items[at + 1]},${items[at + 2]})"
                at += EXEC3
            }
            when (items.size - at) {
                2 -> next += "exec2(${items[at]},${items[at + 1]})"
                1 -> next += items[at]
            }
            items = next
        }
        return items.firstOrNull().orEmpty()
    }

    /** A function call with each of its arguments rewritten in turn. */
    private fun command(
        input: String,
        indent: Int,
    ): String {
        val open = input.indexOf('(')
        if (open < 0) return input
        val close = closingBracket(input)
        if (close < 0) return "BRACKETS[?]->${defused(input)}<-BRACKETS"
        val rewritten = arguments(input.substring(open + 1, close)).joinToString(",") { transform(it, indent + 1, true) }
        return input.substring(0, open) + "(" + rewritten + ")" + command(input.substring(close + 1), indent)
    }

    /** Where the first bracket opened in [input] closes, or -1. */
    private fun closingBracket(input: String): Int {
        var depth = 0
        input.forEachIndexed { at, character ->
            when (character) {
                '(' -> {
                    depth++
                }

                ')' -> {
                    depth--
                    if (depth == 0) return at
                }
            }
        }
        return -1
    }

    /** Takes the comments and the `#` lines out of [input], acting on the ones that are settings. */
    private fun readSettingsFromComments(input: StringBuilder) {
        var at = firstOf(input, 0)
        while (at >= 0) {
            when {
                input[at] == '#' -> {
                    val end = input.indexOf(NEWLINE, at)
                    if (end >= 0) {
                        input.replace(at, end + NEWLINE.length, preprocessor(input.substring(at + 1, end)))
                    } else {
                        input.replace(at, input.length, preprocessor(input.substring(at + 1)))
                    }
                }

                input.getOrNull(at + 1) == '*' -> {
                    val end = input.indexOf("*/", at)
                    input.delete(at, if (end >= 0) end + 2 else input.length)
                }

                input.getOrNull(at + 1) == '/' -> {
                    val end = input.indexOf(NEWLINE, at)
                    if (end >= 0) {
                        comment(input.substring(at + 2, end))
                        input.delete(at, end + NEWLINE.length)
                    } else {
                        comment(input.substring(at + 2))
                        input.delete(at, input.length)
                    }
                }

                // a division: stepped over
                else -> {
                    at++
                }
            }
            at = firstOf(input, at)
        }
    }

    private fun firstOf(
        input: StringBuilder,
        from: Int,
    ): Int = (from until input.length).firstOrNull { input[it] == '/' || input[it] == '#' } ?: -1

    private fun preprocessor(line: String): String {
        val words = line.split(" ", limit = DEFINE_WORDS)
        if (words.firstOrNull() == "define" && words.size == DEFINE_WORDS) {
            val match = if ('(' in words[1]) Match.C_STYLE else Match.CASE_INSENSITIVE
            replacements += Replacement(words[1], words[2], match)
        }
        return ""
    }

    private fun comment(comment: String) {
        if (!comment.startsWith("$")) return
        val split = comment.indexOf('=')
        if (split < 0) return
        val setting = comment.substring(1, split).lowercase()
        val value = comment.substring(split + 1)
        when (setting) {
            "avstrans_mode" -> {
                Mode.entries.firstOrNull { it.name.lowercase() == value }?.let { mode = it }
            }

            "avstrans_transfirst" -> {
                when (value) {
                    "0", "no", "off" -> translateTopLevel = false
                    "1", "on", "yes" -> translateTopLevel = true
                }
            }

            "avstrans_replacement" -> {
                replacement(value, Match.CASE_INSENSITIVE, trim = 0)
            }

            "avstrans_replacement_casesens" -> {
                replacement(value, Match.CASE_SENSITIVE, trim = 1)
            }

            "avstrans_replacement_cstyle" -> {
                replacement(value, Match.C_STYLE, trim = 1)
            }
        }
    }

    /** A `from->to` setting. The two stricter kinds drop the character before the arrow, as the original does. */
    private fun replacement(
        value: String,
        match: Match,
        trim: Int,
    ) {
        val arrow = value.indexOf("->")
        if (arrow < 0) return
        val to = value.substring(arrow + 2)
        val from = value.substring(0, (arrow - trim).coerceAtLeast(0))
        if (to.isEmpty() || from.isEmpty()) return
        // a macro has to be written as one: name(parameters)
        if (match == Match.C_STYLE && !('(' in from && from.endsWith(")"))) return
        replacements += Replacement(from, to, match)
    }

    private fun trimmed(text: String) = text.trim(' ', '\r', '\n')

    companion object {
        private const val NEWLINE = "\r\n"
        private val LAST_IN_ARGUMENT = Regex(""";\s*(?=[,)])""")
        private const val PASTE = "##"
        private const val EXEC3 = 3
        private const val DEFINE_WORDS = 3
        private const val NAME_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_."
        private const val NO_TRANSLATION = "//\$notrans"
        private const val INT_SIZE = 4

        /**
         * What a preset's AVSTrans components say, or null when none of them
         * is switched on: the code of all of them joined, and whether the top
         * level of each piece of code is rewritten too.
         */
        class Settings(
            val prefix: String,
            val translateTopLevel: Boolean,
        )

        /** One component's body: four flags, then its code up to a null. */
        internal fun settingsOf(bodies: List<ByteArray>): Settings? {
            var enabled = false
            var topLevel = false
            val code = StringBuilder()
            for (body in bodies) {
                val reader = BodyReader(body)
                if (reader.has(INT_SIZE)) enabled = reader.int32() != 0
                reader.int32Or(0) // logging to a file on the PC
                if (reader.has(INT_SIZE)) topLevel = reader.int32() != 0
                reader.int32Or(0) // reading settings from comments, which is always done
                code.append(reader.cString()).append(NEWLINE)
            }
            return if (enabled) Settings(code.toString(), topLevel) else null
        }

        /** The rewrite a preset with [settings] applies to each piece of its code. */
        fun translatorFor(settings: Settings): (String) -> String =
            { code ->
                if (code.startsWith(
                        NO_TRANSLATION,
                    )
                ) {
                    code
                } else {
                    AvsTrans(translateTopLevel = settings.translateTopLevel).translate(settings.prefix, code)
                }
            }
    }
}
