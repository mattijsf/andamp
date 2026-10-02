// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The phone's library in Preferences: counted, and scanned one folder at a
 * time, reporting the folder it is on.
 *
 * The scanner and the count are injected, so the tests assert the order of the
 * steps and the text the page shows.
 */
class PhoneLibraryTest {
    private val folders = listOf(PhoneLibrary.Folder("Music", File("/m")), PhoneLibrary.Folder("Download", File("/d")))

    private fun stats(tracks: Int) = PhoneStats(tracks, 1, 1, 0, 0)

    @Test
    fun `a scan goes folder by folder, says where it is, then counts again and says what changed`() =
        runTest {
            val gates = folders.associate { it.path to CompletableDeferred<Unit>() }
            var counted = stats(10)
            var changed = 0
            val phone =
                PhoneLibrary(
                    scope = backgroundScope,
                    folders = { folders },
                    scanner = { gates.getValue(it).await() },
                    count = { counted },
                    onChanged = { changed++ },
                )
            phone.refresh()
            runCurrent()

            phone.scan()
            runCurrent()
            assertEquals(ScanProgress("Music", 0, 2), phone.scanning)

            gates.getValue(File("/m")).complete(Unit)
            runCurrent()
            assertEquals(ScanProgress("Download", 1, 2), phone.scanning)

            counted = stats(13)
            gates.getValue(File("/d")).complete(Unit)
            runCurrent()

            assertNull("the scan is finished", phone.scanning)
            assertEquals(13, phone.stats?.tracks)
            assertEquals("Looked in Music, Download: 3 new tracks.", phone.lastScan)
            assertEquals("the library window reads the index again", 1, changed)
        }

    @Test
    fun `a second press while scanning does not start a second scan`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var scans = 0
            val phone =
                PhoneLibrary(
                    scope = backgroundScope,
                    folders = { folders.take(1) },
                    scanner = {
                        scans++
                        gate.await()
                    },
                    count = { stats(1) },
                )

            phone.scan()
            runCurrent()
            phone.scan()
            runCurrent()

            assertEquals(1, scans)
        }

    @Test
    fun `the words a scan and a library are described in`() {
        assertEquals("Looked in Music: nothing new.", ScanProgress.outcome(stats(5), stats(5), listOf("Music")))
        assertEquals("Looked in Music: 1 new track.", ScanProgress.outcome(stats(5), stats(6), listOf("Music")))
        assertEquals("Looked in Music: 2 tracks fewer.", ScanProgress.outcome(stats(5), stats(3), listOf("Music")))
        assertEquals("No music folders on this phone: nothing new.", ScanProgress.outcome(stats(5), stats(5), emptyList()))
        assertEquals("Looked in Music. The library could not be read.", ScanProgress.outcome(stats(5), null, listOf("Music")))

        val library =
            PhoneStats(tracks = 1, artists = 2, albums = 1, durationMs = 3 * 86_400_000L + 4 * 3_600_000L, bytes = 4_509_715_660L)
        assertEquals("1 track · 2 artists · 1 album", library.summary)
        assertEquals("3 days 4 hours", library.listening)
        assertEquals("4.2 GB", library.size)
        assertEquals("52 minutes", PhoneStats(1, 1, 1, 52 * 60_000L, 0).listening)
    }
}
