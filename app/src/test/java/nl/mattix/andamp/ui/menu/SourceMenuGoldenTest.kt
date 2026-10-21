// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.github.takahirom.roborazzi.captureRoboImage
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.SourceMark
import nl.mattix.andamp.state.WinampState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The library's source menu: each source's mark before its name, as its rows carry it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SourceMenuGoldenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the source menu shows each source's mark before its name`() {
        val state = WinampState()
        state.activeMenu =
            AmpMenu(
                "Media Library",
                listOf(
                    AmpMenuItem.Action("All Sources", checked = true) {},
                    AmpMenuItem.Divider,
                    AmpMenuItem.Action("This Phone", mark = SourceMark.PHONE) {},
                    AmpMenuItem.Action("Example", mark = SourceMark.letter("Example")) {},
                ),
            )
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AmpModals(state, anchorBounds = { IntRect(IntOffset(200, 100), IntSize(44, 12)) })
            }
        }
        compose.onNode(isPopup()).captureRoboImage("src/test/snapshots/source_menu_marks.png")
    }
}
