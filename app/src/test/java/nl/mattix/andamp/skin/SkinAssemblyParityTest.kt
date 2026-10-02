// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A skin rebuilt from its template is the skin loaded from its file: the same
 * pixels, the same fallback decisions, the same parsed text, and a font
 * scanned out of GEN.BMP.
 *
 * [SkinTemplateBitmapsTest] covers the sheets; this covers everything after
 * them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SkinAssemblyParityTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun pixels(image: ImageBitmap): IntArray {
        val bitmap = image.asAndroidBitmap()
        return IntArray(
            bitmap.width * bitmap.height,
        ).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    }

    private fun check(bundled: BundledSkin) {
        val theme = if (bundled.live!!.dark) "dark" else "light"
        val scheme = SkinDist.baselineScheme(theme)
        val fromFile = SkinLoader.loadBundled(app, bundled)

        val live = LiveSkins { _, _ -> scheme }.build(app, bundled)!!

        Sheet.entries.forEach { sheet ->
            val expected = fromFile.getOrNull(sheet)
            val got = live.getOrNull(sheet)
            if (expected == null) {
                assertNull("$theme ${sheet.baseName} is absent, as in the file", got)
            } else {
                assertNotNull("$theme ${sheet.baseName} is present, as in the file", got)
                assertArrayEquals("$theme ${sheet.baseName} has the file's pixels", pixels(expected), pixels(got!!))
            }
        }
        assertEquals(fromFile.name, live.name)
        assertEquals(fromFile.pledit, live.pledit)
        assertEquals(fromFile.visColors, live.visColors)
        assertEquals(fromFile.regions, live.regions)
        assertEquals(fromFile.balanceUsesVolume, live.balanceUsesVolume)
        assertEquals(fromFile.hasNumsEx, live.hasNumsEx)
        assertEquals(fromFile.hasGenWindow, live.hasGenWindow)
        assertEquals(fromFile.ownsGenArt, live.ownsGenArt)
        assertNotNull("the title font is scanned from GEN.BMP", live.genTitleFont)
        assertNull("a template carries no readme", live.readme)
        assertNull("a skin loaded from a file has no live scheme", fromFile.liveScheme)
        assertSame("a live skin carries the scheme it was bound to", scheme, live.liveScheme)
    }

    @Test
    fun `the dark template builds the skin the dark file loads`() = check(BundledSkins.BASE)

    @Test
    fun `the light template builds the skin the light file loads`() = check(BundledSkins.LIGHT)
}
