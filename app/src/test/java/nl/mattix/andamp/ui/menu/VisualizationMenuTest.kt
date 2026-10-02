// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.VisPlugin
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h1600dp-mdpi")
class VisualizationMenuTest {
    private fun labels(items: List<AmpMenuItem>) =
        items.map {
            when (it) {
                is AmpMenuItem.Action -> it.label
                is AmpMenuItem.Submenu -> it.label
                AmpMenuItem.Divider -> "---"
            }
        }

    @Test
    fun `entries match Winamp 2_8's visualization context menu`() {
        val menu = visualizationMenu(testViewModel())
        assertEquals(
            listOf(
                "Visualization options",
                "---",
                "Start/Stop plug-in",
                "Configure plug-in",
                "Select plug-in",
            ),
            labels(menu.items),
        )
    }

    @Test
    fun `start stop toggles the plug-in window both ways`() {
        val vm = testViewModel()
        val toggle = menuAction(vm, "Start/Stop plug-in")
        toggle.onClick()
        assertTrue(vm.state.milkdropOn)
        menuAction(vm, "Start/Stop plug-in").onClick()
        assertEquals(false, vm.state.milkdropOn)
    }

    @Test
    fun `options lists the in-player display modes`() {
        val vm = testViewModel()
        val options =
            visualizationMenu(vm).items.filterIsInstance<AmpMenuItem.Submenu>().first { it.label.startsWith("Visualization") }
        assertEquals(listOf("Spectrum analyzer", "Oscilloscope", "Off"), labels(options.items))

        options.items
            .filterIsInstance<AmpMenuItem.Action>()
            .first { it.label == "Oscilloscope" }
            .onClick()
        assertEquals(VisMode.Oscilloscope, vm.state.visMode)
    }

    @Test
    fun `configure offers the same entries as the visual's long press`() {
        val vm = testViewModel()

        assertEquals(labels(visualMenuItems(vm)), labels(configure(vm)))
    }

    @Test
    fun `configure reaches the same state as the long press`() {
        val vm = testViewModel()

        configure(vm).filterIsInstance<AmpMenuItem.Action>().first { it.label == "Shuffle" }.onClick()

        assertTrue(vm.state.presetShuffle)
        assertTrue(configure(vm).filterIsInstance<AmpMenuItem.Action>().first { it.label == "Shuffle" }.checked)
    }

    @Test
    fun `select plug-in lists what is installed and picking one starts it`() {
        val vm = testViewModel()
        val select =
            visualizationMenu(vm).items.filterIsInstance<AmpMenuItem.Submenu>().first { it.label.startsWith("Select") }
        assertEquals(listOf("AVS", "Milkdrop"), labels(select.items))

        select.items
            .filterIsInstance<AmpMenuItem.Action>()
            .first { it.label == "Milkdrop" }
            .onClick()
        assertEquals(VisPlugin.Milkdrop, vm.state.visPlugin)
        assertTrue(vm.state.milkdropOn)
    }

    @Test
    fun `the menu anchors where it was opened from`() {
        val anchor = MenuAnchor("main", 24, 43, 76, 16)
        assertEquals(anchor, visualizationMenu(testViewModel(), anchor).anchor)
    }

    private fun configure(vm: nl.mattix.andamp.state.WinampViewModel) =
        visualizationMenu(vm)
            .items
            .filterIsInstance<AmpMenuItem.Submenu>()
            .first { it.label.startsWith("Configure") }
            .items

    private fun menuAction(
        vm: nl.mattix.andamp.state.WinampViewModel,
        label: String,
    ) = visualizationMenu(vm).items.filterIsInstance<AmpMenuItem.Action>().first { it.label == label }
}
