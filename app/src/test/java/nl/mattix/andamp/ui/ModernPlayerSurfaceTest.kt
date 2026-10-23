// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntRect
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.R
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.menu.menuAnchorBounds
import nl.mattix.andamp.ui.window.BIG_WINDOW
import nl.mattix.andamp.ui.window.PlaylistMenuActions
import nl.mattix.andamp.ui.window.testViewModel
import nl.mattix.andamp.wal.FakeWalSurface
import nl.mattix.andamp.wal.WalPlacement
import nl.mattix.andamp.wal.WalSurface
import nl.mattix.andamp.wal.stackOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The player with a modern skin worn, against a skin with no engine behind it: the skin stands in
 * for the main window, the equalizer and the playlist, and the classic skin still draws the rest.
 *
 * The screen is 900 by 1600 pixels at three to a virtual pixel, with no insets, so the skin is
 * fitted into 900 by 1599 from the top. The clock is driven by hand, because the skin's clock asks
 * for a frame every frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the classic skin decodes through BitmapFactory
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class ModernPlayerSurfaceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel
    private val surface = FakeWalSurface().apply { layoutFor = stackOf(300, 100, 2f) }
    private val box = IntRect(0, 0, 900, 1599)

    /** What the playlist's pickers were asked for. */
    private val picked = mutableListOf<String>()

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext<Application>())
        vm = testViewModel()
        vm.welcome.seen = true
    }

    private fun frames(n: Int = FRAMES) =
        repeat(n) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }

    private fun show(wal: WalSurface? = surface) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag(SCREEN)) {
                PlayerSurface(
                    vm,
                    skin,
                    PlaylistMenuActions(addFile = { picked += "file" }, addFileThenPlay = { picked += "file to play" }),
                    LibraryAccessHandle(LibraryAccess.GRANTED, request = {}),
                    onPickSkin = {},
                    onPreferences = {},
                    onMuseum = {},
                    onExit = {},
                    onMinimize = {},
                    onOverlaySettings = {},
                    wal = wal,
                )
            }
        }
        frames()
    }

    private fun screen() = compose.onNodeWithTag(SCREEN)

    private fun placed() = WalPlacement.of(box, surface.layout!!, 3)

    /** [anchor] pops a menu at [press], a point on the screen, to within half a virtual pixel. */
    private fun assertPopsAt(
        message: String,
        press: Offset,
        anchor: MenuAnchor?,
    ) {
        val at = menuAnchorBounds(anchor, 3, 900, 1600, vm.state.windowRects).topLeft
        assertTrue(
            "$message: $at, not $press",
            kotlin.math.abs(at.x - press.x) <= 1.5f && kotlin.math.abs(at.y - press.y) <= 1.5f,
        )
    }

    private fun longPress(at: Offset) {
        screen().performTouchInput { down(at) }
        frames(1)
        compose.mainClock.advanceTimeBy(android.view.ViewConfiguration.getLongPressTimeout() + 50L)
        screen().performTouchInput { up() }
        frames()
    }

    @Test
    fun `the skin stands in for the main window, the equalizer and the playlist`() {
        vm.state.eqVisible = true
        vm.state.plVisible = true

        show()

        assertEquals("the rect is the skin's stack", placed().window, vm.state.windowRects[WindowStore.MAIN])
        assertNull(vm.state.windowRects[WindowStore.EQ])
        assertNull(vm.state.windowRects[WindowStore.PLAYLIST])
        assertTrue("the windows stay open for the classic skin", vm.state.eqVisible && vm.state.plVisible)
    }

    @Test
    fun `the library opens over the skin and takes the touches inside it`() {
        // the stack fills the whole box, so the library is over it wherever it opens
        surface.layoutFor = stackOf(450, 799, 2f)
        show()
        vm.state.setWindowOpen(WindowStore.LIBRARY, true) { vm.state.libraryOpen = it }
        frames()
        val library = vm.state.windowRects[WindowStore.LIBRARY]!!
        val inLibrary = IntRect(library.left * 3, library.top * 3, library.right * 3, library.bottom * 3)

        screen().performTouchInput {
            down(Offset(inLibrary.center.x.toFloat(), inLibrary.center.y.toFloat()))
            up()
        }
        frames()
        assertEquals("the skin under the library hears nothing", emptyList<String>(), surface.pointerCalls)

        val beside =
            listOf(Offset(10f, 10f), Offset(890f, 10f), Offset(10f, 1590f), Offset(890f, 1590f)).first {
                !inLibrary.contains(
                    androidx.compose.ui.unit
                        .IntOffset(it.x.toInt(), it.y.toInt()),
                )
            }
        screen().performTouchInput {
            down(beside)
            up()
        }
        frames()
        assertEquals("beside the library the skin hears it", "down", surface.pointerCalls.first().substringBefore(' '))
    }

    @Test
    fun `a long press opens the main menu at the press, led by Skin menu, with the classic windows grayed`() {
        surface.menuUnderEveryPress = true
        show()
        val press = Offset(450f, 100f)

        longPress(press)

        val menu = vm.state.activeMenu!!
        assertPopsAt("it pops at the press", press, menu.anchor)
        val first = menu.items[0] as AmpMenuItem.Action
        assertEquals(vm.words.get(R.string.menu_skin_menu), first.label)
        assertSame(AmpMenuItem.Divider, menu.items[1])
        for (classic in listOf(R.string.menu_playlist_editor, R.string.menu_equalizer)) {
            val entry = menu.items.filterIsInstance<AmpMenuItem.Action>().first { it.label == vm.words.get(classic) }
            assertFalse("${entry.label} is grayed", entry.enabled)
        }
        assertEquals("the skin's press was cancelled", listOf("down 150,50", "cancel"), surface.pointerCalls)

        compose.onNodeWithText(vm.words.get(R.string.menu_skin_menu)).performClick()
        frames()

        assertEquals(listOf(1), surface.rightClicks)
    }

    @Test
    fun `the skin's menu buttons and file requests reach the player through the sink it installs`() {
        show()
        assertNotNull("the surface holding the turn installed its sink", surface.sink)
        val sink = surface.sink!!

        screen().performTouchInput {
            down(Offset(450f, 100f))
            up()
        }
        frames()
        sink.hostAction("SYSMENU", null)

        val menu = vm.state.activeMenu!!
        assertEquals(vm.words.get(R.string.menu_winamp), menu.title)
        assertPopsAt("it pops at the press", Offset(450f, 100f), menu.anchor)
        assertFalse(
            "no Skin menu from a button",
            menu.items.filterIsInstance<AmpMenuItem.Action>().any { it.label == vm.words.get(R.string.menu_skin_menu) },
        )

        sink.openFile(andPlay = false)
        sink.openFile(andPlay = true)
        assertEquals(listOf("file", "file to play"), picked)
    }

    @Test
    fun `a skin that animates from its first frame on takes over the rect from the classic main window`() {
        val waiting = FakeWalSurface()
        show(waiting)
        val classic = vm.state.windowRects[WindowStore.MAIN]
        assertNotNull("the classic main window stands in", classic)

        // the first frame comes while an animation loops, and it loops on
        waiting.layout = stackOf(300, 100, 2f)(waiting.fits.last()).copy(animating = true)
        waiting.frames.intValue++
        frames()

        val stack = WalPlacement.of(box, waiting.layout!!, 3).window
        assertTrue("the stack's rect is not the classic one", stack != classic)
        assertEquals(stack, vm.state.windowRects[WindowStore.MAIN])
    }

    @Test
    fun `with the skin worn the in-player visualizer does not run`() {
        show()
        vm.play()
        frames(30)

        assertEquals(Transport.Playing, vm.state.transport)
        assertEquals(0L, vm.state.visFrame)
    }

    @Test
    fun `with the classic skin worn the in-player visualizer runs`() {
        show(wal = null)
        vm.play()
        frames(30)

        assertEquals(Transport.Playing, vm.state.transport)
        assertTrue(vm.state.visFrame > 0)
    }

    @Test
    fun `Back closes a skin window over the main one before it leaves the player`() {
        surface.layoutFor = { fit -> stackOf(300, 100, 2f)(fit).copy(windowOpen = true) }
        compose.mainClock.autoAdvance = false
        compose.setContent { WinampScreen(vm, wal = surface) }
        frames()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames()

        assertEquals(1, surface.closedWindows)
    }

    @Test
    fun `Back leaves the skin alone when no skin window is open`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { WinampScreen(vm, wal = surface) }
        frames()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames()

        assertEquals(0, surface.closedWindows)
    }

    private companion object {
        const val FRAMES = 6
        const val SCREEN = "screen"
    }
}
