// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

/**
 * What of each preset projectM left out, read from the lines it writes to its error stream.
 *
 * projectM draws a preset whose warp or composite shader does not compile with a plain
 * fallback shader, and skips custom waves and shapes whose code does not compile. It says so
 * only on that stream, and only with `ENABLE_DEBUG_MILKDROP_PRESET` on. Each line belongs to
 * the preset named by the "Loading preset" line before it.
 */
internal class PresetLog {
    private var loading: String? = null
    private val leftOut = HashMap<String, MutableList<String>>()

    /** Reads [lines], and answers the presets whose list of parts left out grew. */
    fun read(lines: List<String>): Set<String> {
        val grew = mutableSetOf<String>()
        for (line in lines) {
            val starting = LOADING.find(line)?.groupValues?.get(1)
            if (starting != null) {
                // a preset loaded again is read afresh
                loading = starting
                leftOut.remove(starting)
                continue
            }
            val path = loading ?: continue
            val part = partOf(line) ?: continue
            val parts = leftOut.getOrPut(path) { mutableListOf() }
            if (part !in parts) {
                parts += part
                grew += path
            }
        }
        return grew
    }

    /** What projectM said it left out of the preset at [path], in the order it said so. */
    fun leftOut(path: String): List<String> = leftOut[path].orEmpty()

    private companion object {
        val LOADING = Regex("""^\[Preset] Loading preset from file "(.*)"\.$""")
        val CUSTOM = Regex("""Could not compile custom (wave|shape) (\d+)""")

        /** The part of a preset a line says is left out, by the name the selector shows; null for any other line. */
        fun partOf(line: String): String? =
            when {
                line.startsWith("[Warp Shader] Error") -> {
                    "warp shader"
                }

                line.startsWith("[Composite Shader] Error") -> {
                    "composite shader"
                }

                else -> {
                    CUSTOM.find(line)?.let { "custom ${it.groupValues[1]} ${it.groupValues[2]}" }
                        ?: when {
                            "Could not compile per-frame" in line -> "per-frame code"
                            "Could not compile per-pixel" in line -> "per-pixel code"
                            else -> null
                        }
                }
            }
    }
}
