// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.widget

import androidx.compose.ui.geometry.Offset
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a slider says its thumb is: the place a press on it reads back as the value it shows. */
class SliderHandleTest {
    private val state = WinampState()

    /** The volume slider's shape: 68 wide with a 14 wide thumb that travels 51. */
    private fun across(
        shown: Float,
        onChange: (Float) -> Unit = {},
    ) = hSlider("s", x = 100, y = 20, w = 68, h = 13, thumbW = 14, travelOverride = 51, shown = { shown }, onChange = onChange)

    /** An equalizer band's shape: 63 tall with an 11 tall thumb. */
    private fun upright(
        shown: Float,
        onChange: (Float) -> Unit = {},
    ) = vSlider("s", x = 78, y = 38, w = 14, h = 63, thumbH = 11, shown = { shown }, onChange = onChange)

    @Test
    fun `a horizontal slider's thumb is its value along the travel, at the middle of its height`() {
        assertEquals(Offset(100 + 7 + 25.5f, 26.5f), across(0.5f).handle?.invoke())
    }

    @Test
    fun `a press on a horizontal slider's thumb reports the value it shows`() {
        for (shown in listOf(0f, 0.25f, 0.5f, 0.8f, 1f)) {
            var reported = -1f
            val slider = across(shown) { reported = it }

            slider.down(state, slider.handle!!.invoke())

            assertEquals(shown, reported, 0.001f)
        }
    }

    @Test
    fun `a vertical slider's thumb is highest at its largest value`() {
        assertEquals(Offset(85f, 38 + 5.5f), upright(1f).handle?.invoke())
        assertEquals(Offset(85f, 38 + 5.5f + 52), upright(0f).handle?.invoke())
    }

    @Test
    fun `a press on a vertical slider's thumb reports the value it shows`() {
        for (shown in listOf(0f, 0.3f, 0.5f, 1f)) {
            var reported = -1f
            val slider = upright(shown) { reported = it }

            slider.down(state, slider.handle!!.invoke())

            assertEquals(shown, reported, 0.001f)
        }
    }

    @Test
    fun `a slider that is not told what it shows, and a button, have no thumb to hold`() {
        assertNull(hSlider("s", x = 0, y = 0, w = 68, h = 13, thumbW = 14, onChange = {}).handle)
        assertNull(button("b", 0, 0, 23, 18) {}.handle)
    }
}
