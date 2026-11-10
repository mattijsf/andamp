// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.BundledSkins
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A classic skin's main window as still pictures, drawn by the engine off screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WalStillTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val skin =
        File(app.cacheDir, "still-spot.wsz").apply {
            writeBytes(app.assets.open(BundledSkins.SPOT.asset).use { it.readBytes() })
        }

    private val still = WalStill.of(skin, app.cacheDir)

    @After
    fun close() = still.close()

    private fun pixels(image: ImageBitmap) = IntArray(image.width * image.height).also { image.readPixels(it) }

    @Test
    fun `the main window is drawn at 1x, and its shade when asked`() {
        val main = still.main(WalStill.Still(title = "A song", lengthMs = 200_000))
        assertNotNull(main)
        assertEquals(275 to 116, main!!.width to main.height)

        val shade = still.main(WalStill.Still(title = "A song", lengthMs = 200_000, shaded = true))
        assertEquals("the title bar alone", 275 to 14, shade!!.width to shade.height)

        val back = still.main(WalStill.Still(title = "A song", lengthMs = 200_000))
        assertEquals(275 to 116, back!!.width to back.height)
    }

    @Test
    fun `what plays shows in the picture`() {
        val stopped = pixels(still.main(WalStill.Still(title = "A song", lengthMs = 200_000))!!)
        val playing =
            pixels(
                still.main(
                    WalStill.Still(
                        playing = true,
                        title = "A song",
                        lengthMs = 200_000,
                        positionMs = 83_000,
                        bitrateKbps = 320,
                        sampleRateKhz = 44,
                        atMs = 1_000,
                    ),
                )!!,
            )
        assertFalse("the clock, the readout and the play sign change", stopped.contentEquals(playing))
        assertTrue("the picture is not empty", playing.any { it != 0 })
    }

    @Test
    fun `the equalizer is drawn alone, and its bands follow the still`() {
        val flat = still.main(WalStill.Still(window = WalStill.EQUALIZER, eqOn = true))
        assertNotNull(flat)
        assertEquals(275 to 116, flat!!.width to flat.height)

        val raised = still.main(WalStill.Still(window = WalStill.EQUALIZER, eqOn = true, bands = List(10) { 63 }))
        assertFalse("raised bands move the sliders", pixels(flat).contentEquals(pixels(raised!!)))

        val main = still.main(WalStill.Still())
        assertEquals("the main window again", 275 to 116, main!!.width to main.height)
        assertFalse("not the equalizer", pixels(main).contentEquals(pixels(flat)))
    }

    @Test
    fun `one still is kept per skin file`() {
        assertTrue(still === WalStill.of(skin, app.cacheDir))
    }
}
