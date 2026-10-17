// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.presets

import java.io.File

/**
 * The name a preset is shown by. Usually its file name; but some packs number their presets
 * and let the folder say what they are ("Geometric/Wire Trace/153.milk"), and a number alone
 * says nothing, so such a preset takes its folder's name too: "Wire Trace 153".
 */
object PresetTitle {
    fun of(path: String): String {
        val file = File(path)
        val name = file.nameWithoutExtension
        if (name.any(Char::isLetter)) return name
        val folder = file.parentFile?.name?.takeIf { it.isNotEmpty() } ?: return name
        return "$folder $name"
    }

    /**
     * The line under a preset's title: the folders it sits in inside [pack], without the one
     * a numbered preset's title already names. Null when there is nothing more to say.
     */
    fun placeOf(
        path: String,
        pack: InstalledPack?,
    ): String? {
        val folder = pack?.folderOf(path) ?: return null
        val numbered = File(path).nameWithoutExtension.none(Char::isLetter)
        return if (numbered) folder.substringBeforeLast(" / ", "").takeIf { it.isNotEmpty() } else folder
    }

    /**
     * Orders titles as a person reads them: by letters regardless of case, and runs of digits
     * by their value, so "Wire Trace 2" comes before "Wire Trace 10".
     */
    val ORDER: Comparator<String> =
        Comparator { a, b ->
            val left = CHUNK.findAll(a).map { it.value }.toList()
            val right = CHUNK.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]
                val y = right[i]
                val compared =
                    if (x[0].isDigit() && y[0].isDigit()) {
                        compareValuesBy(x, y, { it.trimStart('0').length }, { it.trimStart('0') })
                    } else {
                        x.compareTo(y, ignoreCase = true)
                    }
                if (compared != 0) return@Comparator compared
            }
            left.size - right.size
        }

    private val CHUNK = Regex("""\d+|\D+""")
}
