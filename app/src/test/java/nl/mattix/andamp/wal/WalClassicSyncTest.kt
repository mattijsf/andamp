// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.test.junit4.v2.createComposeRule
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.state.MarkPlace
import nl.mattix.andamp.state.MusicSource
import nl.mattix.andamp.state.SourceMark
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.WinampState
import nl.mattix.walkit.ClassicSwitches
import nl.mattix.walkit.ClassicVisMode
import nl.mattix.walkit.RowMark
import nl.mattix.walkit.RowMarks
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalClassicSyncTest {
    @get:Rule
    val compose = createComposeRule()

    private val s = WinampState()
    private val look = FakeWalSurface().apply { classic = true }

    private fun layout(pressed: WalPressedSwitches?) =
        WalLayout(
            fit = WalFit(1080, 1920, 2.75f, sideways = false),
            scale = 3f,
            left = 0f,
            top = 0f,
            stackW = 275,
            stackH = 116,
            windowOpen = false,
            animating = false,
            menuTarget = null,
            pressedSwitches = pressed,
        )

    @Test
    fun `the app's settings reach the skin as they are and at each change`() {
        s.visMode = VisMode.Oscilloscope
        compose.setContent { WalClassicSync(look, s) }
        compose.waitForIdle()

        s.timeRemaining = true
        s.selectedRows = setOf(1, 2)
        compose.waitForIdle()

        assertEquals(
            listOf(
                ClassicSwitches(visMode = ClassicVisMode.SCOPE),
                ClassicSwitches(timeRemaining = true, visMode = ClassicVisMode.SCOPE),
            ),
            look.switches,
        )
        assertEquals(listOf(emptySet(), setOf(1, 2)), look.selections)
    }

    @Test
    fun `the shade setting and the playlist's marks reach the skin`() {
        s.shadeEnabled = false
        s.listMarks = MarkPlace.BEFORE
        val example = SourceMark.letter("Example")
        s.sourceMarks = mapOf(MusicSource.LOCAL.id to SourceMark.PHONE, "EXAMPLE" to example)
        s.playlist =
            listOf(
                Track("a", "A", "One", 1000L, uri = "content://media/external/audio/media/1"),
                Track("b", "B", "Two", 1000L, uri = "example:track:2"),
            )
        compose.setContent { WalClassicSync(look, s) }
        compose.waitForIdle()

        assertEquals(listOf(false), look.shades)
        val marks = look.rowMarks.last()
        assertEquals(nl.mattix.walkit.MarkPlace.BEFORE, marks.place)
        assertEquals(
            listOf(RowMark(SourceMark.PHONE.pixels, SourceMark.PHONE.letter), RowMark(example.pixels, example.letter)),
            marks.marks,
        )
        assertEquals(listOf(0, 1), marks.rows)

        s.listMarks = MarkPlace.OFF
        compose.waitForIdle()
        assertEquals(RowMarks.NONE, look.rowMarks.last())
    }

    @Test
    fun `a press on the skin changes the app's settings, and one from before the screen showed it does not`() {
        look.layout = layout(WalPressedSwitches(1, ClassicSwitches(timeRemaining = true)))
        compose.setContent { WalClassicSync(look, s) }
        compose.waitForIdle()
        assertEquals("the old press was heard already", false, s.timeRemaining)

        look.layout = layout(WalPressedSwitches(2, ClassicSwitches(eqAuto = true, visMode = ClassicVisMode.OFF)))
        compose.waitForIdle()

        assertEquals(false, s.timeRemaining)
        assertEquals(true, s.eqAuto)
        assertEquals(VisMode.Off, s.visMode)
    }
}
