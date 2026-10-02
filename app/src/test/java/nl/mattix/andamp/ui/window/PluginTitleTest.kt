// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.state.PresetImport
import nl.mattix.andamp.state.VisPlugin
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the plug-in window calls itself.
 *
 * A title bar says what the window is, so it names the plug-in - AVS, Milkdrop
 * - and keeps saying it. A preset name would rename the window every few
 * seconds; the preset is read in the Visualization menu and the preset
 * browser instead.
 */
class PluginTitleTest {
    private fun state(
        plugin: VisPlugin,
        preset: String? = null,
    ) = WinampState().apply {
        visPlugin = plugin
        presetName = preset
    }

    @Test
    fun `AVS says AVS, whatever preset is running`() {
        assertEquals("AVS", milkdropTitle(state(VisPlugin.Avs)))
        assertEquals("AVS", milkdropTitle(state(VisPlugin.Avs, preset = "ELVISREALDZ EROG")))
    }

    @Test
    fun `Milkdrop says Milkdrop, whatever preset is running`() {
        assertEquals("Milkdrop", milkdropTitle(state(VisPlugin.Milkdrop)))
        assertEquals("Milkdrop", milkdropTitle(state(VisPlugin.Milkdrop, preset = "Geiss - Spiral")))
    }

    @Test
    fun `an import shows its progress in the title`() {
        val importing = PresetImport(name = "pack.zip", filesWritten = 12)

        assertEquals("Importing pack.zip... 12", milkdropTitle(state(VisPlugin.Avs), importing))
    }

    @Test
    fun `a pack with nothing in it says so`() {
        val failed = PresetImport(name = "pack.zip", filesWritten = 0, failed = true)

        assertEquals("pack.zip: not presets", milkdropTitle(state(VisPlugin.Avs), failed))
    }
}
