// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The clock over a skin that paints digits of its own into the plate under it. Winamp
 * covers every slot with a cell of the numbers sheet, so such a skin never shows its own
 * digits; the slots a stopped player leaves alone are the skin's to fill.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MainWindowClockTest {
    /** The colour the plate's own digits are painted in; no sheet of the base skin has it. */
    private val plateDigits = 0xFFFF00FF.toInt()

    private val skin: Skin by lazy {
        val base = SkinLoader.loadBase(ApplicationProvider.getApplicationContext())
        val main = base[Sheet.MAIN].asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        for (x in Dest.TIME_DIGIT_X) {
            for (dx in 0 until 9) for (dy in 0 until 13) main.setPixel(x + dx, Dest.TIME_DIGIT_Y + dy, plateDigits)
        }
        val sheets = Sheet.entries.mapNotNull { sheet -> base.getOrNull(sheet)?.let { sheet to it } }.toMap()
        Skin(
            sheets = sheets + (Sheet.MAIN to main.asImageBitmap()),
            balanceUsesVolume = base.balanceUsesVolume,
            pledit = base.pledit,
            visColors = base.visColors,
            name = base.name,
        )
    }

    private fun render(block: DrawScope.() -> Unit): Bitmap {
        val image = ImageBitmap(MAIN_W, MAIN_H)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(MAIN_W.toFloat(), MAIN_H.toFloat()), block)
        return image.asAndroidBitmap()
    }

    private fun plateDigitPixels(
        bitmap: Bitmap,
        slot: Int,
    ): Int {
        var count = 0
        for (dx in 0 until 9) {
            for (dy in 0 until 13) {
                if (bitmap.getPixel(Dest.TIME_DIGIT_X[slot] + dx, Dest.TIME_DIGIT_Y + dy) == plateDigits) count++
            }
        }
        return count
    }

    private fun paused(blinkOn: Boolean) =
        WinampState().apply {
            playlist = List(4) { Track(id = "$it", artist = "A", title = "T", durationMs = 200_000) }
            transport = Transport.Paused
            currentIndex = 3
            currentTimeSec = 23
            this.blinkOn = blinkOn
        }

    @Test
    fun `the blink's off phase hides the plate's own digits`() {
        val bitmap = render { drawMainWindow(skin, paused(blinkOn = false)) }
        for (slot in 0 until 4) assertEquals("slot $slot shows the plate", 0, plateDigitPixels(bitmap, slot))
    }

    @Test
    fun `under ten minutes the tens-of-minutes slot hides the plate's own digit`() {
        val bitmap = render { drawMainWindow(skin, paused(blinkOn = true)) }
        assertEquals(0, plateDigitPixels(bitmap, 0))
    }

    @Test
    fun `a stopped player leaves the plate as the skin painted it`() {
        val bitmap = render { drawMainWindow(skin, WinampState()) }
        assertEquals(9 * 13, plateDigitPixels(bitmap, 0))
    }
}
