// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.state.EqOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderMathTest {
    @Test
    fun `volume frame rounds over 28 frames like webamp MainVolume`() {
        assertEquals(0, SliderMath.volumeFrame(0))
        assertEquals(0, SliderMath.volumeFrame(1)) // round(0.28)-1 clamps to 0
        assertEquals(13, SliderMath.volumeFrame(50))
        assertEquals(21, SliderMath.volumeFrame(78))
        assertEquals(27, SliderMath.volumeFrame(100))
    }

    @Test
    fun `volume thumb travels 0 to 51`() {
        assertEquals(0, SliderMath.volumeThumbOffset(0))
        assertEquals(26, SliderMath.volumeThumbOffset(50))
        assertEquals(51, SliderMath.volumeThumbOffset(100))
    }

    @Test
    fun `balance frame floors over 27 frames and mirrors around center`() {
        assertEquals(0, SliderMath.balanceFrame(0))
        assertEquals(13, SliderMath.balanceFrame(50)) // floor(13.5)
        assertEquals(13, SliderMath.balanceFrame(-50))
        assertEquals(27, SliderMath.balanceFrame(100))
        assertEquals(27, SliderMath.balanceFrame(-100))
    }

    @Test
    fun `balance thumb travels 0 to 24 with center at 12`() {
        assertEquals(0, SliderMath.balanceThumbOffset(-100))
        assertEquals(12, SliderMath.balanceThumbOffset(0))
        assertEquals(24, SliderMath.balanceThumbOffset(100))
    }

    @Test
    fun `eq frame maps 0-63 onto the 28 groove frames`() {
        assertEquals(0, SliderMath.eqFrame(0))
        assertEquals(13, SliderMath.eqFrame(31))
        assertEquals(27, SliderMath.eqFrame(63))
    }

    @Test
    fun `eq thumb travels 51 down to 0 with value 63 at the top`() {
        assertEquals(51, SliderMath.eqThumbOffset(0))
        assertEquals(26, SliderMath.eqThumbOffset(31))
        assertEquals(0, SliderMath.eqThumbOffset(63))
    }

    @Test
    fun `eq graph row spans 0 to 18 with plus 12dB at row zero`() {
        assertEquals(18, SliderMath.eqGraphY(0))
        assertEquals(9, SliderMath.eqGraphY(31))
        assertEquals(0, SliderMath.eqGraphY(63))
    }

    @Test
    fun `posbar thumb travels 0 to 219 and clamps the fraction`() {
        assertEquals(0, SliderMath.posbarThumbOffset(0f))
        assertEquals(110, SliderMath.posbarThumbOffset(0.5f))
        assertEquals(219, SliderMath.posbarThumbOffset(1f))
        assertEquals(219, SliderMath.posbarThumbOffset(1.5f))
        assertEquals(0, SliderMath.posbarThumbOffset(-1f))
    }

    @Test
    fun `out-of-range inputs clamp instead of overflowing the sprite sheets`() {
        assertEquals(27, SliderMath.volumeFrame(150))
        assertEquals(27, SliderMath.balanceFrame(300))
        assertEquals(27, SliderMath.eqFrame(99))
        assertEquals(0, SliderMath.eqThumbOffset(99))
    }
}

/** [formatTime], and the balance and preamp detents of [SliderMath]. */
class FormatTimeTest {
    @Test
    fun `formats minutes and zero-padded seconds`() {
        assertEquals("0:00", formatTime(0))
        assertEquals("0:05", formatTime(5))
        assertEquals("1:05", formatTime(65))
        assertEquals("59:59", formatTime(3599))
    }

    @Test
    fun `rolls to hours at one hour like the playlist total display`() {
        assertEquals("1:00:00", formatTime(3600))
        assertEquals("1:02:05", formatTime(3725))
    }

    @Test
    fun `balance snaps to center inside the detent and stays put outside it`() {
        val detent = SliderMath.BALANCE_DETENT
        assertEquals(0, SliderMath.stickyBalance(0))
        assertEquals(0, SliderMath.stickyBalance(detent))
        assertEquals(0, SliderMath.stickyBalance(-detent))
        assertEquals(detent + 1, SliderMath.stickyBalance(detent + 1))
        assertEquals(-(detent + 1), SliderMath.stickyBalance(-(detent + 1)))
        assertEquals(100, SliderMath.stickyBalance(100))
    }

    @Test
    fun `the detent is wide enough to feel on a phone but leaves most of the travel usable`() {
        // 24 virtual px of travel: the snap zone must not eat the slider
        val detent = SliderMath.BALANCE_DETENT
        assertTrue("the detent is at least 12 wide: $detent", detent >= 12)
        assertTrue("the detent is at most 25 wide: $detent", detent <= 25)
    }

    @Test
    fun `the preamp sticks to its 0dB rest position`() {
        assertEquals(EqOps.CENTER, SliderMath.stickyPreamp(EqOps.CENTER - SliderMath.PREAMP_DETENT))
        assertEquals(EqOps.CENTER, SliderMath.stickyPreamp(EqOps.CENTER + SliderMath.PREAMP_DETENT))
        assertEquals(EqOps.CENTER, SliderMath.stickyPreamp(EqOps.CENTER))
    }

    @Test
    fun `a value just outside the preamp detent is still choosable`() {
        val below = EqOps.CENTER - SliderMath.PREAMP_DETENT - 1
        val above = EqOps.CENTER + SliderMath.PREAMP_DETENT + 1

        assertEquals(below, SliderMath.stickyPreamp(below))
        assertEquals(above, SliderMath.stickyPreamp(above))
    }

    @Test
    fun `the preamp detent pulls over a shorter distance than the balance one`() {
        // both in thumb travel: balance runs 200 units over 24px, the preamp
        // 63 over 51px. A shorter pull keeps small boosts near 0dB pickable.
        val balancePx = SliderMath.BALANCE_DETENT / 200f * 24f
        val preampPx = SliderMath.PREAMP_DETENT / 63f * 51f

        assertTrue("the preamp detent is shorter than the balance one: $preampPx px against $balancePx px", preampPx < balancePx)
    }
}
