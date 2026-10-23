// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.NamePrompt
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AmpContextMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = WinampState()

    private fun show(menu: AmpMenu) {
        state.activeMenu = menu
        compose.setContent {
            AmpModals(state, anchorBounds = { IntRect(IntOffset(120, 120), IntSize(44, 12)) })
        }
    }

    @Test
    fun `popup anchor box sits at the bounds the anchor mapper returns`() {
        show(AmpMenu("Root", listOf(AmpMenuItem.Action("X") {})))
        // pins the offset+size plumbing; DropdownMenu attaching to this box is framework behavior
        val bounds = compose.onNodeWithTag("menu.anchor").fetchSemanticsNode().boundsInRoot
        assertEquals(120f, bounds.left, 0.5f)
        assertEquals(120f, bounds.top, 0.5f)
        assertEquals(44f, bounds.width, 0.5f)
        assertEquals(12f, bounds.height, 0.5f)
    }

    @Test
    fun `menu pops with its top-level items, no title header`() {
        show(
            AmpMenu(
                "Equalizer presets",
                listOf(
                    AmpMenuItem.Submenu("Load", emptyList()),
                    AmpMenuItem.Action("Something") {},
                ),
            ),
        )
        compose.onNodeWithText("Load").assertIsDisplayed()
        compose.onNodeWithText("Something").assertIsDisplayed()
        compose.onNodeWithText("Equalizer presets").assertDoesNotExist()
    }

    @Test
    fun `submenu opens in place and its header row navigates back`() {
        show(
            AmpMenu(
                "Root",
                listOf(
                    AmpMenuItem.Submenu("Load", listOf(AmpMenuItem.Action("Rock") {})),
                    AmpMenuItem.Action("Top level") {},
                ),
            ),
        )
        compose.onNodeWithText("Load").performClick()
        compose.onNodeWithText("Rock").assertIsDisplayed()
        compose.onNodeWithText("Top level").assertDoesNotExist()

        compose.onNodeWithText("Load").performClick() // "‹ Load" header goes back
        compose.onNodeWithText("Top level").assertIsDisplayed()
    }

    @Test
    fun `tapping an action fires it and closes the menu`() {
        var fired = false
        show(AmpMenu("Root", listOf(AmpMenuItem.Action("Do it") { fired = true })))
        compose.onNodeWithText("Do it").performClick()
        compose.waitForIdle()
        assertTrue(fired)
        assertNull(state.activeMenu)
    }

    @Test
    fun `a pick runs its entry and closes the menu without calling onDismiss`() {
        var fired = 0
        var dismissed = 0
        show(AmpMenu("Root", listOf(AmpMenuItem.Action("Do it") { fired++ }), onDismiss = { dismissed++ }))

        compose.onNodeWithText("Do it").performClick()
        compose.waitForIdle()

        assertEquals(1, fired)
        assertEquals("a pick is not a dismissal", 0, dismissed)
        assertNull(state.activeMenu)
    }

    @Test
    fun `a tap outside closes the menu and calls onDismiss once`() {
        var fired = 0
        var dismissed = 0
        show(AmpMenu("Root", listOf(AmpMenuItem.Action("Do it") { fired++ }), onDismiss = { dismissed++ }))

        compose.runOnUiThread { tapOutside(popup()) }
        compose.waitForIdle()

        assertEquals(1, dismissed)
        assertEquals("nothing was picked", 0, fired)
        assertNull(state.activeMenu)
        compose.onNodeWithText("Do it").assertDoesNotExist()
    }

    @Test
    fun `back closes the menu and calls onDismiss once`() {
        var fired = 0
        var dismissed = 0
        show(AmpMenu("Root", listOf(AmpMenuItem.Action("Do it") { fired++ }), onDismiss = { dismissed++ }))

        compose.runOnUiThread { pressBack(popup()) }
        compose.waitForIdle()

        assertEquals(1, dismissed)
        assertEquals("nothing was picked", 0, fired)
        assertNull(state.activeMenu)
        compose.onNodeWithText("Do it").assertDoesNotExist()
    }

    @Test
    fun `opening a submenu and going back out of it neither picks nor dismisses`() {
        var dismissed = 0
        val menu =
            AmpMenu(
                "Root",
                listOf(AmpMenuItem.Submenu("Load", listOf(AmpMenuItem.Action("Rock") {})), AmpMenuItem.Action("Top level") {}),
                onDismiss = { dismissed++ },
            )
        show(menu)

        compose.onNodeWithText("Load").performClick()
        compose.onNodeWithText("Rock").assertIsDisplayed()
        compose.onNodeWithText("Load").performClick() // "‹ Load" header goes back
        compose.onNodeWithText("Top level").assertIsDisplayed()

        assertEquals(0, dismissed)
        assertSame(menu, state.activeMenu)
    }

    @Test
    fun `a menu put up while another is open is not cleared when the first one is dismissed`() {
        val second = AmpMenu("Second", listOf(AmpMenuItem.Action("Other") {}))
        show(AmpMenu("First", listOf(AmpMenuItem.Action("Do it") {})))

        compose.runOnUiThread {
            val first = popup()
            // the second menu replaces the first before the screen redraws, and the first
            // popup's Back still arrives
            state.activeMenu = second
            pressBack(first)
        }
        compose.waitForIdle()

        assertSame(second, state.activeMenu)
        compose.onNodeWithText("Other").assertIsDisplayed()
    }

    @Test
    fun `a pick that puts up another menu leaves that one open`() {
        val second = AmpMenu("Second", listOf(AmpMenuItem.Action("Other") {}))
        show(AmpMenu("First", listOf(AmpMenuItem.Action("Next") { state.activeMenu = second })))

        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()

        assertSame(second, state.activeMenu)
        compose.onNodeWithText("Other").assertIsDisplayed()
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

    @Test
    fun `disabled action does not fire`() {
        var fired = false
        show(AmpMenu("Root", listOf(AmpMenuItem.Action("Nope", enabled = false) { fired = true })))
        compose.onNodeWithText("Nope").performClick()
        compose.waitForIdle()
        assertEquals(false, fired)
    }

    @Test
    fun `name prompt shows and confirm stays disabled while the text is blank`() {
        var submitted: String? = null
        state.namePrompt = NamePrompt(title = "Save preset", confirmLabel = "Save") { submitted = it }
        compose.setContent {
            AmpModals(state, anchorBounds = { IntRect(IntOffset.Zero, IntSize.Zero) })
        }

        compose.onNodeWithText("Save preset").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertNull(submitted)
    }

    @Test
    fun `typing into a name prompt replaces the name it suggests`() {
        var named: String? = null
        state.namePrompt = NamePrompt(title = "Station name", initial = "radio.example.com", confirmLabel = "Save") { named = it }
        compose.setContent {
            AmpModals(state, anchorBounds = { IntRect(IntOffset.Zero, IntSize.Zero) })
        }

        compose.onNode(hasSetTextAction()).performTextInput("Late Example")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals("Late Example", named)
    }

    @Test
    fun `a prompt chained from onSubmit survives the first dialog's dismissal`() {
        // radio's NEW: the url prompt's submit sets the name prompt, and the
        // dialog's own post-submit dismiss must not tear the second one down
        var named: String? = null
        state.namePrompt =
            NamePrompt(title = "Station URL", initial = "http://a.example/x", confirmLabel = "Next") {
                state.namePrompt = null
                state.namePrompt =
                    NamePrompt(title = "Station name", initial = "a.example", confirmLabel = "Add") { typed -> named = typed }
            }
        compose.setContent {
            AmpModals(state, anchorBounds = { IntRect(IntOffset.Zero, IntSize.Zero) })
        }

        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Station name").assertIsDisplayed()
        compose.onNodeWithText("Add").performClick()
        compose.waitForIdle()
        assertEquals("a.example", named)
    }
}
