// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.playback.TransportRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * A backend that is not ExoPlayer, as the notification sees it: the title on
 * the lock screen, the position, and that pressing a button reaches the
 * backend.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackendPlayerTest {
    /** Winamp's transport rules over a queue, recording every verb it was told. */
    private class Fake(
        queue: List<Track> = SONGS,
    ) : PlaybackBackend {
        val told = mutableListOf<String>()
        val flow = MutableStateFlow(BackendState(queue = queue))

        /**
         * How often the state was read. Building the session's state reads
         * it; the collector subscribes once.
         */
        var reads = 0

        override val state: StateFlow<BackendState>
            get() {
                reads++
                return flow
            }

        override val capabilities = Capabilities(canSeek = true, canEditQueue = true)

        override fun setQueue(
            tracks: List<Track>,
            startIndex: Int,
        ) = Unit

        override fun play() {
            told += "play"
            flow.value = TransportRules.play(flow.value)
        }

        override fun pause() {
            told += "pause"
            flow.value = TransportRules.pause(flow.value)
        }

        override fun stop() {
            told += "stop"
        }

        override fun next() {
            told += "next"
        }

        override fun previous() {
            told += "previous"
        }

        override fun playAt(index: Int) {
            told += "playAt($index)"
        }

        override fun seekTo(positionMs: Long) {
            told += "seekTo($positionMs)"
        }

        override fun setVolume(fraction: Float) {
            told += "volume($fraction)"
            flow.value = flow.value.copy(volumeFraction = fraction)
        }

        override fun setShuffle(enabled: Boolean) {
            told += "shuffle($enabled)"
        }

        override fun setRepeat(enabled: Boolean) {
            told += "repeat($enabled)"
        }

        override fun release() = Unit
    }

    private fun playerOver(backend: Fake) = BackendPlayer(backend, CoroutineScope(Dispatchers.Main))

    @Test
    fun `the lock screen reads the track the backend is on`() {
        val backend = Fake()
        val player = playerOver(backend)

        assertEquals("Mysterons", player.mediaMetadata.title)
        assertEquals("Portishead", player.mediaMetadata.artist)
    }

    @Test
    fun `the position comes from the backend`() {
        val backend = Fake()
        val player = playerOver(backend)
        backend.flow.value = backend.flow.value.copy(transport = Transport.Playing, positionMs = 42_000)
        ShadowLooper.idleMainLooper()

        assertEquals(42_000L, player.currentPosition)
    }

    @Test
    fun `pressing play in the notification reaches the backend`() {
        val backend = Fake()
        val player = playerOver(backend)

        player.play()

        assertEquals(listOf("play"), backend.told)
    }

    @Test
    fun `the skip buttons reach the backend`() {
        val backend = Fake()
        val player = playerOver(backend)

        player.seekToNextMediaItem()
        player.seekToPreviousMediaItem()

        assertEquals(listOf("next", "previous"), backend.told)
    }

    /** A headset sends PAUSE whatever the player is doing, and Winamp's pause toggles. */
    @Test
    fun `a pause from a headset while paused leaves it paused`() {
        val backend = Fake()
        backend.flow.value = backend.flow.value.copy(transport = Transport.Paused, positionMs = 42_000)
        val player = playerOver(backend)

        player.pause()

        assertEquals("a pause while paused sends nothing to the backend", emptyList<String>(), backend.told)
        assertEquals(Transport.Paused, backend.flow.value.transport)
    }

    /** A car sends PLAY when it connects, and Winamp's play restarts a playing track. */
    @Test
    fun `a play from a car while playing does not restart the song`() {
        val backend = Fake()
        backend.flow.value = backend.flow.value.copy(transport = Transport.Playing, positionMs = 42_000)
        val player = playerOver(backend)

        player.play()

        assertEquals("a play while playing sends nothing to the backend", emptyList<String>(), backend.told)
        assertEquals(42_000L, backend.flow.value.positionMs)
    }

    @Test
    fun `a pause while playing pauses, and a play while paused carries on from there`() {
        val backend = Fake()
        backend.flow.value = backend.flow.value.copy(transport = Transport.Playing, positionMs = 42_000)
        val player = playerOver(backend)

        player.pause()
        ShadowLooper.idleMainLooper()
        player.play()

        assertEquals(listOf("pause", "play"), backend.told)
        assertEquals(Transport.Playing, backend.flow.value.transport)
        assertEquals(42_000L, backend.flow.value.positionMs)
    }

    @Test
    fun `the queue is shown but not editable from there`() {
        val backend = Fake()
        val player = playerOver(backend)

        assertEquals(2, player.mediaItemCount)
        assertTrue(
            "the session cannot change the queue",
            !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS),
        )
    }

    @Test
    fun `what the backend does shows up without being asked`() {
        val backend = Fake()
        val player = playerOver(backend)

        backend.flow.value = backend.flow.value.copy(currentIndex = 1, transport = Transport.Playing)
        ShadowLooper.idleMainLooper()

        assertEquals("Sour Times", player.mediaMetadata.title)
        assertTrue(player.playWhenReady)
    }

    /**
     * Media3 throws on a playlist with two rows of one uid, and the same album
     * can be enqueued twice.
     */
    @Test
    fun `the same songs enqueued twice are four rows`() {
        val backend = Fake()
        val player = playerOver(backend)
        ShadowLooper.idleMainLooper()

        backend.flow.value = backend.flow.value.copy(queue = SONGS + SONGS)
        ShadowLooper.idleMainLooper()

        assertEquals(4, player.mediaItemCount)
        assertEquals("the repeated row keeps the song's id", "t1", player.getMediaItemAt(2).mediaId)
    }

    @Test
    fun `a queue that holds a song twice opens as three rows`() {
        val backend = Fake(queue = SONGS + SONGS.take(1))

        val player = playerOver(backend)

        assertEquals(3, player.mediaItemCount)
    }

    /**
     * A station keeps its row and its id while the song it is playing changes,
     * as a folder's rows do while their tags arrive.
     */
    @Test
    fun `a station naming its next song reaches the lock screen`() {
        val backend = Fake()
        val player = playerOver(backend)
        ShadowLooper.idleMainLooper()

        val queue = backend.flow.value.queue
        backend.flow.value = backend.flow.value.copy(queue = listOf(queue[0].copy(title = "Roads")) + queue.drop(1))
        ShadowLooper.idleMainLooper()

        assertEquals("Roads", player.mediaMetadata.title)
    }

    @Test
    fun `a row with no artist has an empty artist`() {
        val backend = Fake(queue = listOf(Track("t1", "", "Untitled", 60_000, uri = "remote:track:t1")))

        val player = playerOver(backend)

        assertEquals("", player.mediaMetadata.artist.toString())
    }

    @Test
    fun `a song can be scrubbed from the lock screen`() {
        val backend = Fake()
        val player = playerOver(backend)

        player.seekTo(10_000)

        assertTrue(player.isCurrentMediaItemSeekable)
        assertEquals(listOf("seekTo(10000)"), backend.told)
    }

    /** The session refuses what the app's own position bar refuses: a scrub would reconnect the station. */
    @Test
    fun `a station cannot be scrubbed from the lock screen`() {
        val backend = Fake(queue = listOf(STATION))
        val player = playerOver(backend)

        player.seekTo(0, 5_000)

        assertFalse(player.isCurrentMediaItemSeekable)
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertEquals(emptyList<String>(), backend.told)
    }

    /** A stored playlist can hold a length for a station. */
    @Test
    fun `a station with a stored length has no duration and cannot be scrubbed`() {
        val backend = Fake(queue = listOf(STATION.copy(durationMs = 57_000)))

        val player = playerOver(backend)

        assertEquals(C.TIME_UNSET, player.duration)
        assertFalse(player.isCurrentMediaItemSeekable)
    }

    /**
     * Media3 fills a seek's index in from what it last read, which can be one
     * row behind the backend when a track has just ended. The seek stays
     * within the backend's current row.
     */
    @Test
    fun `a scrub as one track gives way to the next stays on the next`() {
        val backend = Fake()
        val player = playerOver(backend)
        ShadowLooper.idleMainLooper()
        assertEquals(0, player.currentMediaItemIndex)

        backend.flow.value = backend.flow.value.copy(currentIndex = 1, transport = Transport.Playing)
        player.seekTo(10_000)

        assertEquals(listOf("seekTo(10000)"), backend.told)
    }

    @Test
    fun `a volume a controller sets is the volume it reads back`() {
        val backend = Fake()
        val player = playerOver(backend)

        player.volume = 0.3f
        ShadowLooper.idleMainLooper()

        assertEquals(0.3f, player.volume, 0.001f)
    }

    /**
     * A position update must not rebuild the queue.
     *
     * Media3 reads the position through a supplier, so a position update needs
     * no invalidation. The test counts reads of the state, because a rebuilt
     * queue is still the same timeline.
     */
    @Test
    fun `a position update does not read the state again`() {
        val backend = Fake()
        val player = playerOver(backend)
        ShadowLooper.idleMainLooper()
        val before = backend.reads

        repeat(8) {
            backend.flow.value = backend.flow.value.copy(positionMs = backend.flow.value.positionMs + 250)
            ShadowLooper.idleMainLooper()
        }

        assertEquals("a clock tick does not read the state again", before, backend.reads)
        assertEquals(2_000L, player.currentPosition)
    }

    @Test
    fun `a queue that changed is rebuilt`() {
        val backend = Fake()
        val player = playerOver(backend)
        ShadowLooper.idleMainLooper()

        backend.flow.value =
            backend.flow.value.copy(
                queue =
                    backend.flow.value.queue
                        .take(1),
            )
        ShadowLooper.idleMainLooper()

        assertEquals(1, player.mediaItemCount)
    }

    private companion object {
        val SONGS =
            listOf(
                Track("t1", "Portishead", "Mysterons", 300_000, uri = "remote:track:t1"),
                Track("t2", "Portishead", "Sour Times", 250_000, uri = "remote:track:t2"),
            )

        val STATION = Track("s1", "", "Radio Example", 0, uri = "https://radio.example.org/stream", isStream = true)
    }
}
