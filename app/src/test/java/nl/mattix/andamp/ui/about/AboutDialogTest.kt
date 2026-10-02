// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.about

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The box around the warp: what it shows, and that its two touches land. */
@RunWith(RobolectricTestRunner::class)
// the panel draws its picture into a real bitmap, which needs real graphics
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class AboutDialogTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * The panel animates for as long as it is on screen, so the clock never
     * goes idle and auto-advance is off.
     */
    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `it names the app, the site and Winamp`() {
        compose.setContent { AboutBody(onClose = {}) }

        compose.onNodeWithText("Andamp").assertIsDisplayed()
        compose.onNodeWithText("mattix.nl").assertIsDisplayed()
        compose
            .onNodeWithText("Winamp", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `Close leaves`() {
        var closed = 0
        compose.setContent { AboutBody(onClose = { closed++ }) }

        compose.onNodeWithTag("about.close").performClick()

        assertEquals(1, closed)
    }

    @Test
    fun `the logo is there to be touched`() {
        compose.setContent { AboutBody(onClose = {}) }

        // the burst it starts is tested with the animation; this checks that
        // the panel exists and takes a press
        compose.onNodeWithTag("about.logo").performClick()
    }
}

/** A poke on [AboutAnimation]. */
class AboutPokeTest {
    private val animation get() = AboutAnimation(8, 8, IntArray(8 * 8) { 0xFF17130F.toInt() })

    @Test
    fun `a poke while resting starts the burst at once`() {
        val warp = animation
        warp.advance(200)

        warp.poke()
        warp.advance(0)

        assertTrue("a poke starts the burst", warp.cycleMillis >= REST)
    }

    @Test
    fun `a poke mid-burst is left alone`() {
        val warp = animation
        warp.advance(REST + 400)
        val was = warp.cycleMillis

        warp.poke()

        assertEquals("a poke leaves a running warp alone", was, warp.cycleMillis)
    }

    private companion object {
        /** The rest length, which is private in `AboutAnimation`. */
        const val REST = 1_800L
    }
}
