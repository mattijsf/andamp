// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.window.testViewModel
import nl.mattix.andamp.wal.WalLook
import nl.mattix.andamp.wal.WalSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Options > Skin Settings...: offered while a worn modern skin lists options, it opens the dialog
 * for that skin, which lists them grouped as the skin nests them and closes when the skin leaves
 * the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h1600dp-mdpi")
class SkinSettingsMenuTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** The view models that wore a modern skin; let go after each test. */
    private val wearing = ArrayList<WinampViewModel>()

    @After
    fun letGoOfModernSkins() {
        wearing.forEach { letGoOfModernSkin(app, it) }
    }

    private fun wear(
        vararg themes: String,
        started: Boolean = true,
    ) = wearThemedModernSkin(app, *themes, started = started).also { wearing += it }

    private fun submenu(
        menu: AmpMenu,
        label: String,
    ) = menu.items
        .filterIsInstance<AmpMenuItem.Submenu>()
        .first { it.label == label }
        .items

    /** Every entry's label, a divider as "-", with submenus walked as "Parent › Entry". */
    private fun shape(
        items: List<AmpMenuItem>,
        path: String = "",
    ): List<String> =
        items.flatMap { item ->
            when (item) {
                is AmpMenuItem.Action -> listOf(path + item.label)
                is AmpMenuItem.Submenu -> listOf(path + item.label) + shape(item.items, "$path${item.label} › ")
                AmpMenuItem.Divider -> listOf("$path-")
            }
        }

    /** What an entry is called, whichever kind of entry it is. */
    private fun labelOf(item: AmpMenuItem): String? =
        when (item) {
            is AmpMenuItem.Action -> item.label
            is AmpMenuItem.Submenu -> item.label
            else -> null
        }

    /** Gives the skin [vm] wears options: two toggles around a separator, and a submenu holding one more. */
    private fun seedOptions(vm: WinampViewModel) =
        seedSkinOptions(vm.skinOps.wal!!) { rt ->
            val more = rt.newConfigItem("More", "{0D2C3B4A-0000-0000-0000-000000000001}")
            more.option("Inner", "1")
            val root = rt.optionsItem()
            root.option("Shown\tAlt+S", "1")
            root.option("-", "-")
            root.option("&Hidden", "0")
            root.option("More", "0D2C3B4A-0000-0000-0000-000000000001")
        }

    /** The Options list under the root menu. */
    private fun options(vm: WinampViewModel) = submenu(mainMenu(vm, {}, {}, {}, {}), "Options")

    @Test
    fun `a worn modern skin's Skin Settings follow Skins under Options, open the dialog for that skin, needing the app`() {
        val vm = wear()
        seedOptions(vm)

        for (list in listOf(options(vm), optionsMenu(vm).items)) {
            val settings = list[2] as AmpMenuItem.Action
            assertEquals("Skins", labelOf(list[1]))
            assertEquals("Skin Settings...", settings.label)
            assertEquals("before the first divider", AmpMenuItem.Divider, list[3])
            assertTrue("only the player's skin holds them", settings.needsTheApp)
            vm.state.skinSettings = null
            settings.onClick()
            assertTrue("the dialog shows the skin worn", vm.state.skinSettings === vm.skinOps.wal)
            assertTrue("it counts as a modal", vm.state.modalShowing)
        }
    }

    @Test
    fun `the dialog closes when its skin leaves the screen, parked under a music source's skin`() {
        val vm = wear("Leaving")
        seedOptions(vm)
        val look = vm.skinOps.wal!!
        (options(vm)[2] as AmpMenuItem.Action).onClick()
        assertTrue(vm.state.skinSettings === look)
        try {
            vm.skinOps.wear(BundledSkins.SPOT.id)
            awaitModern("the source's skin parks the modern look") { vm.skinOps.wal == null }

            assertEquals(null, vm.state.skinSettings)
        } finally {
            look.release(save = false)
        }
    }

    @Test
    fun `the dialog lists the options grouped as the skin nests them, ticked as the skin has them`() {
        val vm = wear()
        seedOptions(vm)

        val (title, rows) = settingRows(vm.skinOps.wal!!.skinOptions)

        assertEquals(null, title)
        assertEquals(listOf("Shown ✓", "-", "Hidden", "# More", "  Inner ✓"), rows.map(::line))
    }

    @Test
    fun `a tap in the dialog flips the option, saved for the next start`() {
        val vm = wear()
        seedOptions(vm)
        val look = vm.skinOps.wal!!

        fun hidden() =
            settingRows(look.skinOptions)
                .second
                .filterIsInstance<SettingRow.Option>()
                .first { it.toggle.text == "Hidden" }
                .toggle
        look.toggleSkinOption(hidden().key)

        awaitModern("the next frame ticks it") { hidden().checked }
        val prefs = app.getSharedPreferences(WalSettings.PREFS, 0)
        ShadowLooper.idleMainLooper(WalLook.SAVE_DELAY_MS + 100, TimeUnit.MILLISECONDS)
        awaitModern("it is saved") { prefs.getString(look.id, null)?.contains("\"&Hidden\":\"1\"") == true }
    }

    @Test
    fun `a skin whose options are one group opens it, its name the title line and its groups the headings`() {
        val vm = wear()
        seedSkinOptions(vm.skinOps.wal!!) { rt ->
            val drawers = rt.newConfigItem("Drawers", "{0D2C3B4A-0000-0000-0000-000000000003}")
            drawers.option("Animate", "0")
            val modern = rt.newConfigItem("Modern", "{0D2C3B4A-0000-0000-0000-000000000002}")
            modern.option("Drawers", "{0D2C3B4A-0000-0000-0000-000000000003}")
            modern.option("Ticker", "1")
            rt.optionsItem().option("Modern", "{0D2C3B4A-0000-0000-0000-000000000002}")
        }

        val (title, rows) = settingRows(vm.skinOps.wal!!.skinOptions)

        assertEquals("Modern", title)
        assertEquals(listOf("# Drawers", "  Animate", "Ticker ✓"), rows.map(::line))
    }

    /** A dialog row in short: a heading as "# Name", an option ticked with "✓", two spaces per group level inside a heading. */
    private fun line(row: SettingRow): String =
        when (row) {
            is SettingRow.Heading -> "  ".repeat(row.depth) + "# " + row.text
            is SettingRow.Option -> "  ".repeat(row.depth) + row.toggle.text + if (row.toggle.checked) " ✓" else ""
            SettingRow.Divider -> "-"
        }

    @Test
    fun `no Skin Settings with a classic skin, or a modern one that lists no options`() {
        assertFalse("classic", "Skin Settings..." in shape(options(testViewModel())))

        val none = wear("None")
        assertFalse("lists none", "Skin Settings..." in shape(options(none)))
        seedSkinOptions(none.skinOps.wal!!) { rt ->
            rt.optionsItem().makiCall("getattribute", listOf(Value.Str("Only read")))
        }
        assertFalse("an attribute only read", "Skin Settings..." in shape(options(none)))
    }

    @Test
    fun `no Skin Settings before the modern skin has loaded`() {
        val vm = wear("Early", started = false)

        assertTrue("its look is on screen", vm.skinOps.wal != null)
        assertFalse("Skin Settings..." in shape(options(vm)))
    }

    @Test
    fun `no Skin Settings with a modern skin parked under a music source's skin`() {
        val vm = wear("Parked")
        seedOptions(vm)
        val look = vm.skinOps.wal!!
        assertTrue("on screen it offers them", "Skin Settings..." in shape(options(vm)))
        try {
            vm.skinOps.wear(BundledSkins.SPOT.id)
            awaitModern("the source's skin parks the modern look") { vm.skinOps.wal == null }

            assertFalse("Skin Settings..." in shape(options(vm)))
        } finally {
            look.release(save = false)
        }
    }
}
