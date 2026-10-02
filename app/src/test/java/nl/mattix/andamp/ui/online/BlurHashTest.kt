// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The decoder, against hashes this file did not produce.
 *
 * The fixtures were encoded by `tools/blurhash.py`, which follows the
 * reference implementation's algorithm. Two are from images whose colors are
 * known by construction: flat crimson, and white beside black.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // decoding ends in a real Bitmap
@Config(sdk = [35])
class BlurHashTest {
    private companion object {
        /** 16x16 of rgb(200, 40, 60). */
        const val FLAT = "LBM_AE|{fQ|{|{o2fQo2fQfQfQfQ"

        /** 16x16, white in the left half and black in the right. */
        const val SPLIT = "L~Lqe9~q%MIUt7t7j[ayfQfQfQfQ"

        /**
         * The museum's first tile, encoded from its own screenshot on the
         * same 4x3 grid as the other two.
         */
        const val BASE_291 =
            "L3A16Rt654R%~eITIn%M5,x[xGQ."

        /** Two implementations rounding the same floats may differ by one per channel. */
        const val TOLERANCE = 3
    }

    private fun far(
        a: Triple<Int, Int, Int>,
        b: Triple<Int, Int, Int>,
    ) = abs(a.first - b.first) + abs(a.second - b.second) + abs(a.third - b.third)

    private fun pixel(
        hash: String,
        x: Int,
        y: Int,
    ): Triple<Int, Int, Int> {
        val colour = BlurHash.decode(hash, 8, 8)!!.toPixelMap()[x, y]
        return Triple((colour.red * 255).toInt(), (colour.green * 255).toInt(), (colour.blue * 255).toInt())
    }

    /** The expected pixels are fixed values, not computed by this decoder. */
    @Test
    fun `it decodes the fixtures to their expected pixels`() {
        val wanted =
            listOf(
                Triple(FLAT, 0 to 0, Triple(231, 50, 75)),
                Triple(FLAT, 7 to 7, Triple(174, 30, 47)),
                Triple(FLAT, 4 to 2, Triple(208, 43, 64)),
                Triple(SPLIT, 0 to 0, Triple(255, 255, 255)),
                Triple(SPLIT, 7 to 7, Triple(123, 123, 123)),
                Triple(SPLIT, 4 to 2, Triple(166, 166, 166)),
                Triple(BASE_291, 0 to 0, Triple(80, 94, 85)),
                Triple(BASE_291, 7 to 7, Triple(60, 75, 95)),
                Triple(BASE_291, 4 to 2, Triple(103, 104, 108)),
            )

        wanted.forEach { (hash, at, expected) ->
            val (x, y) = at
            val got = pixel(hash, x, y)
            assertTrue("$hash at $x,$y decodes to $expected, got $got", far(got, expected) <= TOLERANCE)
        }
    }

    @Test
    fun `a split picture keeps its light side and its dark side`() {
        val left = pixel(SPLIT, 0, 4).first
        val right = pixel(SPLIT, 7, 4).first

        assertTrue("the light half is brighter than the dark half ($left against $right)", left - right > 80)
    }

    @Test
    fun `the size it is asked for is the size it returns`() {
        val decoded = BlurHash.decode(FLAT, 24, 30)!!

        assertEquals(24, decoded.width)
        assertEquals(30, decoded.height)
    }

    /**
     * The hashes are the app's own strings, but they are still parsed: a
     * malformed one decodes to nothing and does not throw.
     */
    @Test
    fun `rubbish decodes to nothing at all`() {
        listOf("", "L", "not a hash", FLAT.dropLast(1), FLAT + "0", "L~Lqe9~q%MIUt7t7j[ay!!!!!!!!").forEach {
            assertNull("'$it' decodes to nothing", BlurHash.decode(it, 8, 8))
        }
        assertNull(BlurHash.decode(FLAT, 0, 8))
    }

    @Test
    fun `every tile the welcome names has a hash that decodes`() {
        listOf(
            "5e4f10275dcb1fb211d4a8b4f1bda236",
            "cd251187a5e6ff54ce938d26f1f2de02",
            "47597ab8e5ffcd39686d455c10c3b436",
        ).forEach { md5 ->
            val hash = MuseumTiles.blurHashOf(md5)
            assertNotNull("$md5 has a stand-in hash", hash)
            assertNotNull("$md5's stand-in decodes", BlurHash.decode(hash!!, 24, 30))
        }
        assertNull(MuseumTiles.blurHashOf("0".repeat(32)))
    }
}
