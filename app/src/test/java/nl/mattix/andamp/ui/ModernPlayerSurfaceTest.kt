// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import android.app.Application
import android.os.Looper
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
import nl.mattix.andamp.modernskin.skin.SkinMenuChoices
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.SkinLibrary
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.state.addModern
import nl.mattix.andamp.ui.menu.menuAnchorBounds
import nl.mattix.andamp.ui.menu.widgetMenu
import nl.mattix.andamp.ui.window.BIG_WINDOW
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.PlaylistMenuActions
import nl.mattix.andamp.ui.window.testViewModel
import nl.mattix.andamp.wal.FakeWalSurface
import nl.mattix.andamp.wal.WalGate
import nl.mattix.andamp.wal.WalPlacement
import nl.mattix.andamp.wal.WalSurface
import nl.mattix.andamp.wal.hidesModernSkin
import nl.mattix.andamp.wal.stackOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The player with a modern skin worn: the skin stands in for the main window, the equalizer and the
 * playlist, and the classic skin still draws the rest. Most cases use a skin with no engine behind
 * it; the app's screen and the widget menu cases wear a modern skin that loads.
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

    /**
     * The player's view model, wearing the classic skin. Made at first use, so a test that makes one
     * wearing a modern skin has the only view model listening to the modern skins switch.
     */
    private val vm by lazy { testViewModel().apply { welcome.seen = true } }
    private val surface = FakeWalSurface().apply { layoutFor = stackOf(300, 100, 2f) }
    private val box = IntRect(0, 0, 900, 1599)

    /** What the playlist's pickers were asked for. */
    private val picked = mutableListOf<String>()

    /** View models made with a modern skin worn, whose looks are let go after the test. */
    private val wearing = mutableListOf<WinampViewModel>()

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext<Application>())
    }

    @After
    fun tearDown() {
        wearing.forEach { it.skinOps.wal?.release(save = false) }
        if (wearing.isNotEmpty()) WalGate.forget()
    }

    /**
     * A view model wearing a modern skin that loads (one window with a play button), chosen before
     * the view model is made, as at a start with that skin worn. Modern skins are on, as a debug
     * build reads them with no switch written.
     */
    private fun wearingModern(): WinampViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WalGate.forget()
        WalGate.file(app).delete()
        app
            .getSharedPreferences("skins", 0)
            .edit()
            .clear()
            .commit()
        File(app.filesDir, "skins").deleteRecursively()
        val library = SkinLibrary(app)
        library.choose(library.addModern(app, "Loads", loads = true))
        val made = testViewModel().also { wearing += it }
        made.welcome.seen = true
        until("the modern skin is worn") { made.skinOps.wal != null && made.skin != null }
        return made
    }

    /**
     * Runs the main looper, which the skin's engine posts its frames to, and the frame clock, until
     * [condition] holds, for up to 10 s.
     */
    private fun until(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        do {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            if (compose.mainClock.autoAdvance.not()) compose.mainClock.advanceTimeByFrame()
            if (condition()) return
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        fail(what)
    }

    private fun frames(n: Int = FRAMES) =
        repeat(n) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }

    private fun show(
        wal: WalSurface? = surface,
        compact: Boolean = false,
    ) {
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
                    compact = compact,
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
    fun `a long press beside several of the skin's menus leads the main menu with them under Skin menu`() {
        surface.menusBesideEveryPress =
            listOf(SkinMenuChoices.Choice("main.vis.trigger", null), SkinMenuChoices.Choice("display.time", null))
        show()

        longPress(Offset(450f, 100f))

        val menu = vm.state.activeMenu!!
        val first = menu.items[0] as AmpMenuItem.Submenu
        assertEquals(vm.words.get(R.string.menu_skin_menu), first.label)
        assertEquals(
            listOf(R.string.menu_skin_menu_visualizer, R.string.menu_skin_menu_time).map { vm.words.get(it) },
            first.items.map { (it as AmpMenuItem.Action).label },
        )
        assertSame(AmpMenuItem.Divider, menu.items[1])

        (first.items[1] as AmpMenuItem.Action).onClick()
        assertEquals(listOf(1 to 1), surface.choiceClicks)
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
    fun `while the skin loads, the classic main window's D, O and V leave Double Size and the in-player modes alone`() {
        show(FakeWalSurface())
        val main = vm.state.windowRects[WindowStore.MAIN]!!

        fun tap(button: IntRect) {
            val at = Offset((main.left + button.center.x) * 3 + 1f, (main.top + button.center.y) * 3 + 1f)
            screen().performTouchInput {
                down(at)
                up()
            }
            frames()
        }

        tap(Dest.CLUTTER_D)
        assertFalse("the stored Double Size stays off", vm.doubleSize.on)

        tap(Dest.CLUTTER_O)
        val options = vm.state.activeMenu!!
        val doubleSize =
            options.items.filterIsInstance<AmpMenuItem.Action>().first {
                it.label ==
                    vm.words.get(R.string.menu_double_size)
            }
        assertFalse("Options grays Double Size", doubleSize.enabled || doubleSize.checked)
        vm.state.activeMenu = null
        frames()

        tap(Dest.CLUTTER_V)
        val visualization = vm.state.activeMenu!!
        val modes =
            visualization.items
                .filterIsInstance<AmpMenuItem.Submenu>()
                .first { it.label == vm.words.get(R.string.menu_visualization_options) }
        assertFalse("V grays the in-player modes", modes.enabled)
        assertTrue(
            "the plug-in's entries stay",
            visualization.items.filterIsInstance<AmpMenuItem.Action>().all { it.enabled },
        )
    }

    @Test
    fun `the floating player fits the skin compact`() {
        show(compact = true)

        assertTrue(surface.fits.isNotEmpty() && surface.fits.all { it.compact })
    }

    @Test
    fun `the app's screen fits the skin to the band`() {
        show()

        assertTrue(surface.fits.isNotEmpty() && surface.fits.none { it.compact })
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

    @Test
    fun `the plug-in window docks under a skin's stack taller than the classic main window`() {
        surface.layoutFor = stackOf(300, 300, 2f)
        vm.state.milkdropOn = true

        show()

        val stack = vm.state.windowRects[WindowStore.MAIN]!!
        val plugin = vm.state.windowRects[WindowStore.MILKDROP]!!
        assertTrue("the stack is taller than the classic main window: $stack", stack.height > MAIN_H)
        assertEquals("it docks under the stack", stack.bottom, plugin.top)
    }

    @Test
    fun `under a stack that fills the screen the plug-in window docks as low as it fits`() {
        surface.layoutFor = stackOf(450, 799, 2f)
        vm.state.milkdropOn = true

        show()

        val plugin = vm.state.windowRects[WindowStore.MILKDROP]!!
        assertEquals("its bottom is the screen's safe bottom", vm.state.safeBottom, plugin.bottom)
    }

    @Test
    fun `a fullscreen Milkdrop over a modern skin rests it`() {
        show()
        assertTrue("shown, the skin is awake", surface.awake)

        vm.state.milkdropOn = true
        vm.state.milkdropFullscreen = true
        frames()
        val ticks = surface.ticks
        frames()

        assertFalse("under the fullscreen visualizer it rests", surface.awake)
        assertEquals("and gets no ticks", ticks, surface.ticks)

        vm.state.milkdropFullscreen = false
        frames()
        assertTrue("the visualizer in its window leaves it awake", surface.awake)
    }

    @Test
    fun `the Library or the Skin Browser over the whole stack rests the skin, and one beside it does not`() {
        val s = WinampState()
        s.windowRects[WindowStore.MAIN] = IntRect(50, 0, 250, 67)
        assertFalse("nothing over it", hidesModernSkin(s))

        s.libraryOpen = true
        s.windowRects[WindowStore.LIBRARY] = IntRect(40, 0, 260, 200)
        assertTrue("the Library over all of it", hidesModernSkin(s))
        s.windowRects[WindowStore.LIBRARY] = IntRect(60, 0, 260, 200)
        assertFalse("the Library over part of it", hidesModernSkin(s))
        s.libraryOpen = false
        s.windowRects[WindowStore.LIBRARY] = IntRect(0, 0, 300, 300)
        assertFalse("a closed Library's last rect counts for nothing", hidesModernSkin(s))

        s.skinManagerOpen = true
        s.windowRects[WindowStore.SKINS] = IntRect(50, 0, 250, 67)
        assertTrue("the Skin Browser just as large", hidesModernSkin(s))
        s.windowRects.remove(WindowStore.MAIN)
        assertFalse("no stack placed yet", hidesModernSkin(s))
    }

    @Test
    fun `with a modern skin laid out and no classic window open the classic clocks rest`() {
        show()
        val before = vm.state.marqueeStep

        compose.mainClock.advanceTimeBy(2_000)

        assertEquals("nothing shows the classic title", before, vm.state.marqueeStep)
    }

    @Test
    fun `the Library's search caret blinks over a modern skin`() {
        show()
        vm.state.setWindowOpen(WindowStore.LIBRARY, true) { vm.state.libraryOpen = it }
        frames()
        val before = vm.state.marqueeStep

        compose.mainClock.advanceTimeBy(1_000)

        assertTrue("the clock the caret blinks by steps: ${vm.state.marqueeStep}", vm.state.marqueeStep > before)
    }

    @Test
    fun `the app's screen shows the worn modern skin, and a classic skin brings the classic windows back`() {
        val vm = wearingModern()
        val look = vm.skinOps.wal!!
        vm.state.eqVisible = true
        vm.state.plVisible = true
        compose.mainClock.autoAdvance = false
        compose.setContent { WinampScreen(vm) }

        until("the skin's stack takes the main window's place") {
            val layout = look.layout
            layout != null && vm.state.windowRects[WindowStore.MAIN] == WalPlacement.of(box, layout, 3).window
        }
        assertTrue("its engine runs", Thread.getAllStackTraces().keys.any { it.name == "wal-${look.id}" })
        assertNull(vm.state.windowRects[WindowStore.EQ])
        assertNull(vm.state.windowRects[WindowStore.PLAYLIST])

        vm.skinOps.apply(BundledSkins.LIGHT.id)

        until("the classic windows are back") {
            vm.skinOps.wal == null && vm.state.windowRects[WindowStore.EQ] != null &&
                vm.state.windowRects[WindowStore.PLAYLIST] != null
        }
        assertTrue("the skin was let go", look.released)
        assertEquals("the classic main window", 275, vm.state.windowRects[WindowStore.MAIN]!!.width)
    }

    @Test
    fun `the widget menu leaves the classic windows unticked while a modern skin is worn`() {
        fun ticked(
            vm: WinampViewModel,
            label: Int,
        ): Boolean {
            val menu =
                widgetMenu(
                    vm,
                    onWidgetSettings = {},
                    onPickSkin = {},
                    onOpenFile = {},
                    onOpenFolder = {},
                    onOpen = {},
                    onExit = {},
                )
            return menu.items
                .filterIsInstance<AmpMenuItem.Action>()
                .first { it.label == vm.words.get(label) }
                .checked
        }
        val vm = wearingModern()
        vm.state.plVisible = true
        vm.state.eqVisible = true

        assertFalse(ticked(vm, R.string.menu_playlist_editor))
        assertFalse(ticked(vm, R.string.menu_equalizer))

        vm.skinOps.apply(BundledSkins.LIGHT.id)
        until("the classic skin is worn") { vm.skinOps.wal == null }

        assertTrue(
            "with the classic skin they are ticked",
            ticked(vm, R.string.menu_playlist_editor) && ticked(vm, R.string.menu_equalizer),
        )
    }

    private companion object {
        const val FRAMES = 6
        const val SCREEN = "screen"
    }
}
