// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.walkit.FontDef
import nl.mattix.walkit.HintedFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalCrispTextTest {
    private val liberation = checkNotNull(AndampDefaultSet.read("fonts/LiberationSans.ttf"))

    @Test
    fun `a classic skin's playlist text measures in whole pixels as Windows lays out Arial at 12 pixels`() {
        val crisp = WalCrispText { _, _ -> liberation }
        // size 16 is the em of 12 pixels the classic playlist asks for: digits 7 wide, the colon 3
        assertEquals(4 * 7 + 3, crisp.width("10:00", FontDef("arial", ""), 16, false))
        assertNull("a script the font has no letters for is left to the smooth drawing", crisp.width("坂本", null, 16, false))
    }

    @Test
    fun `its letters are whole pixels, ink or clear, with a stem one pixel wide`() {
        val font = checkNotNull(HintedFont.of(liberation))
        val px = font.draw("l", 12f, 0xFFFFFFFF.toInt(), 6, 14, 1, 11)
        assertEquals(setOf(0, 0xFFFFFFFF.toInt()), px.toSet())
        assertEquals("one pixel across the stem's middle", 1, (0 until 6).count { px[5 * 6 + it] != 0 })
        font.close()
    }
}
