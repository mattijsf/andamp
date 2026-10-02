// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A template bound to the palette it shipped with is the shipped skin, pixel
 * for pixel.
 *
 * `build.py template` makes the same assertion on the Python side. Here the
 * same role image, written through the same table, has to give the same twenty
 * sheets and the same three text files. It also checks that the template and
 * the skin were built from the same source, which `build.py all` ensures.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the shipped BMPs decode through BitmapFactory
@Config(sdk = [35])
class SkinTemplateBitmapsTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun decode(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )

    private fun checkSheets(theme: String) {
        val template = SkinDist.template(app, theme)
        val shipped = SkinDist.wsz(app, theme)
        val built = template.bitmaps(template.colors(SkinDist.baselineScheme(theme)))

        assertEquals(
            "$theme names the sheets the skin ships",
            shipped.keys.filter { it.endsWith(".BMP") }.sorted(),
            built.keys.sorted(),
        )
        for ((name, bitmap) in built) {
            val expected = decode(shipped.getValue(name))
            assertEquals("$theme $name has the shipped width", expected.width, bitmap.width)
            assertEquals("$theme $name has the shipped height", expected.height, bitmap.height)
            assertArrayEquals("$theme $name has the shipped pixels", pixels(expected), pixels(bitmap))
        }
    }

    @Test
    fun `every sheet of the dark template is the shipped BMP`() = checkSheets("dark")

    @Test
    fun `every sheet of the light template is the shipped BMP`() = checkSheets("light")

    @Test
    fun `the text files are the shipped ones byte for byte`() {
        SkinDist.THEMES.forEach { theme ->
            val template = SkinDist.template(app, theme)
            val shipped = SkinDist.wsz(app, theme)
            val built = template.textFiles(template.colors(SkinDist.baselineScheme(theme)))

            assertEquals(
                "$theme builds the three text files",
                listOf("PLEDIT.TXT", "REGION.TXT", "VISCOLOR.TXT"),
                built.keys.sorted(),
            )
            for ((name, text) in built) {
                assertEquals("$theme $name is the shipped file", shipped.getValue(name).decodeToString(), text)
            }
        }
    }
}
