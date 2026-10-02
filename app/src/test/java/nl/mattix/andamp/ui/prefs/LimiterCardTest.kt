// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import nl.mattix.andamp.core.playback.PeakReading
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The limiter's card says what the output stage is doing, in one line.
 *
 * Off, it says when peaks are being cut off and stops saying so soon after;
 * on, it says how far it is holding the volume down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LimiterCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val second = 1_000_000_000L

    @Test
    fun `off and nothing cut off says what off means`() {
        val status = limiterStatus(on = false, reading = PeakReading.NONE, nowNanos = 10 * second)
        assertEquals("Off · loud peaks are cut off", status.line)
        assertEquals(LimiterTone.QUIET, status.tone)
    }

    @Test
    fun `off and a peak cut off just now says so, and offers the way out`() {
        val status = limiterStatus(on = false, reading = PeakReading(0f, 9 * second), nowNanos = 10 * second)
        assertEquals("Peaks cut off just now · turn on to prevent", status.line)
        assertEquals(LimiterTone.CLIPPING, status.tone)
    }

    @Test
    fun `a clip from a while ago reads as quiet`() {
        val status = limiterStatus(on = false, reading = PeakReading(0f, 1 * second), nowNanos = 10 * second)
        assertEquals(LimiterTone.QUIET, status.tone)
    }

    @Test
    fun `on and holding says how far down`() {
        val status = limiterStatus(on = true, reading = PeakReading(-2.14f, 0L), nowNanos = 10 * second)
        assertEquals("Holding peaks · −2.1 dB", status.line)
        assertEquals(LimiterTone.HOLDING, status.tone)
    }

    @Test
    fun `on and not needed says what it does`() {
        val status = limiterStatus(on = true, reading = PeakReading(-0.02f, 0L), nowNanos = 10 * second)
        assertEquals("Keeps loud peaks under −1 dB", status.line)
    }

    @Test
    fun `the switch is the rack's setting, and the line follows the reading`() {
        var on by mutableStateOf(false)
        var reading = PeakReading.NONE
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                LimiterCard(on = on, onChange = { on = it }, peaks = { reading })
            }
        }
        compose.onNodeWithTag("dsp.limiter.on").assertIsOff()

        compose.onNodeWithTag("dsp.limiter.on").performClick()
        reading = PeakReading(-3f, 0L)
        compose.mainClock.advanceTimeBy(500)

        compose.onNodeWithTag("dsp.limiter.on").assertIsOn()
        compose.onNodeWithTag("dsp.limiter.status").assertTextEquals("Holding peaks · −3.0 dB")
    }
}
