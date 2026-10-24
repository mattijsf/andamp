// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.PowerManager
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.LibraryAccessHandle
import nl.mattix.andamp.ui.PlayerSurface
import nl.mattix.andamp.ui.menu.AmpModals
import nl.mattix.andamp.ui.menu.menuAnchorBounds
import nl.mattix.andamp.ui.window.BIG_WINDOW
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import nl.mattix.andamp.ui.window.PlaylistMenuActions
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * A modern skin's layer on a player surface, against a skin with no engine behind it.
 *
 * The screen is 900 by 1600 pixels at three to a virtual pixel, with no insets, so the box the
 * skin is fitted into is 900 by 1599 from the top. The stack is 300 by 100 skin pixels at twice
 * the size, centered: 600 by 200 from x = 150.
 *
 * The clock is driven by hand, because the skin's clock asks for a frame every frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class WalLayerTest {
    @get:Rule
    val compose = createComposeRule()

    private val surface = FakeWalSurface().apply { layoutFor = stackOf(300, 100, 2f) }
    private val state = WinampState()
    private val owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
    private val box = IntRect(0, 0, 900, 1599)

    /** [n] frames; a write the test made is seen first, as the app's snapshot manager makes it seen. */
    private fun frames(n: Int = FRAMES) =
        repeat(n) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }

    /** The screen, under any popup a menu opened. */
    private fun screen() = compose.onNodeWithTag(SCREEN)

    /** Draws the screen once, as the next frame on a phone would. */
    private fun drawn() {
        val view = (screen().fetchSemanticsNode().root as ViewRootForTest).view
        compose.runOnUiThread { view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888))) }
    }

    /** One layer on the whole screen, with the app's menus drawn; the returned call takes it away. */
    private fun layer(parts: WalLayerParts = partsFor(surface, state)): () -> Unit {
        var shown by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.fillMaxSize().testTag(SCREEN)) {
                    if (shown) WalLayer(parts, state, SCALE, Modifier.fillMaxSize()) {}
                    AmpModals(state, anchorBounds = { menuAnchorBounds(it, SCALE, 900, 1600, state.windowRects) })
                }
            }
        }
        frames()
        return {
            shown = false
            frames()
        }
    }

    private fun placed() = WalPlacement.of(box, surface.layout!!, SCALE)

    /** Past the wait a changed rect has for the stack to hold still while the skin animates. */
    private fun settled() {
        compose.mainClock.advanceTimeBy(WAL_RECT_SETTLE_MS)
        frames()
    }

    /** [anchor] pops a menu at [press], a point on the screen, to within half a virtual pixel. */
    private fun assertPopsAt(
        message: String,
        press: Offset,
        anchor: MenuAnchor?,
    ) {
        val at = menuAnchorBounds(anchor, SCALE, 900, 1600, state.windowRects).topLeft
        assertTrue(
            "$message: $at, not $press",
            kotlin.math.abs(at.x - press.x) <= SCALE / 2f && kotlin.math.abs(at.y - press.y) <= SCALE / 2f,
        )
    }

    @Test
    fun `the layer posts the box it shows the skin in, and publishes the stack's rect`() {
        layer()

        assertEquals(listOf(WalFit(900, 1599, 1f, sideways = false)), surface.fits)
        assertEquals(IntRect(150, 0, 750, 200), placed().stack)
        assertEquals(placed().window, state.windowRects[WindowStore.MAIN])
        assertEquals(IntRect(50, 0, 250, 67), state.windowRects[WindowStore.MAIN])
    }

    @Test
    fun `the rect goes when the layer goes`() {
        val hide = layer()
        assertNotNull(state.windowRects[WindowStore.MAIN])

        hide()

        assertNull(state.windowRects[WindowStore.MAIN])
    }

    @Test
    fun `the layer going after another surface published its rect leaves that rect`() {
        val second = TestOwner().apply { registry.currentState = Lifecycle.State.STARTED }
        var firstShown by mutableStateOf(true)
        var secondShown by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        val first = partsFor(surface, state)
        val other = partsFor(surface, state)
        // two surfaces of the same size, so their rects are equal and only whose they are tells them apart
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                if (firstShown) {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        WalLayer(first, state, SCALE, Modifier.fillMaxSize()) {}
                    }
                }
                if (secondShown) {
                    CompositionLocalProvider(LocalLifecycleOwner provides second) {
                        WalLayer(other, state, SCALE, Modifier.fillMaxSize()) {}
                    }
                }
            }
        }
        frames()
        val firstRect = state.windowRects[WindowStore.MAIN]!!

        owner.registry.currentState = Lifecycle.State.STARTED
        second.registry.currentState = Lifecycle.State.RESUMED
        frames()
        assertSame("the second surface holds the turn", other.actions, surface.sink)
        assertEquals(firstRect, state.windowRects[WindowStore.MAIN])

        firstShown = false
        frames()

        assertEquals("the rect is the second surface's, and stays", firstRect, state.windowRects[WindowStore.MAIN])

        // the second going takes it away
        secondShown = false
        frames()
        assertNull(state.windowRects[WindowStore.MAIN])
    }

    @Test
    fun `a touch beside the stack reaches nothing`() {
        layer()

        screen().performTouchInput {
            down(Offset(100f, 100f))
            up()
        }
        screen().performTouchInput {
            down(Offset(450f, 400f))
            up()
        }
        frames()

        assertEquals(emptyList<String>(), surface.pointerCalls)
    }

    @Test
    fun `a tap on the stack reaches the skin in skin pixels`() {
        val parts = partsFor(surface, state)
        layer(parts)

        screen().performTouchInput {
            down(Offset(450f, 100f))
            up()
        }
        frames()

        assertEquals(listOf("down 150,50", "up 150,50"), surface.pointerCalls)
        assertPopsAt("a menu the skin opens pops at the press", Offset(450f, 100f), parts.actions.pressAt?.invoke())
    }

    @Test
    fun `a skin that animates on and on still publishes its rect`() {
        surface.layoutFor = { fit -> stackOf(300, 100, 2f)(fit).copy(animating = true) }

        layer()

        assertEquals(placed().window, state.windowRects[WindowStore.MAIN])
        assertEquals(IntRect(50, 0, 250, 67), state.windowRects[WindowStore.MAIN])
    }

    @Test
    fun `while the skin animates on, the rect follows a stack that changed size once it holds still`() {
        surface.layoutFor = { fit -> stackOf(300, 100, 2f)(fit).copy(animating = true) }
        layer()
        val before = state.windowRects[WindowStore.MAIN]

        // a drawer slides open below the main window, a few skin pixels a frame
        for (stackH in 110..150 step 10) {
            surface.layout = surface.layout!!.copy(stackH = stackH)
            frames(1)
            assertEquals("mid-slide the rect stays", before, state.windowRects[WindowStore.MAIN])
        }
        settled()

        assertEquals(IntRect(50, 0, 250, 100), placed().window)
        assertEquals("held still, the rect follows", placed().window, state.windowRects[WindowStore.MAIN])
    }

    @Test
    fun `a drawer that stops sliding moves the rect as it stops`() {
        layer()
        val before = state.windowRects[WindowStore.MAIN]

        surface.layout = surface.layout!!.copy(stackH = 120, animating = true)
        frames(2)
        assertEquals("mid-slide the rect stays", before, state.windowRects[WindowStore.MAIN])

        surface.layout = surface.layout!!.copy(stackH = 150, animating = false)
        frames(2)
        assertEquals(placed().window, state.windowRects[WindowStore.MAIN])

        // a slide whose last step comes before the skin says it stopped
        surface.layout = surface.layout!!.copy(stackH = 180, animating = true)
        frames(2)
        assertEquals(IntRect(50, 0, 250, 100), state.windowRects[WindowStore.MAIN])
        surface.layout = surface.layout!!.copy(animating = false)
        frames(2)
        assertEquals(IntRect(50, 0, 250, 120), state.windowRects[WindowStore.MAIN])
    }

    @Test
    fun `a press while the rect waits for a sliding stack still pops menus at the finger`() {
        val parts = partsFor(surface, state)
        layer(parts)
        // the stack slides 30 pixels down while the skin animates, so the rect stays where it was
        surface.layout = surface.layout!!.copy(top = 30f, animating = true)
        frames(2)
        val held = IntRect(50, 0, 250, 67)
        assertEquals(held, state.windowRects[WindowStore.MAIN])

        val press = Offset(450f, 130f)
        screen().performTouchInput {
            down(press)
            up()
        }
        frames(1)

        assertEquals("the skin heard it where the stack is now", listOf("down 150,50", "up 150,50"), surface.pointerCalls)
        assertEquals(held, state.windowRects[WindowStore.MAIN])
        assertPopsAt("a menu the skin opens now pops at the finger", press, parts.actions.pressAt?.invoke())

        settled()
        assertEquals(IntRect(50, 10, 250, 77), state.windowRects[WindowStore.MAIN])
        assertPopsAt("and so does one that opens after the rect followed", press, parts.actions.pressAt?.invoke())
    }

    @Test
    fun `a skin menu that opens while the finger is down keeps the long press from firing`() {
        layer()

        screen().performTouchInput { down(Offset(450f, 100f)) }
        frames(1)
        // the engine pops the skin's menu for the press
        surface.menu = skinMenuOf("Play")
        frames(1)
        compose.mainClock.advanceTimeBy(ViewConfiguration.getLongPressTimeout() + 50L)

        assertEquals("the skin's menu stays up", SKIN_MENU, state.activeMenu?.title)
        assertEquals("and waits for its answer", emptyList<Any>(), surface.answers)
        assertEquals("the skin's press goes on", listOf("down 150,50"), surface.pointerCalls)
    }

    @Test
    fun `a long press opens the main menu while the finger is down, and the skin hears no release`() {
        layer()

        screen().performTouchInput { down(Offset(450f, 100f)) }
        frames(1)
        compose.mainClock.advanceTimeBy(ViewConfiguration.getLongPressTimeout() + 50L)

        val menu = state.activeMenu
        assertEquals(MAIN_MENU, menu?.title)
        assertPopsAt("it pops at the press", Offset(450f, 100f), menu?.anchor)
        assertEquals(listOf("down 150,50", "cancel"), surface.pointerCalls)

        screen().performTouchInput {
            moveTo(Offset(470f, 100f))
            up()
        }
        frames()

        assertEquals("the rest of the finger reaches nothing", listOf("down 150,50", "cancel"), surface.pointerCalls)
        assertSame(menu, state.activeMenu)
    }

    @Test
    fun `Skin menu leads the main menu only for a press the engine found a skin menu under, and right-clicks it`() {
        layer()
        surface.menuUnderEveryPress = true

        screen().performTouchInput { down(Offset(450f, 100f)) }
        frames(1)
        compose.mainClock.advanceTimeBy(ViewConfiguration.getLongPressTimeout() + 50L)
        screen().performTouchInput { up() }
        frames()

        compose.onNodeWithText(SKIN_MENU).performClick()
        frames()

        assertEquals("the gesture of that press", listOf(1), surface.rightClicks)
        assertNull("the main menu closed", state.activeMenu)
    }

    @Test
    fun `a press the engine found no skin menu under gets no Skin menu`() {
        layer()
        // the engine names another press than this one
        surface.layout = surface.layout!!.copy(menuTarget = 99)

        screen().performTouchInput { down(Offset(450f, 100f)) }
        frames(1)
        compose.mainClock.advanceTimeBy(ViewConfiguration.getLongPressTimeout() + 50L)
        screen().performTouchInput { up() }
        frames()

        assertEquals(MAIN_MENU, state.activeMenu?.title)
        compose.onNodeWithText(PREFERENCES).assertExists()
        compose.onNodeWithText(SKIN_MENU).assertDoesNotExist()
    }

    @Test
    fun `a cancelled gesture and a layer that goes mid-press each cancel the skin's press`() {
        val hide = layer()

        screen().performTouchInput {
            down(Offset(450f, 100f))
            cancel()
        }
        frames()
        assertEquals(listOf("down 150,50", "cancel"), surface.pointerCalls)

        screen().performTouchInput { down(Offset(450f, 100f)) }
        frames(1)
        hide()

        assertEquals(listOf("down 150,50", "cancel", "down 150,50", "cancel"), surface.pointerCalls)
    }

    @Test
    fun `only the resumed one of two surfaces ticks, holds the sink and shows menus, and hands all of it over`() {
        val second = TestOwner().apply { registry.currentState = Lifecycle.State.STARTED }
        val otherState = WinampState()
        val first = partsFor(surface, state)
        val other = partsFor(surface, otherState)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    WalLayer(first, state, SCALE, Modifier.fillMaxWidth().size(900.dp, 800.dp)) {}
                }
                CompositionLocalProvider(LocalLifecycleOwner provides second) {
                    WalLayer(other, otherState, SCALE, Modifier.fillMaxWidth().size(900.dp, 800.dp)) {}
                }
            }
        }
        frames()
        surface.menu = skinMenuOf("Play")
        frames()

        assertSame(first.actions, surface.sink)
        assertNotNull(state.activeMenu)
        assertNull(otherState.activeMenu)
        assertNotNull(state.windowRects[WindowStore.MAIN])
        assertNull(otherState.windowRects[WindowStore.MAIN])
        assertNear("one tick a frame", FRAMES, ticksOver(FRAMES))

        owner.registry.currentState = Lifecycle.State.STARTED
        second.registry.currentState = Lifecycle.State.RESUMED
        frames()

        assertSame(other.actions, surface.sink)
        assertNull("the menu left with the turn", state.activeMenu)
        assertNotNull("the waiting menu shows on the surface that took the turn", otherState.activeMenu)
        assertNotNull(otherState.windowRects[WindowStore.MAIN])
        assertNear("still one tick a frame", FRAMES, ticksOver(FRAMES))
        assertEquals("the menu was not answered by the handover", emptyList<Any>(), surface.answers)
    }

    private fun ticksOver(n: Int): Int {
        val before = surface.ticks
        frames(n)
        return surface.ticks - before
    }

    /** [actual] is [expected], give or take the frame a handover or a start falls in. */
    private fun assertNear(
        message: String,
        expected: Int,
        actual: Int,
    ) = assertTrue("$message: $actual, not $expected", kotlin.math.abs(actual - expected) <= 1)

    @Test
    fun `a covered player that is not pulled aside gets no ticks and no wakes`() {
        state.playerCovered = true
        layer()

        surface.shown++
        frames()
        surface.shown++
        frames()

        assertEquals(0, surface.ticks)

        // the back gesture pulls the screen aside, and the player shows again
        state.screenPulled = true
        frames()
        assertTrue(surface.ticks > 0)
    }

    @Test
    fun `with the screen off the skin gets no ticks and no wakes`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        power.setIsInteractive(false)
        layer()

        surface.shown++
        frames()

        assertEquals(0, surface.ticks)

        power.setIsInteractive(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        frames()
        assertTrue(surface.ticks > 0)
    }

    @Test
    fun `a new box while resumed is posted, and the rect moves with the stack`() {
        var width by mutableStateOf(600)
        val parts = partsFor(surface, state)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.size(width.dp, 1600.dp)) {
                    WalLayer(parts, state, SCALE, Modifier.fillMaxSize()) {}
                }
            }
        }
        frames()
        // the stack is 600 wide: at the left of a box 600 wide
        assertEquals(IntRect(0, 0, 200, 67), state.windowRects[WindowStore.MAIN])

        width = 900
        frames()

        assertEquals(listOf(WalFit(600, 1599, 1f, false), WalFit(900, 1599, 1f, false)), surface.fits)
        // and from x = 150 in a box 900 wide
        assertEquals(IntRect(50, 0, 250, 67), state.windowRects[WindowStore.MAIN])
    }

    @Test
    fun `a debug build logs each place the stack is drawn at, once`() {
        ShadowLog.clear()
        var width by mutableStateOf(600)
        val parts = partsFor(surface, state)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.size(width.dp, 1600.dp)) {
                    WalLayer(parts, state, SCALE, Modifier.fillMaxSize()) {}
                }
            }
        }
        frames()
        width = 900
        frames()
        frames()

        assertEquals(
            listOf(
                "placed at 2.000x in 600x1599 upright: stack 600x200 at 0,0",
                "placed at 2.000x in 900x1599 upright: stack 600x200 at 150,0",
            ),
            ShadowLog.getLogsForTag(WalEngine.TIMING_TAG).map { it.msg },
        )
        assertEquals(
            "placed at 2.500x in 2000x900 sideways: stack 100x50 at 10,20",
            placedLine(
                WalPlacement(IntRect(10, 20, 110, 70), IntRect(3, 6, 37, 24), 2.5f, SCALE),
                WalFit(2000, 900, 2.625f, true),
            ),
        )
    }

    @Test
    fun `a layout for another box keeps the stack where it was, and the rect where it was`() {
        layer()
        val before = state.windowRects[WindowStore.MAIN]
        // the engine has not caught up with a box this layer never showed
        surface.layoutFor = null
        surface.layout = surface.layout!!.copy(fit = WalFit(1, 1, 1f, false), left = 0f)
        frames()

        screen().performTouchInput {
            down(Offset(450f, 100f))
            up()
        }
        frames()

        assertEquals(before, state.windowRects[WindowStore.MAIN])
        assertEquals("touches still map with the last placement", listOf("down 150,50", "up 150,50"), surface.pointerCalls)
    }

    @Test
    fun `a draw that throws draws nothing more of that frame, reports it once and does not crash`() {
        surface.drawThrows = IllegalStateException("broken frame")
        layer()
        drawn()
        assertEquals("the frame was drawn and threw", 1, surface.draws)
        assertEquals(1, surface.drawFailures.size)

        // the stack moves and narrows, so the layer draws again, still on the same frame
        surface.layout = surface.layout!!.copy(left = 140f, stackW = 280)
        frames()
        drawn()

        assertEquals(1, surface.drawFailures.size)
        assertEquals("the broken frame is not drawn again", 1, surface.draws)

        surface.frames.intValue++
        frames()
        drawn()
        assertEquals("a new frame is tried, and its failure counts again", 2, surface.drawFailures.size)
    }

    @Test
    fun `the layer going with a menu open closes it without the listener and saves`() {
        val hide = layer()
        surface.menu = skinMenuOf("Play")
        frames()
        assertNotNull(state.activeMenu)

        hide()

        assertEquals(listOf("dismiss", "save"), surface.calls.filter { it == "dismiss" || it == "save" })
        assertNull(state.activeMenu)
    }

    @Test
    fun `while the skin has no layout the classic main window stands in, publishes its rect and long-presses to the main menu`() {
        val vm = testViewModel()
        vm.welcome.seen = true
        val skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext<Application>())
        val waiting = FakeWalSurface()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag(SCREEN)) {
                PlayerSurface(
                    vm,
                    skin,
                    PlaylistMenuActions(),
                    LibraryAccessHandle(LibraryAccess.GRANTED, request = {}),
                    onPickSkin = {},
                    onPreferences = {},
                    onMuseum = {},
                    onExit = {},
                    onMinimize = {},
                    onOverlaySettings = {},
                    wal = waiting,
                )
            }
        }
        frames()

        assertTrue("the engine was asked to start", waiting.fits.isNotEmpty())
        val main = vm.state.windowRects[WindowStore.MAIN]
        assertEquals(IntRect((300 - MAIN_W) / 2, 0, (300 - MAIN_W) / 2 + MAIN_W, MAIN_H), main)

        // the classic window's title bar, between its buttons
        screen().performTouchInput { down(Offset((main!!.left + 150f) * SCALE, (main.top + 7f) * SCALE)) }
        frames(1)
        compose.mainClock.advanceTimeBy(700)
        screen().performTouchInput { up() }
        frames()

        assertEquals(vm.words.get(nl.mattix.andamp.R.string.menu_winamp), vm.state.activeMenu?.title)
        assertEquals("the skin heard none of it", emptyList<String>(), waiting.pointerCalls)
    }

    private companion object {
        const val SCALE = 3
        const val FRAMES = 6
        const val SCREEN = "screen"
    }
}
