// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * Checks the committed launcher icon resources.
 *
 * An adaptive icon is authored on a 108 canvas and masked to the device's shape; only the
 * middle 66 is guaranteed to stay visible, and art outside it is cropped on some devices
 * without a warning. The foreground drawable is measured against that limit.
 */
class AppIconTest {
    @Test
    fun `the mark stays inside what a launcher promises to keep`() {
        val foreground = drawable("ic_launcher_foreground.xml").readText()
        // each path measured with its own stroke: a filled spark reaches its
        // corner, a stroked arc reaches half a stroke past its center line
        val furthest =
            PATH
                .findAll(foreground)
                .map { path ->
                    val body = path.value
                    val margin =
                        (
                            STROKE
                                .find(body)
                                ?.groupValues
                                ?.get(1)
                                ?.toFloat() ?: 0f
                        ) / 2
                    val data = PATH_DATA.find(body)?.groupValues?.get(1) ?: return@map 0f
                    (pointsOf(data).maxOfOrNull { (x, y) -> hypot(x - CENTRE, y - CENTRE) } ?: 0f) + margin
                }.maxOrNull() ?: 0f

        assertTrue(
            "the mark stays within $SAFE of the center, at $furthest",
            furthest <= SAFE,
        )
    }

    /**
     * The points a path visits.
     *
     * The commands are walked, because an arc carries its radii, rotation and flags before
     * its endpoint (`A24,24 0 1 1 x,y`) and those are not points. A command this does not
     * know fails the test.
     */
    private fun pointsOf(data: String): List<Pair<Float, Float>> {
        val tokens = TOKEN.findAll(data).map { it.value }.toList()
        val points = mutableListOf<Pair<Float, Float>>()
        var index = 0
        var command = ' '
        var x = 0f
        var y = 0f

        fun number(): Float = tokens[index++].toFloat()

        while (index < tokens.size) {
            if (tokens[index].first().isLetter()) {
                command = tokens[index].single()
                index++
                if (command == 'Z' || command == 'z') continue
            }
            when (command) {
                'M', 'L' -> {
                    x = number()
                    y = number()
                }

                'H' -> {
                    x = number()
                }

                'V' -> {
                    y = number()
                }

                'A' -> {
                    repeat(5) { number() } // two radii, a rotation and two flags
                    x = number()
                    y = number()
                }

                else -> {
                    error("the drawable uses '$command', which AppIconTest does not measure")
                }
            }
            points += x to y
        }
        return points
    }

    @Test
    fun `the icon has all three layers, including the one the system tints`() {
        val icon = repoRoot().resolve("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()

        listOf("background", "foreground", "monochrome").forEach {
            assertTrue("the adaptive icon declares a $it layer", icon.contains("<$it "))
        }
    }

    @Test
    fun `the generated drawables say they are generated`() {
        listOf("ic_launcher_background.xml", "ic_launcher_foreground.xml", "ic_launcher_monochrome.xml").forEach {
            assertTrue("$it says it is generated", drawable(it).readText().contains("Generated - do not edit"))
        }
    }

    private fun drawable(name: String) = repoRoot().resolve("app/src/main/res/drawable/$name")

    private fun repoRoot(): File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("no settings.gradle.kts above ${File(".").absolutePath}")

    private companion object {
        const val CENTRE = 54f

        /** Half of the 66 an adaptive icon may keep. */
        const val SAFE = 33f

        /** One `<path .../>`, however many attributes and children it has. */
        val PATH = Regex("""<path\b[\s\S]*?(?:/>|</path>)""")
        val PATH_DATA = Regex("""android:pathData="([^"]+)"""")
        val TOKEN = Regex("""[A-Za-z]|-?\d+\.?\d*""")
        val STROKE = Regex("""android:strokeWidth="(-?\d+\.?\d*)"""")
    }
}
