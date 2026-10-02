// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlaylistFileOps.healRestoredEntries] through its wiring: it reads the backend's queue
 * (at launch the render mirror still holds the demo playlist) and applies what it found
 * to the queue as it stands after the lookups.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HealWiringTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val dead = Track("dead", "a", "song", 200_000, uri = "content://gone/document/song.mp3")
    private val alive = Track("alive", "a", "other", 100_000, uri = "content://media/external/audio/media/7")
    private val found = "content://media/external/audio/media/42"

    private class FakeBackend(
        tracks: List<Track>,
    ) : PlaybackBackend {
        val flow = MutableStateFlow(BackendState(queue = tracks))
        override val state: StateFlow<BackendState> get() = flow
        override val capabilities = Capabilities(canSeek = true, canEditQueue = true)

        override fun setQueue(
            tracks: List<Track>,
            startIndex: Int,
        ) {
            flow.value = flow.value.copy(queue = tracks, currentIndex = startIndex)
        }

        override fun play() = Unit

        override fun pause() = Unit

        override fun stop() = Unit

        override fun next() = Unit

        override fun previous() = Unit

        override fun playAt(index: Int) = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun setVolume(fraction: Float) = Unit

        override fun setShuffle(enabled: Boolean) = Unit

        override fun setRepeat(enabled: Boolean) = Unit

        override fun release() = Unit
    }

    private fun opsFor(
        backend: FakeBackend,
        state: WinampState,
        library: MediaStoreAudio,
    ) = PlaylistFileOps(
        app,
        state,
        PlayerFacade(backend),
        PlaylistStore(app),
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        library,
        MediaFiles(app, library),
        io = Dispatchers.Unconfined,
    )

    /** Always finds the same replacement. */
    private class FakeLibrary(
        app: Application,
    ) : MediaStoreAudio(app) {
        var lookups = 0

        override fun hasPermission() = true

        override fun findByName(
            displayName: String,
            durationMs: Long,
        ): android.net.Uri? {
            lookups++
            return android.net.Uri.parse("content://media/external/audio/media/42")
        }
    }

    @Test
    fun `the backend's queue is the one healed`() {
        val backend = FakeBackend(listOf(dead))
        // as at launch: the mirror still holds the demo tracks
        val state = WinampState()
        val ops = opsFor(backend, state, FakeLibrary(app))

        runBlocking { ops.healRestoredEntries() }

        assertEquals(
            "the restored row points at the found file",
            found,
            backend.flow.value.queue
                .single()
                .uri,
        )
    }

    @Test
    fun `a track added while the lookups run is kept`() {
        val backend = FakeBackend(listOf(dead))
        val state = WinampState()
        val library =
            object : MediaStoreAudio(app) {
                override fun hasPermission() = true

                override fun findByName(
                    displayName: String,
                    durationMs: Long,
                ): android.net.Uri {
                    // the listener adds a track while the library is being queried
                    backend.setQueue(backend.flow.value.queue + alive, 0)
                    return android.net.Uri.parse(found)
                }
            }
        val ops = opsFor(backend, state, library)

        runBlocking { ops.healRestoredEntries() }

        val queue = backend.flow.value.queue
        assertEquals("a track added during the lookups is kept", listOf("dead", "alive"), queue.map { it.id })
        assertEquals("the dead row is healed", found, queue.first().uri)
    }
}
