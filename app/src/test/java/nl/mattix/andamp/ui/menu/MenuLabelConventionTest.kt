// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Windows menu convention Winamp followed: a trailing "..." promises a
 * dialog, a "›" promises a submenu, and no entry may claim both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h1600dp-mdpi")
class MenuLabelConventionTest {
    private fun allMenus(): List<AmpMenu> {
        val vm = testViewModel()
        return listOf(
            mainMenu(vm, {}, {}, {}, {}),
            visualizationMenu(vm),
            vm.eqOps.presetsMenu(),
        )
    }

    private fun submenuLabels(items: List<AmpMenuItem>): List<String> =
        items.filterIsInstance<AmpMenuItem.Submenu>().flatMap { listOf(it.label) + submenuLabels(it.items) }

    @Test
    fun `no submenu label ends in an ellipsis`() {
        for (menu in allMenus()) {
            for (label in submenuLabels(menu.items)) {
                assertTrue("submenu '$label' has no ellipsis", !label.endsWith("..."))
            }
        }
    }

    @Test
    fun `entries that do open a dialog keep their ellipsis`() {
        val vm = testViewModel()
        val save =
            vm.eqOps
                .presetsMenu()
                .items
                .filterIsInstance<AmpMenuItem.Submenu>()
                .first { it.label == "Save" }
        val prompt = save.items.filterIsInstance<AmpMenuItem.Action>().first { it.label.startsWith("Preset") }
        assertTrue("the name prompt entry ends in an ellipsis: '${prompt.label}'", prompt.label.endsWith("..."))
    }
}
