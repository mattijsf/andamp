// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.overlay

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySupportTest {
    @Test
    fun `Android 11 and later can show the floating player`() {
        assertTrue(OverlaySupport.on(Build.VERSION_CODES.R))
        assertTrue(OverlaySupport.on(Build.VERSION_CODES.VANILLA_ICE_CREAM))
    }

    @Test
    fun `Android 8 to 10 cannot`() {
        assertFalse(OverlaySupport.on(Build.VERSION_CODES.O))
        assertFalse(OverlaySupport.on(Build.VERSION_CODES.P))
        assertFalse(OverlaySupport.on(Build.VERSION_CODES.Q))
    }
}
