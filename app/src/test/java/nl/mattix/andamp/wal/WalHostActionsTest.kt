// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.os.Looper
import nl.mattix.andamp.R
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.VisualCommands
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.menu.MenuBarSection
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the player does for a modern skin's buttons that reach outside the skin. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h1600dp-mdpi")
class WalHostActionsTest {
    private val reached = mutableListOf<String>()

    /** Where the menus asked for popped. */
    private val anchors = mutableListOf<MenuAnchor?>()
    private val sections = mutableListOf<MenuBarSection?>()

    private fun on(name: String) {
        assertTrue("$name runs on the main thread", Looper.getMainLooper().isCurrentThread)
        reached += name
    }

    private val actions =
        WalHostActions(
            openMenu = { section, anchor ->
                sections += section
                anchors += anchor
                on("menu")
            },
            minimize = { on("minimize") },
            exit = { on("exit") },
            toggleLibrary = { on("library") },
            toggleVisualizer = { on("visualizer") },
            showVisualizer = { on("show visualizer") },
            visualizationMenu = {
                anchors += it
                on("visualization menu")
            },
            fullscreenVisualizer = { on("fullscreen") },
            nextPreset = { on("next preset") },
            previousPreset = { on("previous preset") },
            pickFile = { andPlay -> on(if (andPlay) "file to play" else "file") },
            fileInfo = { on("file info") },
        )

    private fun reaches(
        action: String,
        param: String? = null,
    ): List<String> {
        reached.clear()
        actions.hostAction(action, param)
        return reached.toList()
    }

    @Test
    fun `each action reaches one thing the player does`() {
        assertEquals(listOf("menu"), reaches("MENU"))
        assertEquals(listOf("menu"), reaches("SYSMENU"))
        assertEquals(listOf("menu"), reaches("CONTROLMENU"))
        assertEquals("the main window's X, as the classic X", listOf("exit"), reaches("CLOSE"))
        assertEquals(listOf("minimize"), reaches("MINIMIZE"))
        assertEquals(listOf("library"), reaches("TOGGLE", "ml"))
        assertEquals(listOf("visualizer"), reaches("TOGGLE", "avs"))
        assertEquals(listOf("visualization menu"), reaches("VIS_MENU"))
        assertEquals(listOf("fullscreen"), reaches("VIS_FS"))
        assertEquals(listOf("next preset"), reaches("VIS_NEXT"))
        assertEquals(listOf("previous preset"), reaches("VIS_PREV"))
        assertEquals(listOf("file info"), reaches("TRACKINFO"))
        assertEquals("Detach Visualizer", listOf("show visualizer"), reaches("SHOW", "avs"))
    }

    @Test
    fun `the eject button and Play on an empty playlist each open the file picker`() {
        actions.openFile(andPlay = false)
        actions.openFile(andPlay = true)

        assertEquals(listOf("file", "file to play"), reached)
    }

    @Test
    fun `a video window, another window and an unknown action reach nothing`() {
        assertEquals(emptyList<String>(), reaches("TOGGLE", "video"))
        assertEquals(emptyList<String>(), reaches("TOGGLE", "pl"))
        assertEquals(emptyList<String>(), reaches("TOGGLE", "guid:something"))
        assertEquals(emptyList<String>(), reaches("TOGGLE", null))
        assertEquals(emptyList<String>(), reaches("SHOW", "video"))
        assertEquals(emptyList<String>(), reaches("SOMETHING_ELSE"))
    }

    @Test
    fun `a menu bar entry opens the part of the main menu it names, and the whole menu for any other`() {
        actions.hostAction("MENU", "WA5:Options")
        actions.hostAction("MENU", "WA5:Play")
        actions.hostAction("MENU", "WA5:File")
        actions.hostAction("MENU", null)
        actions.hostAction("SYSMENU", "WA5:Options")

        assertEquals(listOf(MenuBarSection.OPTIONS, MenuBarSection.PLAYBACK, null, null, null), sections)
    }

    @Test
    fun `a menu the skin asks for pops at the last press`() {
        val press = MenuAnchor(WindowStore.MAIN, 40, 12, 1, 1)
        actions.hostAction("MENU", null)
        actions.pressAt = { press }
        actions.hostAction("SYSMENU", null)
        actions.hostAction("VIS_MENU", null)

        assertEquals(listOf(null, press, press), anchors)
    }

    @Test
    fun `the library button opens and closes the library as the main menu does`() {
        val vm = testViewModel()
        val actions = WalHostActions.of(vm, openMenu = { _, _ -> }, onMinimize = {}, openFile = {})

        actions.hostAction("TOGGLE", "ml")
        assertTrue(vm.state.libraryOpen)
        assertEquals("raised over the other windows", WindowStore.LIBRARY, vm.state.windowOrder.last())

        actions.hostAction("TOGGLE", "ml")
        assertFalse(vm.state.libraryOpen)
    }

    @Test
    fun `the visualizer button opens and closes the visualizer window`() {
        val vm = testViewModel()
        val actions = WalHostActions.of(vm, openMenu = { _, _ -> }, onMinimize = {}, openFile = {})

        actions.hostAction("TOGGLE", "avs")
        assertTrue(vm.state.milkdropOn)

        actions.hostAction("TOGGLE", "avs")
        assertFalse(vm.state.milkdropOn)
    }

    @Test
    fun `fullscreen opens the visualizer window and fills the screen with it`() {
        val vm = testViewModel()
        val actions = WalHostActions.of(vm, openMenu = { _, _ -> }, onMinimize = {}, openFile = {})

        actions.hostAction("VIS_FS", null)

        assertTrue(vm.state.milkdropOn)
        assertTrue(vm.state.milkdropFullscreen)
    }

    @Test
    fun `fullscreen fills the screen with the visualizer the skin shows, leaving its window shut`() {
        val vm = testViewModel()
        val actions = WalHostActions.of(vm, openMenu = { _, _ -> }, onMinimize = {}, openFile = {})
        vm.state.visInSkin = true

        actions.hostAction("VIS_FS", null)

        assertFalse(vm.state.milkdropOn)
        assertTrue(vm.state.visualizerFullscreen)
    }

    @Test
    fun `Detach Visualizer opens the visualizer window, and again leaves it open`() {
        val vm = testViewModel()
        val actions = WalHostActions.of(vm, openMenu = { _, _ -> }, onMinimize = {}, openFile = {})

        actions.hostAction("SHOW", "avs")
        actions.hostAction("SHOW", "avs")

        assertTrue(vm.state.milkdropOn)
    }

    @Test
    fun `the preset buttons step the visualizer only while its window or the skin shows it`() {
        val vm = testViewModel()
        val stepped = mutableListOf<String>()
        vm.state.visualCommands =
            object : VisualCommands {
                override fun nextPreset() {
                    stepped += "next"
                }

                override fun previousPreset() {
                    stepped += "previous"
                }

                override fun goToPreset(index: Int) = Unit
            }
        val actions = WalHostActions.of(vm, openMenu = { _, _ -> }, onMinimize = {}, openFile = {})

        actions.hostAction("VIS_NEXT", null)
        assertEquals("closed, it is not stepped", emptyList<String>(), stepped)

        vm.state.milkdropOn = true
        actions.hostAction("VIS_NEXT", null)
        actions.hostAction("VIS_PREV", null)
        assertEquals(listOf("next", "previous"), stepped)

        vm.state.milkdropOn = false
        vm.state.visInSkin = true
        actions.hostAction("VIS_NEXT", null)
        assertEquals("the skin shows it", listOf("next", "previous", "next"), stepped)
    }

    @Test
    fun `the visualization menu pops at the press with the in-player modes grayed`() {
        val vm = testViewModel()
        val actions = WalHostActions.of(vm, openMenu = { _, _ -> }, onMinimize = {}, openFile = {})
        val press = MenuAnchor(WindowStore.MAIN, 40, 12, 1, 1)
        actions.pressAt = { press }

        actions.hostAction("VIS_MENU", null)

        val menu = vm.state.activeMenu!!
        assertSame(press, menu.anchor)
        val modes =
            menu.items.filterIsInstance<AmpMenuItem.Submenu>().first {
                it.label ==
                    vm.words.get(R.string.menu_visualization_options)
            }
        assertFalse("a modern skin does not show the in-player modes", modes.enabled)
    }

    @Test
    fun `the file info button shows the song playing, and nothing before a song has played`() {
        val vm = testViewModel()
        val actions = WalHostActions.of(vm, openMenu = { _, _ -> }, onMinimize = {}, openFile = {})

        actions.hostAction("TRACKINFO", null)
        assertNull("nothing has played", vm.state.trackInfo)

        vm.playTrack(0)
        actions.hostAction("TRACKINFO", null)
        assertEquals(
            vm.state.playlist
                .first()
                .title,
            vm.state.trackInfo?.heading,
        )
    }

    @Test
    fun `the menu buttons, minimize and the file requests reach what the surface gave`() {
        val vm = testViewModel()
        val got = mutableListOf<String>()
        val press = MenuAnchor(WindowStore.MAIN, 40, 12, 1, 1)
        val actions =
            WalHostActions.of(
                vm,
                openMenu = { _, at -> got += "menu at ${at?.x},${at?.y}" },
                onMinimize = { got += "minimize" },
                openFile = { got += "file $it" },
                onExit = { got += "exit" },
            )
        actions.pressAt = { press }

        actions.hostAction("SYSMENU", null)
        actions.hostAction("CLOSE", null)
        actions.hostAction("MINIMIZE", null)
        actions.openFile(andPlay = true)

        assertEquals(listOf("menu at 40,12", "exit", "minimize", "file true"), got)
    }
}
