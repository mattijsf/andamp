// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.LiveSkins
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.ui.window.testViewModel
import nl.mattix.andamp.wal.WalBundled
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A car's screen with the window drawn by the engine, from the skin's file. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-mdpi")
class RemoteScreenGoldenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun capture(
        up: Up,
        name: String,
    ) {
        val vm = testViewModel()
        val file = WalBundled.fileOf(app, BundledSkins.SPOT, LiveSkins { _, _ -> null })
        val skin = app.assets.open(BundledSkins.SPOT.asset).use { SkinLoader.load(it, null, "Spot") }
        val showing = Showing().apply { this.up = up }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(800.dp, 480.dp).testTag("car")) {
                RemoteScreen(vm, skin, file, within = null, scroll = ListScroll(), showing = showing)
            }
        }
        // the engine draws on another thread, a picture each 33 ms of frames
        repeat(STEPS) {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(STEP_MS)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("car").captureRoboImage("src/test/snapshots/$name.png")
    }

    @Test
    fun `the main window on a car's screen matches its golden`() = capture(Up.MAIN, "car_main_spot")

    @Test
    fun `the equalizer on a car's screen matches its golden`() = capture(Up.EQUALIZER, "car_equalizer_spot")

    private companion object {
        const val STEPS = 30
        const val STEP_MS = 20L
    }
}
