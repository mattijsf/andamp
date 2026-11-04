// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.skinplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What every [SkinPlayer] must do, run against an implementation by a subclass: [player] makes one
 * with a playlist, and [settle] waits until the commands given so far have taken effect (nothing
 * for a player that applies them at once).
 */
abstract class SkinPlayerContract {
    abstract fun player(playlist: List<Entry>): SkinPlayer

    open fun settle(player: SkinPlayer) = Unit

    private val songs = List(3) { Entry("Song $it", 200_000L + it) }

    private fun fresh() = player(songs).also { settle(it) }

    private fun SkinPlayer.after(command: SkinPlayer.() -> Unit): PlayerState {
        command()
        settle(this)
        return state
    }

    @Test
    fun `the transport plays, pauses and stops`() {
        val p = fresh()
        assertEquals(Transport.PLAYING, p.after { play() }.transport)
        assertEquals(Transport.PAUSED, p.after { pause() }.transport)
        assertEquals(Transport.STOPPED, p.after { stop() }.transport)
    }

    @Test
    fun `a picked entry becomes current and plays`() {
        val p = fresh()
        val s = p.after { playTrack(2) }
        assertEquals(2, s.currentIndex)
        assertEquals(Transport.PLAYING, s.transport)
        assertEquals(songs[2], s.current)
    }

    @Test
    fun `the version moves on each change and only then`() {
        val p = fresh()
        val v0 = p.state.version
        val v1 = p.after { setVolume(40) }.version
        assertNotEquals(v0, v1)
        assertEquals("the same value again changes nothing", v1, p.after { setVolume(40) }.version)
    }

    @Test
    fun `a snapshot keeps what it read`() {
        val p = fresh()
        val before = p.state
        val bands = before.eqBands
        p.after {
            setVolume(10)
            setEqBand(0, 60)
        }
        assertEquals(PlayerState().volume, before.volume)
        assertSame(bands, before.eqBands)
        assertEquals(PlayerState.EQ_CENTER, before.eqBands[0])
    }

    @Test
    fun `values outside their range are clamped`() {
        val p = fresh()
        assertEquals(PlayerState.VOLUME_MAX, p.after { setVolume(250) }.volume)
        assertEquals(0, p.after { setVolume(-5) }.volume)
        assertEquals(-PlayerState.BALANCE_MAX, p.after { setBalance(-300) }.balance)
        assertEquals(PlayerState.EQ_MAX, p.after { setPreamp(99) }.preamp)
        assertEquals(0, p.after { setEqBand(3, -1) }.eqBands[3])
    }

    @Test
    fun `for the volume and the equalizer only the latest value counts`() {
        val p = fresh()
        val s =
            p.after {
                setVolume(10)
                setVolume(20)
                setEqBand(1, 5)
                setEqBand(1, 50)
            }
        assertEquals(20, s.volume)
        assertEquals(50, s.eqBands[1])
    }

    @Test
    fun `switches and the equalizer reset`() {
        val p = fresh()
        val s =
            p.after {
                setShuffle(true)
                setRepeat(true)
                setEqEnabled(false)
                setEqBand(4, 10)
                setPreamp(10)
            }
        assertTrue(s.shuffle && s.repeat && !s.eqOn)
        val reset = p.after { resetEq() }
        assertEquals(List(PlayerState.EQ_BANDS) { PlayerState.EQ_CENTER }, reset.eqBands)
        assertEquals(PlayerState.EQ_CENTER, reset.preamp)
    }

    @Test
    fun `a listener hears each change until it is closed`() {
        val p = fresh()
        var heard = 0
        val handle = p.addListener { heard++ }
        p.after { setVolume(11) }
        assertTrue("heard the change", heard > 0)
        handle.close()
        val before = heard
        p.after { setVolume(12) }
        assertEquals("closed, it hears nothing more", before, heard)
    }

    @Test
    fun `a seek moves the position within the song`() {
        val p = fresh()
        if (!p.capabilities.seek) return
        p.after { seekTo(5_000) }
        assertTrue(p.positionMs() >= 5_000)
        p.after { seekTo(-10) }
        assertTrue(p.positionMs() >= 0)
    }
}
