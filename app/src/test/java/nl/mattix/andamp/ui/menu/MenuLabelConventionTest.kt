// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.After
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
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** A view model that wore a modern skin; let go after the test. */
    private var wearing: WinampViewModel? = null

    @After
    fun letGoOfModernSkin() {
        wearing?.let { letGoOfModernSkin(app, it) }
    }

    private fun allMenus(): List<AmpMenu> {
        val vm = testViewModel()
        val modern = wearThemedModernSkin(app, "One", "Two").also { wearing = it }
        seedSkinOptions(modern.skinOps.wal!!) { rt -> rt.optionsItem().option("Option", "1") }
        return listOf(
            mainMenu(vm, {}, {}, {}, {}),
            // with a modern skin worn whose color themes are listed under Skins, and its options under Options
            mainMenu(modern, {}, {}, {}, {}, modernPlayer = true).also { menu ->
                assertTrue("Color themes is listed", submenuLabels(menu.items).contains("Color themes"))
                assertTrue("Skin Settings is listed", submenuLabels(menu.items).contains("Skin Settings"))
            },
            // led by a skin's menu, or several, as a long press on a modern skin opens it
            mainMenu(vm, {}, {}, {}, {}, skinMenus = listOf(AmpMenuItem.Action("Visualizer") {})),
            mainMenu(vm, {}, {}, {}, {}, skinMenus = listOf("Visualizer", "Time").map { AmpMenuItem.Action(it) {} }),
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
    fun `the skin menu entry opens a menu, not a dialog, so it has no ellipsis`() {
        val entry =
            mainMenu(testViewModel(), {}, {}, {}, {}, skinMenus = listOf(AmpMenuItem.Action("Visualizer") {})).items.first()
                as AmpMenuItem.Action

        assertTrue("'${entry.label}' has no ellipsis", !entry.label.endsWith("..."))
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
