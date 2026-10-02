// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.InMemoryEqPresetStore
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.LibraryAccessHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pointer gestures through the library window land on the right widget and follow
 * select-then-act. Guards against a swipe over a list too short to scroll being read as a
 * tap on the row under it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // skin decode goes through BitmapFactory
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class LibraryInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel
    private lateinit var frame: WindowFrame
    private lateinit var layout: LibraryLayout

    /** Enough artists to outgrow the window, one album with three tracks. */
    private class Shelf : BrowseSource {
        override val capabilities = BrowseCapabilities()
        override val available = true

        val artists = (0 until 40).map { LibraryArtist("a$it", "Artist $it", albumCount = 1) }

        override suspend fun artists() = artists

        override suspend fun albums(artistId: String?) =
            listOf(LibraryAlbum("l-$artistId", "Album of $artistId", "?", year = 1998))

        override suspend fun tracks(albumId: String) =
            (0 until 3).map { Track("$albumId-t$it", "Someone", "Song $it", 60_000, uri = "content://$it") }
    }

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(app)
        frame = frameFor(skin)
        vm =
            WinampViewModel(
                app,
                createBackend = { scope -> MockBackend(FakeTracks.tracks, scope) },
                presetStore = InMemoryEqPresetStore(),
                browseSource = { Shelf() },
            )
        vm.libraryOps.open()

        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                LibraryWindow(
                    vm,
                    skin,
                    SCALE,
                    LibraryAccessHandle(LibraryAccess.GRANTED, request = {}),
                    Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
        layout = windowLayout()
    }

    /** The layout the window computed for the test container's height. */
    private fun windowLayout(): LibraryLayout {
        val root = compose.onRoot().fetchSemanticsNode().size
        return LibraryLayout.forAvailableHeight(root.height / SCALE, frame)
    }

    private fun devicePoint(
        x: Float,
        y: Float,
    ): Offset {
        val root = compose.onRoot().fetchSemanticsNode().size
        val originX = (root.width - LibraryLayout.WIDTH * SCALE) / 2f
        val originY = (root.height - layout.height(frame) * SCALE) / 2f
        return Offset(originX + x * SCALE, originY + y * SCALE)
    }

    private fun tapWindow(
        x: Float,
        y: Float,
    ) = compose.onRoot().performTouchInput {
        down(devicePoint(x, y))
        up()
    }

    private fun dragWindow(
        x: Float,
        fromY: Float,
        toY: Float,
    ) = compose.onRoot().performTouchInput {
        down(devicePoint(x, fromY))
        moveTo(devicePoint(x, (fromY + toY) / 2f))
        moveTo(devicePoint(x, toY))
        up()
    }

    private fun rowCenterY(row: Int) =
        frame.titleH + LibraryLayout.ROWS_TOP + row * LibraryLayout.ROW_H + LibraryLayout.ROW_H / 2f

    private fun rowX() = frame.leftW + 40f

    private fun headerY() = frame.titleH + LibraryLayout.HEADER_TOP + LibraryLayout.BAR_H / 2f

    private fun tabsY() = frame.titleH + LibraryLayout.BAR_H / 2f

    private fun barY() = frame.titleH + layout.barTop + LibraryLayout.BAR_H / 2f

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun drillToTracks() {
        tapWindow(rowX(), rowCenterY(0))
        settle()
        tapWindow(rowX(), rowCenterY(0))
        settle()
        assertTrue("the fixture reaches the track level", vm.libraryOps.atTracks)
    }

    @Test
    fun `tapping an artist drills into their albums`() {
        tapWindow(rowX(), rowCenterY(2))
        settle()

        assertEquals(1, vm.libraryOps.depth)
        assertEquals("Artist 2", vm.libraryOps.header)
    }

    @Test
    fun `a swipe over a short list scrolls nothing and fires nothing`() {
        drillToTracks() // three rows: nowhere to scroll
        val queueBefore = vm.facadeQueueTitles()

        dragWindow(rowX(), rowCenterY(0), rowCenterY(0) + 6 * LibraryLayout.ROW_H)
        settle()

        assertEquals(Transport.Stopped, vm.transport())
        assertEquals(queueBefore, vm.facadeQueueTitles())
        assertEquals("the swipe selects no row", -1, vm.state.librarySelected)
    }

    @Test
    fun `a track tap selects and the second tap replaces the queue and plays`() {
        drillToTracks()

        tapWindow(rowX(), rowCenterY(1))
        settle()
        assertEquals(1, vm.state.librarySelected)
        assertEquals(Transport.Stopped, vm.transport())

        tapWindow(rowX(), rowCenterY(1))
        settle()
        assertEquals(Transport.Playing, vm.transport())
        assertEquals(listOf("Song 0", "Song 1", "Song 2"), vm.facadeQueueTitles())
    }

    @Test
    fun `ADD appends the album and playback stays stopped`() {
        val before = vm.facadeQueueTitles()
        drillToTracks()

        tapWindow(layout.listWidth(frame) + frame.leftW - LibraryLayout.ADD_CELL_W / 2f, barY())
        settle()

        assertEquals(before + listOf("Song 0", "Song 1", "Song 2"), vm.facadeQueueTitles())
        assertEquals(Transport.Stopped, vm.transport())
    }

    @Test
    fun `a drag over the artists scrolls without acting`() {
        dragWindow(rowX(), rowCenterY(6), rowCenterY(1))
        settle()

        assertTrue("the drag scrolls the list: ${vm.state.libraryScroll}", vm.state.libraryScroll > 0)
        assertEquals("the drag stays at the artist level", 0, vm.libraryOps.depth)
    }

    @Test
    fun `the scrollbar jumps the list proportionally`() {
        val listRight = frame.leftW + layout.listWidth(frame)
        compose.onRoot().performTouchInput {
            down(devicePoint(listRight - 4f, rowCenterY(0)))
            moveTo(devicePoint(listRight - 4f, frame.titleH + layout.rowsBottom - 2f))
            up()
        }
        settle()

        val maxScroll = 40 - layout.visibleRows
        assertEquals(maxScroll, vm.state.libraryScroll)
    }

    @Test
    fun `a tab tap switches the category from any depth`() {
        drillToTracks()

        // the ALBUMS tab is the second cell of four
        val cells = LibraryLayout.tabCells(layout.listWidth(frame), vm.libraryOps.visibleCategories.size)
        val albumsCell = cells[1]
        tapWindow(frame.leftW + (albumsCell.start + albumsCell.end) / 2f, tabsY())
        settle()

        assertEquals(nl.mattix.andamp.state.LibraryOps.Category.ALBUMS, vm.libraryOps.category)
        assertEquals(0, vm.libraryOps.depth)
        assertEquals("ALBUMS", vm.libraryOps.header)
    }

    @Test
    fun `the header goes up one level and restores the place it left`() {
        vm.state.libraryScroll = 10
        compose.waitForIdle()
        tapWindow(rowX(), rowCenterY(0)) // drill into Artist 10
        settle()
        assertEquals(1, vm.libraryOps.depth)

        tapWindow(rowX(), headerY())
        settle()

        assertEquals(0, vm.libraryOps.depth)
        assertEquals(10, vm.state.libraryScroll)
    }
}

private fun WinampViewModel.facadeQueueTitles() = state.playlist.map { it.title }

private fun WinampViewModel.transport() = state.transport
