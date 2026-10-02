// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the plug-in window is showing survives a launch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VisualizerStoreTest {
    private val store = VisualizerStore(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun `nothing saved yet means AVS, no packs, in order`() {
        val loaded = store.load()

        assertEquals(VisPlugin.Avs, loaded.plugin)
        assertEquals(null, loaded.packFor(VisPlugin.Avs))
        assertEquals(null, loaded.packFor(VisPlugin.Milkdrop))
        assertEquals(false, loaded.shuffle)
    }

    @Test
    fun `every setting comes back, each engine keeping its own pack`() {
        val memory =
            VisualizerMemory(
                plugin = VisPlugin.Milkdrop,
                packs = mapOf(VisPlugin.Milkdrop to "Cream of the Crop", VisPlugin.Avs to "Winamp AVS"),
                shuffle = true,
            )
        store.save(memory)
        assertEquals(memory, store.load())
    }

    @Test
    fun `a cleared pack comes back as no pack`() {
        store.save(VisualizerMemory(packs = mapOf(VisPlugin.Avs to "Tiny")))
        store.save(VisualizerMemory(packs = mapOf(VisPlugin.Avs to null)))
        assertEquals(null, store.load().packFor(VisPlugin.Avs))
    }

    /** A file may hold one pack under the bare `pack` key, with no engine in the key. */
    @Test
    fun `the bare pack key is read as Milkdrop's`() {
        ApplicationProvider
            .getApplicationContext<Application>()
            .getSharedPreferences("visualizer", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("plugin", "Milkdrop")
            .putString("pack", "Cream of the Crop")
            .apply()

        val loaded = store.load()

        assertEquals("Cream of the Crop", loaded.packFor(VisPlugin.Milkdrop))
        assertEquals(null, loaded.packFor(VisPlugin.Avs))
    }

    /** A save removes the bare key, so it cannot bring back a pack cleared since. */
    @Test
    fun `a cleared pack stays cleared even with the bare key present`() {
        ApplicationProvider
            .getApplicationContext<Application>()
            .getSharedPreferences("visualizer", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("pack", "Old Pack")
            .apply()

        store.save(VisualizerMemory(packs = mapOf(VisPlugin.Milkdrop to null)))

        assertEquals(null, store.load().packFor(VisPlugin.Milkdrop))
    }

    @Test
    fun `an engine name this build does not know falls back to the default`() {
        ApplicationProvider
            .getApplicationContext<Application>()
            .getSharedPreferences("visualizer", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("plugin", "ProjectM")
            .apply()
        assertEquals(VisPlugin.Avs, store.load().plugin)
    }
}
