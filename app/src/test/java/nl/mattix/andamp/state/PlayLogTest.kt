// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The listener's play log, and when a track counts as played. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayLogTest {
    private val log = PlayLog(ApplicationProvider.getApplicationContext<Application>(), null)

    private fun track(
        title: String,
        artist: String = "Portishead",
        uri: String = "content://media/$title",
        ms: Long = 240_000,
    ) = Track("id-$title-$uri", artist, title, ms, uri = uri)

    @Test
    fun `the song played most comes first, and the one played last keeps its copy`() {
        log.played(track("Roads"), at = 1)
        log.played(track("Glory Box"), at = 2)
        log.played(track("Roads", uri = "example:track:roads"), at = 3)

        val most = log.mostPlayed(10)

        assertEquals(listOf("Roads" to 2, "Glory Box" to 1), most.map { it.track.title to it.plays })
        assertEquals("the copy played last is kept", "example:track:roads", most.first().track.uri)
        assertEquals(listOf("Roads", "Glory Box"), log.recentlyPlayed(10).map { it.track.title })
    }

    @Test
    fun `a song is its artist and title, whatever the case or the accents`() {
        log.played(track("Hoppípolla", artist = "Sigur Rós"), at = 1)
        log.played(track("hoppipolla", artist = "SIGUR ROS"), at = 2)

        assertEquals(1, log.mostPlayed(10).size)
        assertEquals(2, log.mostPlayed(10).single().plays)
    }

    @Test
    fun `not played lately is a song played more than once and not since`() {
        log.played(track("Roads"), at = 1)
        log.played(track("Roads"), at = 2)
        log.played(track("Glory Box"), at = 3)
        log.played(track("Mysterons"), at = 4)
        log.played(track("Mysterons"), at = 100)

        assertEquals(listOf("Roads"), log.notLately(before = 50, limit = 10).map { it.track.title })
    }

    @Test
    fun `a track counts once it played half its length, or four minutes of a long one`() {
        val counted = mutableListOf<String>()
        val counter = PlayCounter { counted += it.title }
        val short = track("Roads", ms = 200_000)
        val long = track("Long", ms = 20 * 60_000)

        counter.observe(short, 99_000, playing = true)
        assertEquals(emptyList<String>(), counted)
        counter.observe(short, 100_000, playing = true)
        counter.observe(short, 150_000, playing = true)
        assertEquals("once per play", listOf("Roads"), counted)

        counter.observe(long, 4 * 60_000L, playing = true)
        assertEquals(listOf("Roads", "Long"), counted)
    }

    @Test
    fun `a track played again from the top is another play, and a station is no play`() {
        val counted = mutableListOf<String>()
        val counter = PlayCounter { counted += it.title }
        val roads = track("Roads", ms = 200_000)

        counter.observe(roads, 150_000, playing = true)
        counter.observe(roads, 1_000, playing = true)
        counter.observe(roads, 120_000, playing = true)
        counter.observe(Track("st", "", "Radio", 0, uri = "http://radio.example/live", isStream = true), 600_000, playing = true)

        assertEquals(listOf("Roads", "Roads"), counted)
    }

    @Test
    fun `a paused track does not count`() {
        val counted = mutableListOf<String>()
        val counter = PlayCounter { counted += it.title }

        counter.observe(track("Roads", ms = 200_000), 150_000, playing = false)

        assertEquals(emptyList<String>(), counted)
    }
}
