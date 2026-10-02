// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.MixedQueueBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The player's lanes, as the sources on the phone come and go under it.
 *
 * Guards against a source installed after the player was built having no lane, and against
 * its rows being handed to the phone's player. The rig is built the way [PlaybackRoot]
 * builds the player: the phone's lane, and a lane per source followed off a list that
 * moves. The phone's player is a mock that records every row it is handed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceLanesTest {
    /** A player that records every row it is handed. */
    private class Handed(
        private val inner: MockBackend,
    ) : PlaybackBackend by inner {
        val rows = mutableSetOf<String>()
        var released = false

        override fun setQueue(
            tracks: List<Track>,
            startIndex: Int,
        ) {
            rows += tracks.map { it.id }
            inner.setQueue(tracks, startIndex)
        }

        override fun enqueue(tracks: List<Track>) {
            rows += tracks.map { it.id }
            inner.enqueue(tracks)
        }

        override fun release() {
            released = true
            inner.release()
        }
    }

    private class Pack(
        override val source: MusicSource,
        private val open: () -> PlaybackBackend?,
    ) : ExtraSource {
        override fun signedIn(context: Context) = true

        override fun backend(
            context: Context,
            scope: CoroutineScope,
        ): PlaybackBackend? = open()

        override fun browse(context: Context): BrowseSource? = null

        @Composable
        override fun Summary(signedIn: Boolean) = ""
    }

    /** What discovery has found right now, as the snapshot state [PackSources.found] is. */
    private var installed by mutableStateOf(emptyList<ExtraSource>())

    private val jellyfin = MusicSource("JELLYFIN", "Jellyfin")

    private val song = Track("song", "Artist", "On the phone", 2_000, uri = "content://media/1")
    private val album = Track("album", "Artist", "Also on the phone", 2_000, uri = "file:///sdcard/2.mp3")
    private val remote = Track("remote", "Artist", "On the server", 2_000, uri = "jellyfin:track:abc")

    private class Rig(
        val player: MixedQueueBackend,
        val phone: Handed,
        val lanes: SourceLanes,
    ) {
        var pack: Handed? = null
        var opened = 0
    }

    private fun TestScope.rig(vararg rows: Track): Rig {
        val phone = Handed(MockBackend(emptyList(), backgroundScope))
        val player = MixedQueueBackend(listOf(SourceLanes.phone { phone }), rows.toList(), 0, backgroundScope)
        val lanes = SourceLanes(player) { extra -> extra.backend(RuntimeEnvironment.getApplication(), backgroundScope) }
        lanes.follow(backgroundScope) { installed }
        runCurrent()
        return Rig(player, phone, lanes)
    }

    private fun TestScope.packFor(rig: Rig) =
        Pack(jellyfin) {
            rig.opened++
            Handed(MockBackend(emptyList(), backgroundScope)).also { rig.pack = it }
        }

    private fun TestScope.discover(now: List<ExtraSource>) {
        installed = now
        // PackSources sends the same notification after it writes its list
        Snapshot.sendApplyNotifications()
        runCurrent()
    }

    private val MixedQueueBackend.at get() = state.value.queue[state.value.currentIndex].id

    @Test
    fun `with no source but the phone's, the phone's own files play through the composite`() =
        runTest {
            val rig = rig(song, album)

            rig.player.play()

            assertEquals(Transport.Playing, rig.player.state.value.transport)
            assertEquals("song", rig.player.at)
            assertEquals(
                "the phone's player receives the whole run of phone rows",
                listOf("song", "album"),
                rig.phone.state.value.queue
                    .map { it.id },
            )
        }

    @Test
    fun `a source that appears after the player was built gets a lane, and its rows play`() =
        runTest {
            val rig = rig(remote, song)
            rig.player.play()
            assertEquals("a row with no lane is stepped over", "song", rig.player.at)

            discover(listOf(packFor(rig)))
            rig.player.playAt(0)

            assertEquals("remote", rig.player.at)
            assertEquals(Transport.Playing, rig.player.state.value.transport)
            assertEquals(
                Transport.Playing,
                rig.pack
                    ?.state
                    ?.value
                    ?.transport,
            )
        }

    @Test
    fun `a row of a source with no lane is never handed to the phone's player`() =
        runTest {
            val rig = rig(remote, song, remote.copy(id = "remote2"))

            rig.player.play()
            rig.player.next()
            rig.player.playAt(2)

            assertFalse(
                "the phone's player receives no remote row: ${rig.phone.rows}",
                "remote" in rig.phone.rows || "remote2" in rig.phone.rows,
            )
        }

    @Test
    fun `a source that goes loses its lane, and its rows go to nobody`() =
        runTest {
            val rig = rig(remote, song)
            discover(listOf(packFor(rig)))
            rig.player.play()
            val pack = rig.pack!!
            assertEquals("remote", rig.player.at)

            discover(emptyList())

            assertEquals(Transport.Stopped, rig.player.state.value.transport)
            assertTrue("the source's player is released", pack.released)
            rig.player.playAt(0)
            assertEquals("the source's row is stepped over", "song", rig.player.at)
            assertFalse("the phone's player receives no remote row", "remote" in rig.phone.rows)
        }

    @Test
    fun `a source's lane is made once when the list is read again`() =
        runTest {
            val rig = rig(remote, song)
            val pack = packFor(rig)
            discover(listOf(pack))
            rig.player.play()
            val playing = rig.pack!!

            // the list read again: another source arrived beside it
            discover(listOf(pack, Pack(MusicSource("MOOSE", "Moose")) { null }))

            assertEquals(1, rig.opened)
            assertFalse(playing.released)
            assertEquals(Transport.Playing, rig.player.state.value.transport)
            assertEquals("remote", rig.player.at)
        }

    @Test
    fun `a sign-out puts the source's player away, and a sign-in builds it again`() =
        runTest {
            val rig = rig(remote, song)
            discover(listOf(packFor(rig)))
            rig.player.play()
            val first = rig.pack!!

            rig.lanes.signedOut(jellyfin)

            assertTrue(first.released)
            assertEquals(Transport.Stopped, rig.player.state.value.transport)
            rig.player.playAt(0)
            assertEquals("the row plays on a new player in the same lane", "remote", rig.player.at)
            assertEquals(2, rig.opened)
        }
}
