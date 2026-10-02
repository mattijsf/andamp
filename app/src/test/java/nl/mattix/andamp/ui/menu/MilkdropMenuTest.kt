// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.VisPlugin
import nl.mattix.andamp.state.VisualCommands
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The visual's own long press: what the running plug-in can be told. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h1600dp-mdpi")
class MilkdropMenuTest {
    private class RecordingCommands : VisualCommands {
        val calls = mutableListOf<String>()

        override fun nextPreset() {
            calls += "next"
        }

        override fun previousPreset() {
            calls += "previous"
        }

        override fun goToPreset(index: Int) {
            calls += "goTo $index"
        }
    }

    private fun labels(items: List<AmpMenuItem>) =
        items.map {
            when (it) {
                is AmpMenuItem.Action -> it.label
                is AmpMenuItem.Submenu -> it.label
                AmpMenuItem.Divider -> "---"
            }
        }

    private fun ticked(items: List<AmpMenuItem>) =
        items.filterIsInstance<AmpMenuItem.Action>().filter { it.checked }.map { it.label }

    private fun presets(
        vm: WinampViewModel,
        count: Int,
    ) {
        vm.state.presetNames = (1..count).map { "preset $it" }
    }

    private fun action(
        vm: WinampViewModel,
        label: String,
    ) = visualMenu(vm).items.filterIsInstance<AmpMenuItem.Action>().first { it.label == label }

    private fun presetItems(vm: WinampViewModel) =
        visualMenu(vm)
            .items
            .filterIsInstance<AmpMenuItem.Submenu>()
            .first { it.label == "Presets" }
            .items

    @Test
    fun `the menu is about what the running plug-in is doing`() {
        assertEquals(
            listOf(
                "Presets",
                "Preset pack",
                "Next preset",
                "Previous preset",
                "---",
                "Shuffle",
                "---",
                "Manage presets...",
                "Load preset pack...",
                "---",
                "Fullscreen",
                "Close",
            ),
            labels(visualMenu(testViewModel()).items),
        )
    }

    @Test
    fun `the presets are listed and picking one plays it`() {
        val vm = testViewModel()
        val commands = RecordingCommands()
        vm.state.visualCommands = commands
        presets(vm, 3)

        assertEquals(listOf("preset 1", "preset 2", "preset 3"), labels(presetItems(vm)))

        presetItems(vm).filterIsInstance<AmpMenuItem.Action>()[2].onClick()
        assertEquals(listOf("goTo 2"), commands.calls)
    }

    @Test
    fun `the preset now playing is ticked`() {
        val vm = testViewModel()
        presets(vm, 3)
        vm.state.presetName = "preset 2"

        assertEquals(listOf("preset 2"), ticked(presetItems(vm)))
    }

    @Test
    fun `with no pack loaded the idle preset is offered and ticked`() {
        val vm = testViewModel()
        vm.state.visPlugin = VisPlugin.Milkdrop
        val packs =
            visualMenu(vm)
                .items
                .filterIsInstance<AmpMenuItem.Submenu>()
                .first { it.label == "Preset pack" }
                .items

        assertEquals(listOf("projectM idle preset"), labels(packs))
        assertEquals(listOf("projectM idle preset"), ticked(packs))
    }

    /** Each engine's menu offers its own idle preset, under its own name. */
    @Test
    fun `the AVS menu offers the AVS idle preset`() {
        val vm = testViewModel()
        vm.state.visPlugin = VisPlugin.Avs
        val packs =
            visualMenu(vm)
                .items
                .filterIsInstance<AmpMenuItem.Submenu>()
                .first { it.label == "Preset pack" }
                .items

        assertEquals(listOf("AVS idle preset"), labels(packs))
    }

    /** A pack can hold thousands of presets, so the menu is capped. */
    @Test
    fun `a pack too big for a menu is capped and offers the manager`() {
        val vm = testViewModel()
        presets(vm, 500)
        val items = presetItems(vm)

        assertEquals(PRESET_MENU_MAX + 2, items.size) // the cap, a divider, and the way out
        assertEquals("All 500 presets...", labels(items).last())

        items.filterIsInstance<AmpMenuItem.Action>().last().onClick()
        assertTrue(vm.state.presetManagerRequested)
    }

    @Test
    fun `no presets loaded says so and opens the manager`() {
        val vm = testViewModel()
        val items = presetItems(vm)

        assertEquals(listOf("No presets loaded"), labels(items))
        items.filterIsInstance<AmpMenuItem.Action>().single().onClick()
        assertTrue(vm.state.presetManagerRequested)
    }

    @Test
    fun `next and previous reach the visual's commands`() {
        val vm = testViewModel()
        val commands = RecordingCommands()
        vm.state.visualCommands = commands

        action(vm, "Next preset").onClick()
        action(vm, "Previous preset").onClick()

        assertEquals(listOf("next", "previous"), commands.calls)
    }

    /** The window can be closed while a menu built from it is still up. */
    @Test
    fun `with no visual on screen the verbs are harmless`() {
        val vm = testViewModel()
        vm.state.visualCommands = null

        action(vm, "Next preset").onClick()
        action(vm, "Previous preset").onClick()
    }

    @Test
    fun `shuffle toggles and is ticked when it is on`() {
        val vm = testViewModel()

        action(vm, "Shuffle").onClick()
        assertTrue(vm.state.presetShuffle)
        assertTrue(ticked(visualMenu(vm).items).contains("Shuffle"))
    }

    @Test
    fun `fullscreen, load pack and close work from the menu`() {
        val vm = testViewModel()
        vm.presetOps.setWindowOpen(true)

        action(vm, "Fullscreen").onClick()
        assertTrue(vm.state.milkdropFullscreen)

        action(vm, "Load preset pack...").onClick()
        assertTrue(vm.state.presetPickRequested)

        action(vm, "Close").onClick()
        assertEquals(false, vm.state.milkdropOn)
    }
}
