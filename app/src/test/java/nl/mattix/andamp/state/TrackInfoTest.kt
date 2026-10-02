// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.TrackInfo
import nl.mattix.andamp.core.player.TrackInfoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Winamp's Alt+3 box. A source lists what it knows and blank lines are dropped; a source that
 * knows nothing leaves the lines the queue's own row can fill.
 */
class TrackInfoTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun track(
        title: String = "Shimmering Scars",
        artist: String = "Muse",
        ms: Long = 268_000,
        kbps: Int? = 192,
        khz: Int? = 44,
        uri: String? = "content://media/audio/7",
    ) = Track("t", artist, title, ms, kbps, khz, uri)

    private fun opsFor(
        state: WinampState,
        source: TrackInfoSource,
    ) = TrackInfoOps(state, source, scope)

    @Test
    fun `a source's lines are shown in the order it gave them`() {
        val state = WinampState()
        val source =
            TrackInfoSource {
                TrackInfo.of("Shimmering Scars", listOf("Format" to "audio/mpeg", "Bitrate" to "192 kbps"))
            }

        opsFor(state, source).show(track())

        assertEquals(listOf("Format", "Bitrate"), state.trackInfo?.lines?.map { it.label })
    }

    /** `TrackInfo.of` drops a line whose value is null or blank. */
    @Test
    fun `lines with nothing in them never reach the dialog`() {
        val info =
            TrackInfo.of(
                "Shimmering Scars",
                listOf("Format" to "audio/mpeg", "Genre" to null, "Composer" to "   ", "Year" to "2014"),
            )

        assertEquals(listOf("Format", "Year"), info?.lines?.map { it.label })
    }

    @Test
    fun `a block with nothing in it at all is no block`() {
        assertNull(TrackInfo.of("Nothing", listOf("Genre" to null, "Year" to "")))
    }

    /** A row with only a title, an artist and a length gets a box of those three lines. */
    @Test
    fun `a source that knows nothing still leaves the queue's own answer`() {
        val state = WinampState()

        opsFor(state, TrackInfoSource.None).show(track(kbps = null, khz = null, uri = null))

        val shown = assertNotNull("the box shows the queue's own lines", state.trackInfo).let { state.trackInfo!! }
        assertEquals(listOf("Title", "Artist", "Length"), shown.lines.map { it.label })
        assertEquals("4:28", shown.lines.first { it.label == "Length" }.value)
    }

    @Test
    fun `the queue's own answer carries what the row measured`() {
        val state = WinampState()

        opsFor(state, TrackInfoSource.None).show(track())

        val labels = state.trackInfo!!.lines.map { it.label }
        assertTrue("a local row knows its bitrate", "Bitrate" in labels)
        assertTrue("a local row knows its sample rate", "Sample rate" in labels)
    }

    @Test
    fun `what a source does say wins over the fallback`() {
        val state = WinampState()
        val source = TrackInfoSource { TrackInfo.of("From the file", listOf("Album" to "The Wow! Signal")) }

        opsFor(state, source).show(track())

        assertEquals("From the file", state.trackInfo?.heading)
        assertEquals(listOf("Album"), state.trackInfo?.lines?.map { it.label })
    }

    @Test
    fun `nothing selected and no row means nothing to describe`() {
        val state = WinampState()

        opsFor(state, TrackInfoSource.None).show(null)

        assertNull(state.trackInfo)
    }

    /** The playlist's verbs mean the selection; the row is only the fallback. */
    @Test
    fun `the selection is what gets described`() {
        val state = WinampState()
        state.playlist = listOf(track(title = "One"), track(title = "Two"), track(title = "Three"))
        state.selectedRows = setOf(2)

        val chosen = opsFor(state, TrackInfoSource.None).trackFor(row = 0)

        assertEquals("Three", chosen?.title)
    }

    @Test
    fun `with no selection the row the menu opened on is described`() {
        val state = WinampState()
        state.playlist = listOf(track(title = "One"), track(title = "Two"))

        val chosen = opsFor(state, TrackInfoSource.None).trackFor(row = 1)

        assertEquals("Two", chosen?.title)
    }
}
