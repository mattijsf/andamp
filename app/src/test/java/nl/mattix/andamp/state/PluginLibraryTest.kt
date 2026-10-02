// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.dsp.GraphSpec
import nl.mattix.andamp.core.plugin.PluginSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Where an installed plug-in lives: the file the listener picked is copied in, so the
 * original can be moved or deleted and the plug-in stays installed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginLibraryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun spec(
        id: String = "com.example.gain",
        name: String = "Gain",
    ) = PluginSpec(
        id = id,
        name = name,
        version = "1.2",
        author = "Somebody",
        about = "Makes it louder",
        params = emptyList(),
        graph = GraphSpec(sampleRate = 44_100, channels = 2, nodes = emptyList(), outputs = emptyList()),
    )

    private fun library() = PluginLibrary(app)

    @Test
    fun `an installed plug-in is a copy, and outlives the file it came from`() {
        val picked = File(app.cacheDir, "gain.lua").apply { writeText("-- gain") }
        library().save(picked.readText(), spec())

        picked.delete()

        assertEquals(listOf("-- gain"), library().sources())
    }

    @Test
    fun `what it says about itself is what the list shows`() {
        library().save("-- gain", spec())

        val entry = library().list().single()

        assertEquals("Gain", entry.name)
        assertEquals("1.2", entry.version)
        assertEquals("Somebody", entry.author)
        assertEquals("Makes it louder", entry.about)
        assertEquals("com.example.gain", entry.pluginId)
        assertTrue("the entry reports its size", entry.sizeBytes > 0)
    }

    @Test
    fun `the same file twice is one plug-in`() {
        library().save("-- gain", spec())
        library().save("-- gain", spec(name = "Gain, renamed"))

        assertEquals(1, library().list().size)
        assertEquals("Gain, renamed", library().list().single().name)
    }

    @Test
    fun `two plug-ins are two files`() {
        library().save("-- gain", spec())
        library().save("-- fuzz", spec(id = "com.example.fuzz", name = "Fuzz"))

        assertEquals(listOf("Fuzz", "Gain"), library().list().map { it.name })
    }

    @Test
    fun `removing one leaves nothing of it behind`() {
        val entry = library().save("-- gain", spec())
        library().save("-- fuzz", spec(id = "com.example.fuzz", name = "Fuzz"))

        library().delete(entry.id)

        assertEquals(listOf("Fuzz"), library().list().map { it.name })
        assertNull(library().read(entry.id))
        assertNull("the plug-in id is removed with it", library().pluginIdOf(entry.id))
    }

    @Test
    fun `what was installed is still there next launch`() {
        library().save("-- gain", spec())

        // a second instance is what a relaunch amounts to
        assertEquals(listOf("Gain"), library().list().map { it.name })
        assertEquals(listOf("-- gain"), library().sources())
    }
}
