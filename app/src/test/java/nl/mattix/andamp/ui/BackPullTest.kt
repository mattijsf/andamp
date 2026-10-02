// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A back gesture the screen can follow.
 *
 * Android reports a back gesture as it happens: where the finger is and how far it has
 * come. These check that the progress reaches the screen and that letting go without
 * finishing leaves the screen where it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackPullTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var pull: BackPull
    private var left = false

    private fun show() {
        compose.setContent {
            pull = rememberBackPull { left = true }
            Box(Modifier.fillMaxSize().pulledAside(pull))
        }
        compose.waitForIdle()
    }

    private fun pull(fraction: Float) {
        compose.runOnUiThread {
            val back = compose.activity.onBackPressedDispatcher
            if (fraction == 0f) {
                back.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            } else {
                back.dispatchOnBackProgressed(BackEventCompat(0f, 0f, fraction, BackEventCompat.EDGE_LEFT))
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the screen hears the gesture while it is happening`() {
        show()

        pull(0f)
        pull(HALF)

        assertEquals("the screen receives the gesture's progress", HALF, pull.progress, 0.001f)
        assertFalse("the screen stays until the gesture finishes", left)
    }

    @Test
    fun `letting go leaves the screen where it was`() {
        show()
        pull(0f)
        pull(HALF)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        // it springs back, so the progress reaches 0 a moment later
        compose.waitUntil(SETTLING_MS) { pull.progress == 0f }

        assertEquals("the screen springs back to where it was", 0f, pull.progress, 0.001f)
        assertFalse(left)
    }

    @Test
    fun `finishing the gesture leaves`() {
        show()
        pull(0f)
        pull(HALF)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(SETTLING_MS) { pull.progress == 1f }

        assertTrue("a finished gesture leaves the screen", left)
        // the pull runs on to 1: snapping back to 0 would draw the screen at
        // full size for a frame before the transition takes over
        assertEquals("the pull runs on to 1", 1f, pull.progress, 0.001f)
    }

    @Test
    fun `a press with no gesture behind it still leaves`() {
        // three-button navigation, and the accessibility back action
        show()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertTrue(left)
    }

    private companion object {
        const val HALF = 0.5f

        const val SETTLING_MS = 2_000L
    }
}
