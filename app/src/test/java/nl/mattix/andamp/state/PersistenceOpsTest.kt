// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.IntOffset
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [PersistenceOps.start] restores first and collects after.
 *
 * Every writer drops its first emission, which is the value just restored. A
 * writer started before the restore would drop the default instead, and its
 * next emission would write the default over what was stored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistenceOpsTest {
    private lateinit var app: Application
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        listOf("windows", "transport", "visuals").forEach {
            app
                .getSharedPreferences(it, 0)
                .edit()
                .clear()
                .commit()
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    private lateinit var backend: MockBackend

    private fun opsFor(state: WinampState): PersistenceOps {
        backend = MockBackend(FakeTracks.tracks, scope)
        val facade = PlayerFacade(backend)
        return PersistenceOps(
            state,
            facade,
            scope,
            PlaylistStore(app),
            TransportStore(app),
            WindowStore(app),
            VisualsStore(app),
            io = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `what was left on disk is on screen once it has started`() {
        WindowStore(app).save(
            WindowLayoutMemory(
                placements = mapOf(WindowStore.LIBRARY to WindowPlacement(40, 90, 7, cols = 2)),
                order = emptyList(),
            ),
        )
        val state = WinampState()

        opsFor(state).start()

        assertEquals(IntOffset(40, 90), state.libraryOffset)
        assertEquals(7, state.libraryRows)
        assertEquals(2, state.libraryCols)
    }

    /**
     * A first launch gives the playlist [WindowStore.PLAYLIST_START_SEGMENTS]
     * segments. Null segments mean "fill what is left", which is kept for a
     * listener who sized the playlist that way.
     */
    @Test
    fun `a fresh install starts with a playlist that does not fill the screen`() {
        val state = WinampState()

        opsFor(state).start()

        assertEquals(WindowStore.PLAYLIST_START_SEGMENTS, state.plSegments)
    }

    @Test
    fun `a playlist that was left filling the screen comes back that way`() {
        WindowStore(app).save(
            WindowLayoutMemory(placements = mapOf(WindowStore.PLAYLIST to WindowPlacement(size = null))),
        )
        val state = WinampState()

        opsFor(state).start()

        assertNull(state.plSegments)
    }

    /** A writer started before the restore would overwrite this placement. */
    @Test
    fun `starting the writers does not write the defaults over the memory`() {
        val store = WindowStore(app)
        store.save(
            WindowLayoutMemory(
                placements = mapOf(WindowStore.MAIN to WindowPlacement(11, 22)),
                order = emptyList(),
            ),
        )

        opsFor(WinampState()).start()

        assertEquals(IntOffset(11, 22), store.load().offsetOf(WindowStore.MAIN))
    }

    /** Shuffle and repeat are restored through the facade, so they are read from the backend. */
    @Test
    fun `the transport comes back to what it was set to`() {
        TransportStore(app).saveSettings(TransportState(shuffle = true, repeat = true))

        opsFor(WinampState()).start()

        assertTrue(backend.state.value.shuffle)
        assertTrue(backend.state.value.repeat)
    }

    /** The position within the track is not restored. */
    @Test
    fun `a session comes back at the top of its track, wherever the last one was`() {
        val ops = opsFor(WinampState())
        backend.play()
        backend.seekTo(3_000)
        ops.start()
        backend.stop()

        opsFor(WinampState()).start()

        assertEquals(0L, backend.state.value.positionMs)
        assertEquals(Transport.Stopped, backend.state.value.transport)
    }

    @Test
    fun `an edit still inside the debounce is written when the app goes away`() {
        val state = WinampState()
        val ops = opsFor(state)
        ops.start()
        state.libraryOffset = IntOffset(5, 6)
        // the writer collects a snapshotFlow, which emits once apply notifications are sent
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()

        ops.flush()

        assertEquals(IntOffset(5, 6), WindowStore(app).load().offsetOf(WindowStore.LIBRARY))
    }

    /**
     * A backend that cannot edit its queue shows somebody else's. Saving it
     * would replace the stored playlist, and [PlaylistStore.retainOnly] would
     * then release every persisted document grant the remote rows do not name,
     * so the listener's local files would stop opening after a restart.
     */
    @Test
    fun `a source that does not own the queue leaves the stored playlist alone`() {
        val store = PlaylistStore(app)
        // rows the local source owns; a row is stored only with a uri, so these carry one
        val local =
            listOf(
                Track("local-1", "Neon Cassette", "Midnight Drive", 254_000, uri = "content://media/external/audio/media/1"),
                Track("local-2", "Pixel Foundry", "Dither Me This", 198_000, uri = "content://media/external/audio/media/2"),
            )
        store.save(local, 1)
        val before = store.load()
        val remote = FakeRemoteBackend()
        val ops =
            PersistenceOps(
                WinampState(),
                PlayerFacade(remote),
                scope,
                store,
                TransportStore(app),
                WindowStore(app),
                VisualsStore(app),
                io = Dispatchers.Unconfined,
            )
        ops.start()

        remote.playing(Track(id = "example-1", artist = "Muse", title = "Hysteria", durationMs = 1_000))
        ops.flush()

        assertEquals(before, store.load())
    }

    /**
     * What the saved lists and the bookmarks name is read from files. When that read fails,
     * nothing is known about what they need, and releasing on that would take away access
     * that only the listener can give back.
     */
    @Test
    fun `no access is given up while what is saved cannot be read`() {
        // no room, so a release would happen if the read had answered
        val store = PlaylistStore(app, room = 0)
        val folder = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic%2FKept")
        store.rememberTree(folder)
        val backend = MockBackend(FakeTracks.tracks, scope)
        val ops =
            PersistenceOps(
                WinampState(),
                PlayerFacade(backend),
                scope,
                store,
                TransportStore(app),
                WindowStore(app),
                VisualsStore(app),
                io = Dispatchers.Unconfined,
                namedElsewhere = { null },
            )
        ops.start()

        backend.setQueue(emptyList(), 0)
        ops.flush()

        assertEquals("the edit itself is stored", emptyList<Track>(), store.load()?.tracks)
        assertTrue(
            "the folder nothing in the queue names is still readable",
            app.contentResolver.persistedUriPermissions.any { it.uri == folder },
        )
    }

    /** A backend that cannot edit its queue, as a remote source's cannot. */
    private class FakeRemoteBackend : PlaybackBackend {
        private val _state = MutableStateFlow(BackendState(queue = emptyList()))
        override val state: StateFlow<BackendState> = _state
        override val capabilities = Capabilities(canSeek = true, canEditQueue = false)

        fun playing(track: Track) {
            _state.value = BackendState(queue = listOf(track), positionMs = 30_000)
        }

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

        override fun seekTo(positionMs: Long) = Unit

        override fun setVolume(fraction: Float) = Unit

        override fun setShuffle(enabled: Boolean) = Unit

        override fun setRepeat(enabled: Boolean) = Unit

        override fun release() = Unit
    }
}
