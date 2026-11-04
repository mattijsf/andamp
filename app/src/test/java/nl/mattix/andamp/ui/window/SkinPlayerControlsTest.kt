// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skinplayer.Entry
import nl.mattix.andamp.skinplayer.FakeSkinPlayer
import nl.mattix.andamp.skinplayer.PlayerState
import nl.mattix.andamp.skinplayer.SkinPlayer
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classic windows over any player: their controls reach the player as its commands, and what
 * the player says reaches the state the windows draw.
 */
class SkinPlayerControlsTest {
    private var now = 0L
    private val songs =
        listOf(
            Entry("A - One", 100_000, mapOf("artist" to "A", "title" to "One")),
            Entry("Two", 200_000),
        )
    private val player = FakeSkinPlayer(songs, clock = { now })
    private val state = WinampState()
    private val controls = SkinPlayerControls(player, state)

    @Test
    fun `the playlist reaches the windows with artist and title from the tags`() {
        assertEquals(listOf("A", ""), state.playlist.map { it.artist })
        assertEquals(listOf("One", "Two"), state.playlist.map { it.title })
        assertEquals(listOf(100_000L, 200_000L), state.playlist.map { it.durationMs })
    }

    @Test
    fun `the transport buttons drive the player, and Pause pressed again plays on`() {
        controls.play()
        assertEquals(Transport.Playing, state.transport)
        controls.pause()
        assertEquals(Transport.Paused, state.transport)
        controls.pause()
        assertEquals(Transport.Playing, state.transport)
        controls.next()
        assertEquals(1, state.currentIndex)
        controls.stop()
        assertEquals(Transport.Stopped, state.transport)
        controls.playTrack(0)
        assertEquals(0, state.currentIndex)
        assertEquals(Transport.Playing, state.transport)
    }

    @Test
    fun `the clock follows the player's place on each sync`() {
        controls.play()
        now = 4_500
        controls.sync()
        assertEquals(4, state.currentTimeSec)
        controls.seekTo(0.5f)
        assertEquals("half of 100 s", 50, state.currentTimeSec)
    }

    @Test
    fun `a toggle sends the opposite of what the windows show`() {
        controls.toggleShuffle()
        controls.toggleRepeat()
        controls.toggleEq()
        assertTrue(player.state.shuffle && player.state.repeat)
        assertFalse(player.state.eqOn)
        assertTrue(state.shuffle && state.repeat && !state.eqOn)
        controls.toggleShuffle()
        assertFalse(player.state.shuffle)
    }

    @Test
    fun `the sliders reach the player in its units`() {
        controls.setVolume(0.42f)
        controls.setBalance(-30)
        controls.setBand(3, 50)
        controls.setPreamp(20)
        assertEquals(42, state.volume)
        assertEquals(-30, state.balance)
        assertEquals(50, state.eqBands[3])
        assertEquals(20, state.preamp)
    }

    @Test
    fun `the equalizer's reset centers the bands and leaves the preamp`() {
        controls.setBand(0, 10)
        controls.setPreamp(20)
        controls.resetBands()
        assertTrue(state.eqBands.all { it == PlayerState.EQ_CENTER })
        assertEquals(20, state.preamp)
    }

    @Test
    fun `an unchanged player leaves the windows' state alone`() {
        val playlist = state.playlist
        controls.sync()
        controls.setVolume(0.5f)
        assertSame("the playlist is made again only when it changes", playlist, state.playlist)
    }

    @Test
    fun `a player that answers later shows its answer at the next sync`() {
        val later = Later(FakeSkinPlayer(songs, clock = { now }))
        val windows = WinampState()
        val slow = SkinPlayerControls(later, windows)
        slow.play()
        assertEquals("not yet", Transport.Stopped, windows.transport)
        later.apply()
        slow.sync()
        assertEquals(Transport.Playing, windows.transport)
    }

    @Test
    fun `a stream connecting shows as playing with the connecting lamp`() {
        val connecting =
            object : SkinPlayer by player {
                override val state = PlayerState(transport = nl.mattix.andamp.skinplayer.Transport.CONNECTING, playlist = songs)
            }
        val windows = WinampState()
        SkinPlayerControls(connecting, windows)
        assertEquals(Transport.Playing, windows.transport)
        assertTrue(windows.connecting)
    }

    /** A player whose transport commands wait for [apply]. */
    private class Later(
        private val real: FakeSkinPlayer,
    ) : SkinPlayer by real {
        private val waiting = ArrayList<() -> Unit>()

        override fun play() {
            waiting += { real.play() }
        }

        fun apply() {
            waiting.forEach { it() }
            waiting.clear()
        }
    }
}
