// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** A skin picture's pixels read a block at a time, as the runtime reads a window's background to see how far it shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalHostPixelsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** A [w] x [h] PNG with the colors [color] gives. */
    private fun png(
        w: Int,
        h: Int,
        color: (Int, Int) -> Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bitmap.setPixel(x, y, color(x, y))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun files(vararg entries: Pair<String, ByteArray>): SkinFiles =
        object : SkinFiles {
            private val m = entries.toMap()

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    @Test
    fun `a block reads the pixels one at a time would, and nothing past the picture's edge`() {
        // opaque colors that differ per pixel, and one clear pixel
        val picture = png(4, 3) { x, y -> if (x == 3 && y == 2) 0 else (0xFF shl 24) or (x * 40 shl 16) or (y * 60 shl 8) or 7 }
        val host = WalHost(files("p.png" to picture), app.cacheDir)

        val block = checkNotNull(host.pixels("p.png", 1, 1, 3, 2))
        assertEquals(List(6) { i -> host.pixel("p.png", 1 + i % 3, 1 + i / 3) }, block.toList())
        assertEquals(0, block.last())

        assertNull("past the right edge", host.pixels("p.png", 2, 0, 3, 1))
        assertNull("before the left edge", host.pixels("p.png", -1, 0, 2, 1))
        assertNull("no such picture", host.pixels("q.png", 0, 0, 1, 1))
    }

    @Test
    fun `a window's background that shows past its shaped body counts as drawn on the phone, as far as it shows`() {
        // a bar 250 px long in the top 20 rows of a 300 px window, over a body that ends at 200
        val bar = png(300, 100) { x, y -> if (x < 250 && y < 20) 0xFF808080.toInt() else 0 }
        val skin =
            files(
                "skin.xml" to
                    """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>"""
                        .toByteArray(),
                "player.xml" to
                    (
                        """<elements><bitmap id="bg" file="bg.png"/></elements>""" +
                            """<container id="main" name="t" default_visible="1">""" +
                            """<layout id="normal" w="300" h="100" background="bg">""" +
                            """<group id="body" x="0" y="0" w="200" h="100" sysregion="1" rectrgn="1"/>""" +
                            """</layout></container>"""
                    ).toByteArray(),
                "bg.png" to bar,
            )
        val rt =
            SkinRuntime(skin, WalHost(skin, app.cacheDir), FakePlayer()).apply {
                viewSize = 300 to 1000
                load()
            }
        assertEquals(250 to 100, rt.drawnSize)
    }
}
