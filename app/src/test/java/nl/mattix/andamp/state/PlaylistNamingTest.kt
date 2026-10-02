// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The filename LIST > SAVE LIST offers the system's save dialog; the listener can type over it. */
class PlaylistNamingTest {
    private val day = LocalDate.of(2026, 8, 12)

    private fun track(
        artist: String,
        title: String = "t",
    ) = Track(id = title, artist = artist, title = title, durationMs = 1000, uri = "content://x/$title")

    @Test
    fun `a mixed queue is named for the app and the day`() {
        val name = PlaylistNaming.fileName(listOf(track("A"), track("B")), day)

        assertEquals("Andamp playlist 2026-08-12.m3u", name)
    }

    @Test
    fun `a queue by one artist is named after them`() {
        val name = PlaylistNaming.fileName(listOf(track("Autechre", "a"), track("Autechre", "b")), day)

        assertEquals("Autechre 2026-08-12.m3u", name)
    }

    @Test
    fun `two saves on different days do not collide`() {
        val first = PlaylistNaming.fileName(listOf(track("A")), day)
        val second = PlaylistNaming.fileName(listOf(track("A")), day.plusDays(1))

        assertTrue("saves on different days get different names", first != second)
    }

    @Test
    fun `characters a filesystem would refuse are replaced`() {
        val name = PlaylistNaming.fileName(listOf(track("AC/DC: live?")), day)

        assertTrue("the name holds no refused characters: $name", name.none { it == '/' || it == ':' || it == '?' })
        assertTrue(name.endsWith(PlaylistNaming.EXTENSION))
    }

    @Test
    fun `an empty queue and a nameless artist still get a usable name`() {
        assertEquals("Andamp playlist 2026-08-12.m3u", PlaylistNaming.fileName(emptyList(), day))
        assertEquals("Andamp playlist 2026-08-12.m3u", PlaylistNaming.fileName(listOf(track("")), day))
    }

    @Test
    fun `an absurd artist name is trimmed rather than passed on whole`() {
        val name = PlaylistNaming.fileName(listOf(track("x".repeat(300))), day)

        assertTrue("the name stays under 80 characters: ${name.length}", name.length < 80)
    }

    @Test
    fun `a typed name becomes a filename, with or without the extension`() {
        assertEquals("roadtrip.m3u", PlaylistNaming.withExtension("roadtrip"))
        assertEquals("roadtrip.m3u", PlaylistNaming.withExtension("roadtrip.m3u"))
        assertEquals("roadtrip.m3u", PlaylistNaming.withExtension("  roadtrip  "))
    }

    @Test
    fun `a typed name cannot escape the folder it is saved into`() {
        val name = PlaylistNaming.withExtension("../../etc/passwd")

        assertTrue("the name holds no path separators: $name", name.none { it == '/' || it == '\\' })
        assertTrue(name.endsWith(PlaylistNaming.EXTENSION))
    }

    @Test
    fun `an empty answer still names the file something`() {
        assertEquals("Andamp playlist.m3u", PlaylistNaming.withExtension("   "))
    }
}
