// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class PleditStyle(
    val normal: Color,
    val current: Color,
    val normalBg: Color,
    val selectedBg: Color,
    val fontName: String,
) {
    companion object {
        // Winamp defaults (webamp baseSkin.json playlistStyle)
        val DEFAULT =
            PleditStyle(
                normal = Color(0xFF00FF00),
                current = Color(0xFFFFFFFF),
                normalBg = Color(0xFF000000),
                selectedBg = Color(0xFF0000FF),
                fontName = "Arial",
            )
    }
}

/**
 * Parses PLEDIT.TXT — an INI file with a [Text] section. Winamp quirks
 * (mirrored from webamp's parseIni/getPlaylistStyle): sections and keys are
 * case-insensitive, `#` on colors is optional, over-long colors are truncated
 * to #RRGGBB, `;` starts a comment, quotes around values are stripped.
 */
object PleditTxt {
    fun parse(text: String?): PleditStyle {
        if (text == null) return PleditStyle.DEFAULT
        val values = parseIniSection(text, "text")
        return PleditStyle(
            normal = parseColor(values["normal"]) ?: PleditStyle.DEFAULT.normal,
            current = parseColor(values["current"]) ?: PleditStyle.DEFAULT.current,
            normalBg = parseColor(values["normalbg"]) ?: PleditStyle.DEFAULT.normalBg,
            selectedBg = parseColor(values["selectedbg"]) ?: PleditStyle.DEFAULT.selectedBg,
            fontName = values["font"] ?: PleditStyle.DEFAULT.fontName,
        )
    }

    private fun parseIniSection(
        text: String,
        wantedSection: String,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var section = ""
        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore(';').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0 || section != wantedSection) continue
            val key = line.substring(0, eq).trim().lowercase()
            var value = line.substring(eq + 1).trim()
            // Winamp ignores anything after a second '='
            value =
                value
                    .substringBefore('=')
                    .trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
            result[key] = value
        }
        return result
    }

    private fun parseColor(value: String?): Color? {
        if (value == null) return null
        var hex = value.removePrefix("#").trim()
        if (hex.length > 6) hex = hex.take(6)
        if (hex.length != 6) return null
        val rgb = hex.toIntOrNull(16) ?: return null
        return Color(0xFF000000.toInt() or rgb)
    }
}
