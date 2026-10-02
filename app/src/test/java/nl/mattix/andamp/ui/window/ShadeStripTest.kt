// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who answers "how far down may a title bar sit".
 *
 * Two surfaces draw the player and they do not share an answer: the activity spans the
 * screen, the floating window is already positioned below the shade's strip. Every window
 * and the layout around them ask [grabbableTop], so the answer comes from one place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShadeStripTest {
    @get:Rule val compose = createComposeRule()

    private fun topWith(strip: Int?): Int {
        var seen = -1
        compose.setContent {
            CompositionLocalProvider(
                LocalSurfaceScreen provides strip?.let { SurfaceScreen(statusBar = 0, shadeStrip = it, bottom = 0) },
            ) {
                seen = grabbableTop(scale = 3, density = LocalDensity.current)
            }
        }
        compose.waitForIdle()
        return seen
    }

    @Test
    fun `a surface that holds none of the strip clamps to nothing`() {
        assertEquals(0, topWith(0))
    }

    @Test
    fun `a surface says how much of the strip it holds, in its own scale`() {
        assertEquals(10, topWith(30))
    }

    @Test
    fun `a strip that is not a whole row rounds down`() {
        assertEquals(4, topWith(14))
    }

    @Test
    fun `saying nothing means asking the window`() {
        // Robolectric dispatches no gesture insets, so the window's own answer is
        // zero; what matters is that null asks rather than assuming
        assertEquals(0, topWith(null))
    }
}
