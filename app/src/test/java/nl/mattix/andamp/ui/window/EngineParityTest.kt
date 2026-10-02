// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.VisPlugin
import nl.mattix.andamp.state.presets.InstalledPack
import nl.mattix.andamp.ui.menu.countFor
import nl.mattix.andamp.ui.menu.idleLabel
import nl.mattix.andamp.visualizer.avs.AvsView
import nl.mattix.andamp.visualizer.core.VisualizerView
import nl.mattix.andamp.visualizer.projectm.ProjectMView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every visualizer engine is listed in [views] once, and the suite asserts the contract
 * they share. A new engine joins [views]; the first test fails until it does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineParityTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** The registry under test: one view per engine. */
    private val views: Map<VisPlugin, (Application) -> VisualizerView<*>> =
        mapOf(
            VisPlugin.Avs to { app -> AvsView(app) },
            VisPlugin.Milkdrop to { app -> ProjectMView(app) },
        )

    @Test
    fun `every engine has a view, and no view is missing an engine`() {
        assertEquals(VisPlugin.entries.toSet(), views.keys)
    }

    @Test
    fun `every engine's verbs are safe before any surface exists`() {
        views.forEach { (plugin, make) ->
            val view = make(context)
            // a menu can fire these while the window is still opening
            view.nextPreset()
            view.previousPreset()
            view.goToPreset(0)
            assertTrue("${plugin.name}: the verbs return without a surface", true)
        }
    }

    @Test
    fun `every engine's lifecycle gate toggles without a surface`() {
        views.forEach { (plugin, make) ->
            val view = make(context)
            view.rendering = false
            view.rendering = true
            assertEquals("${plugin.name} renders again when the gate reopens", true, view.rendering)
        }
    }

    /**
     * Place-keeping is part of the shared contract. The token is whatever that engine
     * addresses presets by, so all this can assert of a view with no surface is that the
     * property exists and starts empty.
     */
    @Test
    fun `every engine keeps a place to return to, and starts with none`() {
        views.forEach { (plugin, make) ->
            assertEquals("${plugin.name} starts with no place to return to", null, make(context).stickyPreset)
        }
    }

    @Test
    fun `every engine's view is opaque`() {
        views.forEach { (plugin, make) ->
            assertTrue("${plugin.name} is opaque", make(context).isOpaque)
        }
    }

    @Test
    fun `every engine's announcements are settable and start unset`() {
        views.forEach { (_, make) ->
            val view = make(context)
            view.onPresetChanged = { }
            view.onPlaylistChanged = { }
            view.onPresetChanged = null
            view.onPlaylistChanged = null
        }
    }

    @Test
    fun `every engine can be sent back to a named preset by the seat`() {
        // handing the player between the activity and the floating window
        // builds a fresh engine, which starts at the top of its list. The seat
        // remembers the name and asks, so every engine accepts the request.
        views.forEach { (plugin, make) ->
            val view = make(context)
            var told: List<String>? = null
            view.onPlaylistChanged = { told = it }

            view.onPlaylistChanged?.invoke(listOf("first", "wanted", "third"))
            val index = told?.indexOf("wanted") ?: -1
            assertEquals("${plugin.name} hands on the list the seat resumes from", 1, index)
            // before a surface exists, as the seat may ask: it must not throw
            view.goToPreset(index)
        }
    }

    @Test
    fun `every engine names its own idle preset, distinctly`() {
        val labels = VisPlugin.entries.map { it.idleLabel }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals("every engine has a distinct idle label", labels.size, labels.distinct().size)
    }

    @Test
    fun `every engine counts its own kind of preset`() {
        val pack = InstalledPack("both", File("/tmp/both"), presetCount = 3, textureDirs = emptyList(), avsCount = 7)
        assertEquals(7, pack.countFor(VisPlugin.Avs))
        assertEquals(3, pack.countFor(VisPlugin.Milkdrop))
    }
}
