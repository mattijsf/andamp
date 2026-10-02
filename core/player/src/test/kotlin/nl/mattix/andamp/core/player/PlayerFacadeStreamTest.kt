// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.PlaybackBackend
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A backend that can seek may hold a track that cannot: a fraction of an unknown length is not
 * a position, so the facade does not pass it down.
 */
class PlayerFacadeStreamTest {
    private class SeekRecorder(
        track: Track,
    ) : PlaybackBackend {
        val seeks = mutableListOf<Long>()
        override val state: StateFlow<BackendState> =
            MutableStateFlow(BackendState(queue = listOf(track), currentIndex = 0))
        override val capabilities = Capabilities(canSeek = true)

        override fun setQueue(
            tracks: List<Track>,
            startIndex: Int,
        ) = Unit

        override fun play() = Unit

        override fun pause() = Unit

        override fun stop() = Unit

        override fun next() = Unit

        override fun previous() = Unit

        override fun playAt(index: Int) = Unit

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
        }

        override fun setVolume(fraction: Float) = Unit

        override fun setShuffle(enabled: Boolean) = Unit

        override fun setRepeat(enabled: Boolean) = Unit

        override fun release() = Unit
    }

    @Test
    fun `seeking by fraction does nothing when the duration is unknown`() {
        val backend = SeekRecorder(Track("station:1", "", "Live", 0, uri = "http://example.com/s"))
        val facade = PlayerFacade(backend)

        facade.seekToFraction(0.5f)

        assertEquals(emptyList<Long>(), backend.seeks)
    }

    @Test
    fun `seeking by fraction seeks on a track with a known duration`() {
        val backend = SeekRecorder(Track("t1", "A", "T", 10_000))
        val facade = PlayerFacade(backend)

        facade.seekToFraction(0.5f)

        assertEquals(listOf(5_000L), backend.seeks)
    }
}
