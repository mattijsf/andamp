// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Preferences tells the listener about the audio permission, and the
 * button it offers: [LibraryAccess.ASKABLE] can be asked again,
 * [LibraryAccess.BLOCKED] only leads to settings.
 */
class LibraryAccessTest {
    @Test
    fun `granted has nothing left to offer`() {
        val access = LibraryAccess.of(granted = true, canAsk = false)

        assertEquals(LibraryAccess.GRANTED, access)
        assertNull("a granted permission offers no action", access.action)
    }

    @Test
    fun `refused but askable offers the prompt again`() {
        val access = LibraryAccess.of(granted = false, canAsk = true)

        assertEquals(LibraryAccess.ASKABLE, access)
        assertEquals("Allow", access.action)
    }

    @Test
    fun `refused for good offers the settings screen`() {
        val access = LibraryAccess.of(granted = false, canAsk = false)

        assertEquals(LibraryAccess.BLOCKED, access)
        assertEquals("Open settings", access.action)
    }

    @Test
    fun `every state's summary mentions a restart`() {
        LibraryAccess.entries.forEach { access ->
            assertNotNull(access.summary(NAME))
            assertTrue("${access.name}'s summary mentions a restart", access.summary(NAME).contains("restart"))
        }
    }

    @Test
    fun `a refused state's summary does not open as the granted one does`() {
        listOf(LibraryAccess.ASKABLE, LibraryAccess.BLOCKED).forEach {
            assertTrue("${it.name}: ${it.summary(NAME)}", !it.summary(NAME).startsWith("Andamp has"))
        }
    }

    @Test
    fun `the permission's name follows the Android version`() {
        assertEquals("Music and audio", MusicPermission.name(33))
        assertEquals("Files and media", MusicPermission.name(30))
        assertEquals("Storage", MusicPermission.name(26))
    }

    private companion object {
        const val NAME = "Music and audio"
    }
}
