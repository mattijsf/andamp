// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rules on their own, without a backend. */
class TransportRulesTest {
    private val rows = List(3) { Track("t$it", "Artist", "Title $it", 60_000) }

    private fun at(
        index: Int,
        transport: Transport = Transport.Playing,
        positionMs: Long = 0,
    ) = BackendState(queue = rows, currentIndex = index, transport = transport, positionMs = positionMs)

    @Test
    fun `play from stopped starts where a seek while stopped left it`() {
        val sought = TransportRules.seekTo(at(1, Transport.Stopped), 30_000)

        val playing = TransportRules.play(sought)

        assertEquals(Transport.Playing, playing.transport)
        assertEquals(30_000L, playing.positionMs)
    }

    @Test
    fun `stop and play still starts from the top`() {
        val stopped = TransportRules.stop(at(1, positionMs = 30_000))

        assertEquals(0L, TransportRules.play(stopped).positionMs)
    }

    @Test
    fun `play while playing restarts the track`() {
        assertEquals(0L, TransportRules.play(at(1, positionMs = 30_000)).positionMs)
    }

    @Test
    fun `a track ending with stop after current stops on that track`() {
        val after = TransportRules.trackEnded(at(1), pickShuffled = { 2 }, stopAfterCurrent = true)

        assertEquals(Transport.Stopped, after.transport)
        assertEquals(1, after.currentIndex)
        assertEquals(0L, after.positionMs)
    }

    @Test
    fun `stop after current wins over shuffle and repeat`() {
        val after =
            TransportRules.trackEnded(at(2).copy(shuffle = true, repeat = true), pickShuffled = { 0 }, stopAfterCurrent = true)

        assertEquals(Transport.Stopped, after.transport)
        assertEquals(2, after.currentIndex)
    }

    @Test
    fun `the end of the queue without repeat stops at its head`() {
        val after = TransportRules.trackEnded(at(2, positionMs = 59_000), pickShuffled = { 0 })

        assertEquals(Transport.Stopped, after.transport)
        assertEquals(0, after.currentIndex)
        assertEquals(0L, after.positionMs)
    }

    @Test
    fun `playAt on an empty queue changes nothing`() {
        val empty = BackendState()

        assertEquals(empty, TransportRules.playAt(empty, 0))
    }

    @Test
    fun `moving on puts a station back to its name`() {
        val station = Track("s0", "Muse", "Hexagons", 0, defaultName = "Radio Example", isStream = true)
        val state = BackendState(queue = listOf(station) + rows, transport = Transport.Playing)

        val moved = TransportRules.next(state)

        assertEquals(1, moved.currentIndex)
        assertEquals("Radio Example", moved.queue[0].title)
        assertEquals("", moved.queue[0].artist)
    }

    @Test
    fun `a row that is not a station is left as it is`() {
        val file = Track("f0", "Muse", "Hexagons", 60_000, defaultName = "hexagons.mp3")

        assertEquals(file, file.atRest())
    }
}
