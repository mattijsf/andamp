// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.menu.AmpModals
import nl.mattix.andamp.ui.window.BIG_WINDOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A modern skin's popup menus, shown as the app's menus and answered back to the skin. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class WalMenusTest {
    @get:Rule
    val compose = createComposeRule()

    private val surface = FakeWalSurface()
    private val state = WinampState()

    /** The skin's menus shown as the turn holder shows them, with the app's menus drawn. */
    private fun show() {
        compose.setContent {
            LaunchedEffect(Unit) { showSkinMenus(surface, state, SKIN_MENU) { PRESS } }
            AmpModals(state, anchorBounds = { IntRect(IntOffset(100, 100), IntSize(1, 1)) })
        }
    }

    private fun pops(menu: SkinMenu) {
        surface.menu = menu
        compose.waitForIdle()
    }

    @Test
    fun `commands, ticks, grayed entries, submenus and separators become the app's entries`() {
        val picked = mutableListOf<Int>()
        var dismissed = 0
        val menu =
            SkinMenu(
                listOf(
                    SkinMenu.Command(1, "Play", checked = false, disabled = false),
                    SkinMenu.Command(2, "Always on top", checked = true, disabled = false),
                    SkinMenu.Separator,
                    SkinMenu.Command(3, "Not now", checked = false, disabled = true),
                    SkinMenu.Submenu(
                        "Themes",
                        SkinMenu(listOf(SkinMenu.Command(7, "Default", checked = true, disabled = false), SkinMenu.Separator)),
                    ),
                ),
            )

        val amp = menu.toAmpMenu(SKIN_MENU, PRESS, onPick = { picked += it }, onDismiss = { dismissed++ })

        assertEquals(SKIN_MENU, amp.title)
        assertSame(PRESS, amp.anchor)
        val items = amp.items
        assertEquals(5, items.size)
        val play = items[0] as AmpMenuItem.Action
        assertEquals("Play" to listOf(true, false), play.label to listOf(play.enabled, play.checked))
        val onTop = items[1] as AmpMenuItem.Action
        assertTrue("ticked as the skin says", onTop.checked)
        assertSame(AmpMenuItem.Divider, items[2])
        val notNow = items[3] as AmpMenuItem.Action
        assertFalse("grayed as the skin says", notNow.enabled)
        val themes = items[4] as AmpMenuItem.Submenu
        assertEquals("Themes", themes.label)
        val default = themes.items[0] as AmpMenuItem.Action
        assertTrue(default.checked)
        assertSame(AmpMenuItem.Divider, themes.items[1])

        play.onClick()
        default.onClick()
        amp.onDismiss!!()
        assertEquals("each command answers with its id, nested or not", listOf(1, 7), picked)
        assertEquals(1, dismissed)
    }

    @Test
    fun `a skin's labels show as it wrote them`() {
        val amp = skinMenuOf("Égaliseur…", "ALWAYS ON TOP").toAmpMenu(SKIN_MENU, null, {}, {})

        assertEquals(listOf("Égaliseur…", "ALWAYS ON TOP"), amp.items.map { (it as AmpMenuItem.Action).label })
    }

    @Test
    fun `a classic switch reads grayed and unticked, and the rest of the menu stays as it was`() {
        val doubleSize = AmpMenuItem.Action("Double Size", checked = true) {}
        val repeat = AmpMenuItem.Action("Repeat", checked = true) {}
        val skins = AmpMenuItem.Submenu("Skins", listOf(AmpMenuItem.Action("Double Size") {}))
        val menu = AmpMenu("Options", listOf(doubleSize, AmpMenuItem.Divider, repeat, skins), null)

        val grayed = menu.grayedAt("Double Size")

        assertEquals(doubleSize.copy(enabled = false, checked = false), grayed.items[0])
        assertEquals(listOf(AmpMenuItem.Divider, repeat, skins), grayed.items.drop(1))
        assertEquals(menu.title, grayed.title)
    }

    @Test
    fun `the menu pops at the press, and a pick answers its id once`() {
        show()
        val menu = skinMenuOf("Play", "Stop")
        pops(menu)

        assertSame(PRESS, state.activeMenu?.anchor)
        compose.onNodeWithText("Stop").performClick()
        compose.waitForIdle()

        assertEquals(listOf(menu to 2), surface.answers)
        assertNull(state.activeMenu)
    }

    @Test
    fun `Back answers 0, and a second menu the skin opens in the same touch still shows`() {
        show()
        val first = skinMenuOf("Play", "Stop")
        val second = skinMenuOf("Second thoughts")
        surface.afterAnswer = { _, _ -> surface.menu = second }
        pops(first)

        compose.runOnUiThread { pressBack(popup()) }
        compose.waitForIdle()

        assertEquals(listOf(first to 0), surface.answers)
        compose.onNodeWithText("Second thoughts").assertIsDisplayed()
        compose.onNodeWithText("Second thoughts").performClick()
        compose.waitForIdle()
        assertEquals(listOf(first to 0, second to 1), surface.answers)
    }

    @Test
    fun `a tap outside answers 0`() {
        show()
        val menu = skinMenuOf("Play")
        pops(menu)

        compose.runOnUiThread { tapOutside(popup()) }
        compose.waitForIdle()

        assertEquals(listOf(menu to 0), surface.answers)
        assertNull(state.activeMenu)
    }

    @Test
    fun `the screen taking its menus down, as on a recreation, answers 0`() {
        show()
        val menu = skinMenuOf("Play")
        pops(menu)

        // what WinampScreen does with a menu left from before a recreation
        state.activeMenu = null
        compose.waitForIdle()

        assertEquals(listOf(menu to 0), surface.answers)
        compose.onNodeWithText("Play").assertDoesNotExist()
    }

    @Test
    fun `a skin menu whose wait ran out leaves no menu up`() {
        show()
        pops(skinMenuOf("Play"))
        assertTrue(state.activeMenu != null)

        // the engine gave up waiting and closed it
        surface.menu = null
        compose.waitForIdle()

        assertNull(state.activeMenu)
        assertEquals("nothing is left to answer", emptyList<Pair<SkinMenu, Int>>(), surface.answers)
        compose.onNodeWithText("Play").assertDoesNotExist()
    }

    @Test
    fun `a skin menu while another menu of the app is up answers 0 and leaves that one up`() {
        show()
        val other = AmpMenu("Equalizer presets", listOf(AmpMenuItem.Action("Load") {}))
        state.activeMenu = other
        compose.waitForIdle()
        val menu = skinMenuOf("Play")

        pops(menu)

        assertEquals(listOf(menu to 0), surface.answers)
        assertSame(other, state.activeMenu)
        compose.onNodeWithText("Load").assertIsDisplayed()
        compose.onNodeWithText("Play").assertDoesNotExist()
    }

    /** The layer itself, resting (covered), so the screen goes idle; its turn shows the menus. */
    private fun layer(owner: TestOwner): () -> Unit {
        var shown by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.fillMaxSize()) {
                    if (shown) WalLayer(partsFor(surface, state, covered = { true }), state, 3, Modifier.fillMaxSize()) {}
                    AmpModals(state, anchorBounds = { IntRect(IntOffset(100, 100), IntSize(1, 1)) })
                }
            }
        }
        compose.waitForIdle()
        return { shown = false }
    }

    @Test
    fun `the layer going away closes the menu without the listener and saves`() {
        val owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val hide = layer(owner)
        pops(skinMenuOf("Play"))
        assertTrue(state.activeMenu != null)

        hide()
        compose.waitForIdle()

        assertEquals(listOf("dismiss", "save"), surface.calls.filter { it == "dismiss" || it == "save" })
        assertEquals("the listener did not answer", emptyList<Pair<SkinMenu, Int>>(), surface.answers)
        assertNull(state.activeMenu)
        compose.onNodeWithText("Play").assertDoesNotExist()
    }

    @Test
    fun `the surface stopping closes the menu and saves, and none is up on return`() {
        val owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        layer(owner)
        pops(skinMenuOf("Play"))

        owner.registry.currentState = Lifecycle.State.CREATED
        compose.waitForIdle()

        assertEquals(1, surface.dismissals)
        assertEquals(1, surface.saves)
        assertNull(state.activeMenu)

        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.waitForIdle()

        assertNull(state.activeMenu)
        compose.onNodeWithText("Play").assertDoesNotExist()
    }

    /** The newest popup window, as the window manager holds it. */
    private fun popup(): View {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val manager = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(manager) as List<*>
        return views.filterIsInstance<View>().last { it.javaClass.name == "androidx.compose.ui.window.PopupLayout" }
    }

    /** A press above and left of the popup, as the window manager hands a focused popup a touch outside it. */
    private fun tapOutside(popup: View) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0L, 0L, action, -10f, -10f, 0)
            popup.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    /** The Back key, as it reaches the focused popup. */
    private fun pressBack(popup: View) {
        popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
    }

    private companion object {
        val PRESS = MenuAnchor(WindowStore.MAIN, 40, 12, 1, 1)
    }
}
