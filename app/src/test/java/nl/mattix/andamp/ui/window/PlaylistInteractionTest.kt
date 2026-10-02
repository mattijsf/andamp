// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.LONG_PRESS_MS
import nl.mattix.andamp.ui.ScaledWindowCanvas
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class PlaylistInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: WinampViewModel
    private var dirRequests = 0
    private var saveRequests = 0
    private var loadRequests = 0

    // minimum window: 4 visible rows (10 fake tracks -> max scroll 6)
    private val layout = PlaylistLayout(116)

    @Before
    fun setUp() {
        vm = testViewModel()
        // stand in for the document pickers, so the menu wiring stays covered
        val widgets =
            playlistWidgets(
                vm,
                layout,
                PlaylistMenuActions(
                    addFile = { vm.playlistOps.addDefaultTracks() },
                    addDir = { dirRequests++ },
                    saveList = { saveRequests++ },
                    loadList = { loadRequests++ },
                ),
                // the app hands the menu's own actions in, since they reach
                // further into it than a window does
                onMenuEntry = { entry -> entry.action(vm) },
                rowMenu = { row ->
                    nl.mattix.andamp.ui.menu.playlistRowMenu(
                        vm,
                        row,
                        nl.mattix.andamp.state
                            .MenuAnchor(nl.mattix.andamp.state.WindowStore.PLAYLIST, 0, 0, 10, 10),
                    )
                },
            )
        compose.setContent {
            ScaledWindowCanvas(PL_W, layout.height, SCALE, vm.state, widgets) {}
        }
    }

    private fun rowCenterY(visibleRow: Int): Float = layout.textTop + visibleRow * Dest.PL_ROW_H + 6f

    @Test
    fun `tapping a row selects it`() {
        compose.onRoot().performTouchInput { tapVirtual(100f, rowCenterY(2)) }
        assertEquals(setOf(2), vm.state.selectedRows)
        assertEquals(Transport.Stopped, vm.state.transport)
    }

    @Test
    fun `tapping the duration at the right end of a row still selects the row`() {
        // the scrollbar begins one pixel past the text area, so the readout
        // sits within touch slop of it: a press there must not jump-scroll
        val lastTextColumn = (PL_W - Dest.PL_RIGHT_W - 1).toFloat()
        val scrollBefore = vm.state.playlistScroll

        compose.onRoot().performTouchInput { tapVirtual(lastTextColumn, rowCenterY(3)) }

        assertEquals(setOf(3), vm.state.selectedRows)
        assertEquals(scrollBefore, vm.state.playlistScroll)
    }

    @Test
    fun `double tapping a row plays it`() {
        compose.onRoot().performTouchInput { tapVirtual(100f, rowCenterY(1)) }
        compose.onRoot().performTouchInput { tapVirtual(100f, rowCenterY(1)) }
        assertEquals(1, vm.state.currentIndex)
        assertEquals(Transport.Playing, vm.state.transport)
    }

    @Test
    fun `vertical drag scrolls by whole rows`() {
        compose.onRoot().performTouchInput {
            dragVirtual(100f, rowCenterY(3), 100f, rowCenterY(3) - 2 * Dest.PL_ROW_H)
        }
        assertEquals(2, vm.state.playlistScroll)
    }

    @Test
    fun `scrollbar drag jumps proportionally`() {
        // track top 20, handle 18: frac = (y - 20 - 9) / (middleH - 18); ends overshoot so clamping is exact.
        // x=272 avoids the scroll-arrow buttons at x 260-268 which win the hit-test over the track.
        compose.onRoot().performTouchInput { tapVirtual(272f, 74f) } // past bottom -> maxScroll 6
        assertEquals(6, vm.state.playlistScroll)
        compose.onRoot().performTouchInput { tapVirtual(272f, 25f) } // past top -> 0
        assertEquals(0, vm.state.playlistScroll)
    }

    @Test
    fun `mini transport play and stop work from the bottom bar`() {
        val btnY = layout.height - Dest.PL_BOTTOM_H + 27f // buttons at +22, 10px tall
        compose.onRoot().performTouchInput { tapVirtual(143f, btnY) } // play: x=128+10..138
        assertEquals(Transport.Playing, vm.state.transport)
        compose.onRoot().performTouchInput { tapVirtual(163f, btnY) } // stop: x=158..168
        assertEquals(Transport.Stopped, vm.state.transport)
    }

    @Test
    fun `close button hides the playlist window`() {
        compose.onRoot().performTouchInput { tapVirtual(268f, 7f) } // (264,3) 9x9
        assertEquals(false, vm.state.plVisible)
    }

    // menu geometry at h=116: buttons y 86..104; SEL/ADD menus top at y=50, REM at y=32

    @Test
    fun `menu button toggles its menu and an outside tap closes it`() {
        compose.onRoot().performTouchInput { tapVirtual(83f, 95f) } // SEL button
        assertEquals("pl.menu.sel", vm.state.openMenu)
        compose.onRoot().performTouchInput { tapVirtual(150f, 40f) } // rows area -> scrim
        assertEquals(null, vm.state.openMenu)
        assertEquals(emptySet<Int>(), vm.state.selectedRows) // scrim swallowed the tap, no row selected
    }

    @Test
    fun `select all and invert work from the SEL menu`() {
        compose.onRoot().performTouchInput { tapVirtual(83f, 95f) } // open SEL
        compose.onRoot().performTouchInput { tapVirtual(83f, 95f) } // bottom entry = SELECT ALL
        assertEquals(
            vm.state.playlist.indices
                .toSet(),
            vm.state.selectedRows,
        )
        assertEquals(null, vm.state.openMenu)

        compose.onRoot().performTouchInput { tapVirtual(83f, 95f) } // open SEL again
        compose.onRoot().performTouchInput { tapVirtual(83f, 59f) } // top entry = INVERT
        assertEquals(emptySet<Int>(), vm.state.selectedRows)
    }

    @Test
    fun `remove selected drops the track via the REM menu`() {
        compose.onRoot().performTouchInput { tapVirtual(100f, rowCenterY(2)) } // select row 2
        compose.onRoot().performTouchInput { tapVirtual(54f, 95f) } // open REM
        compose.onRoot().performTouchInput { tapVirtual(54f, 95f) } // REMOVE SELECTED, the bottom entry
        assertEquals(9, vm.state.playlist.size)
        assertEquals(emptySet<Int>(), vm.state.selectedRows)
        assertEquals(null, vm.state.openMenu)
    }

    @Test
    fun `remove all empties the playlist and add file refills it`() {
        compose.onRoot().performTouchInput { tapVirtual(54f, 95f) } // open REM
        compose.onRoot().performTouchInput { tapVirtual(54f, 59f) } // REMOVE ALL, second from the top
        assertEquals(0, vm.state.playlist.size)
        assertEquals(Transport.Stopped, vm.state.transport)

        compose.onRoot().performTouchInput { tapVirtual(25f, 95f) } // open ADD
        compose.onRoot().performTouchInput { tapVirtual(25f, 95f) } // bottom entry = ADD FILE
        assertEquals(10, vm.state.playlist.size)
    }

    /** Touch's right click: the press that stayed put opens the row's menu. */
    @Test
    fun `a long press on a row opens its menu and does not play it`() {
        compose.onRoot().performTouchInput {
            down(Offset(100f * SCALE, rowCenterY(2) * SCALE))
        }
        compose.mainClock.advanceTimeBy(LONG_PRESS_MS + 100)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()

        assertEquals("a long press opens the row's menu", true, vm.state.activeMenu != null)
        assertEquals(Transport.Stopped, vm.state.transport)
    }

    /** The menu acts on the selection, so the press has to make one first. */
    @Test
    fun `a long press on an unselected row selects it`() {
        compose.onRoot().performTouchInput {
            down(Offset(100f * SCALE, rowCenterY(3) * SCALE))
        }
        compose.mainClock.advanceTimeBy(LONG_PRESS_MS + 100)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(setOf(3), vm.state.selectedRows)
    }

    /**
     * The list is shorter than the window, so there is nowhere to scroll - and
     * a tap-vs-scroll rule that compares scroll positions reads every swipe
     * across it as a still finger, acting on the row the finger started on.
     * Two swipes then read as a double tap and start playing.
     */
    @Test
    fun `a swipe over a short playlist fires nothing`() {
        // fewer rows than the window shows: max scroll 0
        vm.playlistOps.removeDead(
            vm.state.playlist
                .drop(2)
                .map { it.id }
                .toSet(),
        )
        compose.waitForIdle()
        assertEquals(2, vm.state.playlist.size)

        repeat(2) {
            compose.onRoot().performTouchInput {
                down(Offset(100f * SCALE, rowCenterY(0) * SCALE))
                moveTo(Offset(100f * SCALE, (rowCenterY(0) + 2 * Dest.PL_ROW_H) * SCALE))
                moveTo(Offset(100f * SCALE, (rowCenterY(0) + 3 * Dest.PL_ROW_H) * SCALE))
                up()
            }
            compose.waitForIdle()
        }

        assertEquals(Transport.Stopped, vm.state.transport)
    }

    @Test
    fun `rem misc drops dead files and spares tracks it cannot check`() {
        // route a dead file through the backend (addAudio) - injecting it only
        // into the render mirror would be swallowed by StateFlow dedup when
        // the scan writes back an unchanged queue
        vm.playlistFiles.addAudio(android.net.Uri.parse("file:///nowhere/x.mp3"))
        pumpUntil { vm.state.playlist.size == 11 }

        compose.onRoot().performTouchInput { tapVirtual(54f, 95f) } // open REM
        compose.onRoot().performTouchInput { tapVirtual(54f, 41f) } // REM MISC, which webamp puts at the top

        // scan hops Main -> IO -> Main; drive the Robolectric looper by hand
        pumpUntil { vm.state.playlist.size == 10 }
        assertEquals(true, vm.state.playlist.none { it.uri?.contains("nowhere") == true })
        assertEquals(null, vm.state.openMenu)
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            Thread.sleep(10)
        }
        assertEquals(true, condition())
    }

    @Test
    fun `crop keeps only the selection`() {
        compose.onRoot().performTouchInput { tapVirtual(100f, rowCenterY(1)) } // select row 1
        compose.onRoot().performTouchInput { tapVirtual(54f, 95f) } // open REM
        compose.onRoot().performTouchInput { tapVirtual(54f, 77f) } // CROP, third down
        assertEquals(1, vm.state.playlist.size)
        assertEquals("Neon Cassette", vm.state.playlist[0].artist)
    }

    @Test
    fun `the LIST menu asks for a document to save the playlist to`() {
        compose.onRoot().performTouchInput { tapVirtual(242f, 95f) } // open LIST
        compose.onRoot().performTouchInput { tapVirtual(242f, 77f) } // middle entry = SAVE LIST

        assertEquals(1, saveRequests)
        assertEquals(null, vm.state.openMenu)
    }

    @Test
    fun `the LIST menu asks for a playlist to load`() {
        compose.onRoot().performTouchInput { tapVirtual(242f, 95f) } // open LIST
        compose.onRoot().performTouchInput { tapVirtual(242f, 95f) } // bottom entry = LOAD LIST

        assertEquals(1, loadRequests)
    }

    @Test
    fun `ADD DIR asks for a folder`() {
        compose.onRoot().performTouchInput { tapVirtual(25f, 95f) } // open ADD
        compose.onRoot().performTouchInput { tapVirtual(25f, 77f) } // middle entry = ADD DIR

        assertEquals(1, dirRequests)
        assertEquals(null, vm.state.openMenu)
    }

    @Test
    fun `a tap on the empty area below the last row deselects`() {
        // shrink the queue to two rows so the window has a visible void
        vm.state.selectedRows = setOf(0, 1)
        vm.playlistOps.crop()
        compose.waitForIdle()
        assertEquals(2, vm.state.playlist.size)

        compose.onRoot().performTouchInput { tapVirtual(100f, rowCenterY(0)) }
        assertEquals(setOf(0), vm.state.selectedRows)

        compose.onRoot().performTouchInput { tapVirtual(100f, rowCenterY(3)) }

        assertEquals(emptySet<Int>(), vm.state.selectedRows)
    }

    @Test
    fun `ADD URL opens the stream-url prompt`() {
        compose.onRoot().performTouchInput { tapVirtual(25f, 95f) } // open ADD
        compose.onRoot().performTouchInput { tapVirtual(25f, 59f) } // top entry = ADD URL

        assertEquals("Add URL", vm.state.namePrompt?.title)
        assertEquals(null, vm.state.openMenu)
    }
}
