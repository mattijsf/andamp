// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import nl.mattix.andamp.core.model.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the widget's analyzer is worth following the audio for.
 *
 * The launcher animates the frames it already has and stops when the home
 * screen goes away. Producing new frames costs an FFT per frame, so each reason
 * not to produce them is checked here.
 */
class WidgetOpsTest {
    private fun wants(
        transport: Transport = Transport.Playing,
        hasAudio: Boolean = true,
        screenOn: Boolean = true,
        placed: Boolean = true,
    ) = WidgetOps.wantsFrames(transport, hasAudio, screenOn, placed)

    @Test
    fun `with no widget placed no frames are wanted`() {
        // playing a song must not start an FFT burst when no widget exists to show it
        assertFalse(wants(placed = false))
    }

    @Test
    fun `a playing song on a lit home screen is worth following`() {
        assertTrue(wants())
    }

    @Test
    fun `a stopped player has nothing to follow`() {
        assertFalse(wants(transport = Transport.Stopped))
        assertFalse(wants(transport = Transport.Paused))
    }

    @Test
    fun `a backend that hands over no audio cannot be analyzed`() {
        // a remote backend plays without exposing its samples, so the analyzer stays dark
        assertFalse(wants(hasAudio = false))
    }

    @Test
    fun `a dark screen wants no frames`() {
        // a phone in a pocket, playing for hours
        assertFalse(wants(screenOn = false))
    }

    @Test
    fun `one reason is enough`() {
        assertFalse(wants(transport = Transport.Stopped, screenOn = false))
        assertFalse(wants(transport = Transport.Paused, hasAudio = false))
        assertFalse(wants(placed = false, screenOn = false))
    }
}
