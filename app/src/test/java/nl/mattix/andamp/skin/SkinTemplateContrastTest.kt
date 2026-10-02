// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The declared contrast pairs hold under palettes the build never saw.
 *
 * On the phone the palette is the listener's wallpaper, so the pairs are
 * checked over hues and chromas the way `skin/verify/sweep.py` does, with the
 * runtime's own repair on: 48 schemes per theme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkinTemplateContrastTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val hues = (0 until 360 step 30).map { it.toDouble() }
    private val chromas = listOf(0.0, 16.0, 48.0, 80.0)

    /** Which pairs fail in [colors], described. */
    private fun failures(
        template: SkinTemplate,
        colors: IntArray,
    ): List<String> =
        template.contrastPairs.mapNotNull { (fg, bg, min) ->
            val ratio = Tonal.contrast(colors[template.roleNames.indexOf(fg)], colors[template.roleNames.indexOf(bg)])
            if (ratio < min) "$fg on $bg: %.2f:1, needs $min:1".format(ratio) else null
        }

    @Test
    fun `the manifest names both roles of every pair`() {
        SkinDist.THEMES.forEach { theme ->
            val template = SkinDist.template(app, theme)
            // 34 pairs are declared; a null one would have been dropped on load
            assertEquals("$theme carries every declared pair", 34, template.contrastPairs.size)
            template.contrastPairs.forEach { (fg, bg, _) ->
                assertTrue("$theme: $fg is a role", fg in template.roleNames)
                assertTrue("$theme: $bg is a role", bg in template.roleNames)
            }
        }
    }

    @Test
    fun `the shipped palettes clear every pair without repair`() {
        SkinDist.THEMES.forEach { theme ->
            val template = SkinDist.template(app, theme)
            val colors = template.colors(SkinDist.baselineScheme(theme), repair = false)
            assertEquals("$theme clears every pair", emptyList<String>(), failures(template, colors))
        }
    }

    @Test
    fun `every pair holds under wallpaper-shaped schemes with repair on`() {
        val schemes =
            SkinDist.THEMES.flatMap { theme ->
                hues.flatMap { hue -> chromas.map { chroma -> Triple(theme, hue, chroma) } }
            }
        val broken =
            schemes.flatMap { (theme, hue, chroma) ->
                val template = SkinDist.template(app, theme)
                val colors = template.colors(SyntheticSchemes.schemeAt(theme, hue, chroma), repair = true)
                failures(template, colors).map { "$theme hue $hue chroma $chroma - $it" }
            }
        assertEquals(broken.joinToString("\n"), emptyList<String>(), broken)
    }

    /**
     * Repair is a walk along the foreground's own tone axis: the background
     * is shared by other pairs and stays put, the hue stays what it was, and
     * the walk stops when the pair clears.
     */
    @Test
    fun `repair moves only the foreground, keeps its hue, and stops when it clears`() {
        val fg = 0x806040
        val bg = 0x705030
        val template = SkinTemplate.load(onePixelTemplate(fg = fg, bg = bg, min = 4.5).inputStream())

        val colors = template.colors(SkinDist.baselineScheme("dark"), repair = true)

        val bgAt = template.roleNames.indexOf("bg")
        val fgAt = template.roleNames.indexOf("fg")
        assertEquals("the background keeps its color", bg, colors[bgAt])
        val ratio = Tonal.contrast(colors[fgAt], colors[bgAt])
        assertTrue("the pair clears 4.5:1, at %.2f:1".format(ratio), ratio >= 4.5)
        // one tone back down and it would fail again: the walk stopped at the first pass
        val lab = Tonal.toLab(colors[fgAt])
        val oneBack = Tonal.fromLch(lab[0] - 1, hypot(lab[1], lab[2]), Math.toDegrees(atan2(lab[2], lab[1])))
        assertTrue("the walk stops at the first tone that clears", Tonal.contrast(oneBack, bg) < 4.5)
        val before = Tonal.toLab(fg)
        val hueBefore = Math.toDegrees(atan2(before[2], before[1]))
        val hueAfter = Math.toDegrees(atan2(lab[2], lab[1]))
        assertTrue("the hue stays within 2 degrees: $hueBefore to $hueAfter", abs(hueBefore - hueAfter) < 2.0)
    }

    /** A template of one pixel and two literal roles, with one pair between them. */
    private fun onePixelTemplate(
        fg: Int,
        bg: Int,
        min: Double,
    ): ByteArray {
        val manifest =
            """
            {"format": 1, "theme": "test", "scheme": "dark",
             "roles": [{"name": "bg", "kind": "literal", "value": $bg}, {"name": "fg", "kind": "literal", "value": $fg}],
             "role_order": ["bg", "fg"],
             "sheets": [{"name": "MAIN.BMP", "w": 1, "h": 1, "offset": 0}],
             "text": {},
             "contrast": [{"name": "the pair", "fg": "fg", "bg": "bg", "min": $min}]}
            """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(manifest.toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("sheets.idx"))
            z.write(byteArrayOf(1))
            z.closeEntry()
        }
        return out.toByteArray()
    }
}
