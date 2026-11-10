// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import nl.mattix.andamp.skin.BundledSkins
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A fetched skin on the demo player, as the engine draws it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SkinPreviewGoldenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `a skin on the demo player matches its golden`() {
        val file =
            File(app.cacheDir, "golden-spot.wsz").apply {
                writeBytes(app.assets.open(BundledSkins.SPOT.asset).use { it.readBytes() })
            }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.width(275.dp).testTag("preview")) { SkinPreviewCanvas(file, interactive = false) }
        }
        // the engine loads on its own thread, and draws once a frame asks it to
        repeat(STEPS) {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(STEP_MS)
        }
        compose.waitForIdle()

        // the analyzer's bars and peaks fall by the ticks the engine ran, which thread timing moves
        // by a few pixels; anything more is a change
        compose.onNodeWithTag("preview").captureRoboImage(
            "src/test/snapshots/skin_preview_spot.png",
            RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = PEAK_SLACK)),
        )
    }

    private companion object {
        const val STEPS = 40
        const val STEP_MS = 25L

        /** The share of the picture's pixels the analyzer's peaks may move: about 100 of its 100,000. */
        const val PEAK_SLACK = 0.001f
    }
}
