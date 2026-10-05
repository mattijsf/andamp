// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import nl.mattix.andamp.ui.window.BIG_WINDOW
import nl.mattix.andamp.ui.window.LocalSurfaceScreen
import nl.mattix.andamp.ui.window.SurfaceScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The surface the windows are laid out on, when it is larger than the screen it is drawn on.
 *
 * The windows only know the surface: its size, its insets and touches at its coordinates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class PlayerViewportBoxTest {
    @get:Rule val compose = createComposeRule()

    /** An 800 px box showing a surface laid out 1000 px wide. */
    private val shrunk = PlayerViewport(scale = 4, shrink = 0.8f)
    private val told = SurfaceScreen(statusBar = 80, shadeStrip = 40, bottom = 120)

    private var given: Constraints? = null
    private var screen: SurfaceScreen? = null
    private var tapped: Offset? = null
    private var view: View? = null
    private var outerDensity = 0f
    private var innerDensity = 0f

    private fun show(
        viewport: PlayerViewport,
        told: SurfaceScreen? = this.told,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            view = LocalView.current
            outerDensity = density.density
            CompositionLocalProvider(LocalSurfaceScreen provides told) {
                Box(Modifier.size(with(density) { 800.toDp() }, with(density) { 1200.toDp() }).testTag("screen")) {
                    PlayerViewportBox(viewport) {
                        given = constraints
                        screen = LocalSurfaceScreen.current
                        innerDensity = LocalDensity.current.density
                        // a 100 px target at (500, 1000) on the surface
                        Box(
                            Modifier
                                .offset { IntOffset(500, 1000) }
                                .size(with(density) { 100.toDp() })
                                .pointerInput(Unit) { detectTapGestures { tapped = it } },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the windows are given the surface's size, not the screen's`() {
        show(shrunk)

        assertEquals(Constraints.fixed(1000, 1500), given)
    }

    @Test
    fun `a touch on the screen reaches what is drawn under it`() {
        show(shrunk)

        // the target's corner is drawn at (400, 800); 20 px into it on the screen is 25 on the surface
        compose.onNodeWithTag("screen").performTouchInput {
            down(Offset(420f, 820f))
            up()
        }

        assertEquals(25f, tapped!!.x, 1f)
        assertEquals(25f, tapped!!.y, 1f)
    }

    @Test
    fun `a larger surface reads the window's insets itself and answers in its own pixels`() {
        show(shrunk)
        // a gesture bar 48 screen pixels tall, handed to the window as the system would
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view!!.rootView, insets) }
        compose.waitForIdle()

        assertEquals("60 of the surface's pixels are drawn as 48", 60, screen?.bottom)
    }

    @Test
    fun `a dp is as many of the surface's pixels as it is drawn over`() {
        show(shrunk)

        assertEquals(outerDensity / 0.8f, innerDensity, 0.001f)
    }

    @Test
    fun `a surface the size of the screen passes on what it was told about the screen`() {
        show(PlayerViewport(scale = 3, shrink = 1f))

        assertEquals(told, screen)
    }

    @Test
    fun `a surface the size of the screen changes nothing`() {
        show(PlayerViewport(scale = 3, shrink = 1f), told = null)

        assertEquals(Constraints.fixed(800, 1200), given)
        assertNull("the windows read the insets themselves", screen)
        assertEquals(outerDensity, innerDensity, 0f)
    }
}
