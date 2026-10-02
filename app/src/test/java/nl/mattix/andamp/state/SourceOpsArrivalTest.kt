// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A source that arrives after the app has started. [SourceOps.follow] follows the list of
 * installed sources, so one installed while the app is open joins the sources offered and
 * one that is uninstalled leaves them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceOpsArrivalTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val moose = MusicSource("MOOSE", "Moose Music")

    /** What is installed right now, as snapshot state: the thing discovery moves. */
    private var installed by mutableStateOf(emptyList<ExtraSource>())

    private val standing = MutableStateFlow(SourceStanding.SIGNED_OUT)

    private val arriving = answering(standing)

    /** Something that answers for the one source, saying what [standing] says. */
    private fun answering(standing: Flow<SourceStanding>) =
        object : ExtraSource {
            override val source = moose

            override fun signedIn(context: Context) = false

            override fun accountChanges(context: Context): Flow<SourceStanding> = standing

            override fun backend(
                context: Context,
                scope: CoroutineScope,
            ): PlaybackBackend? = null

            override fun browse(context: Context): BrowseSource? = null

            @Composable
            override fun Summary(signedIn: Boolean) = ""
        }

    /** A library that lists nothing; no test here opens the window. */
    private object Empty : BrowseSource {
        override val capabilities =
            nl.mattix.andamp.core.model
                .BrowseCapabilities()
        override val available = true

        override suspend fun artists() = emptyList<nl.mattix.andamp.core.model.LibraryArtist>()

        override suspend fun albums(artistId: String?) = emptyList<nl.mattix.andamp.core.model.LibraryAlbum>()

        override suspend fun tracks(albumId: String) = emptyList<nl.mattix.andamp.core.model.Track>()
    }

    private fun opsOver(sources: LibrarySources): SourceOps {
        val loose = CoroutineScope(Dispatchers.Unconfined)
        val state = WinampState()
        return SourceOps(
            state,
            sources,
            LibraryOps(
                { Empty },
                PlayerFacade(MockBackend(emptyList(), loose)),
                state,
                loose,
                PlaylistLibrary(temp.newFolder("lists")),
                StationStore(java.io.File(temp.newFolder("radio"), "stations.m3u")),
            ),
            extras = { installed },
            io = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `a source installed while the app is open joins the list it was not in`() =
        runTest {
            val sources =
                LibrarySources(
                    LibrarySourceStore(app.getSharedPreferences("t", Context.MODE_PRIVATE)),
                    listOf(MusicSource.LOCAL),
                    emptySet(),
                )
            opsOver(sources).follow(app, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            assertFalse("the source is not offered before it is installed", moose in sources.present)

            installed = listOf(arriving)
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
            testScheduler.advanceUntilIdle()

            assertTrue("the source that arrived is offered", moose in sources.present)
        }

    @Test
    fun `a source uninstalled while the app is open leaves the list`() =
        runTest {
            val sources =
                LibrarySources(
                    LibrarySourceStore(app.getSharedPreferences("t2", Context.MODE_PRIVATE)),
                    listOf(MusicSource.LOCAL),
                    emptySet(),
                )
            opsOver(sources).follow(app, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            installed = listOf(arriving)
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
            testScheduler.advanceUntilIdle()

            installed = emptyList()
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
            testScheduler.advanceUntilIdle()

            assertFalse("the source that went is not offered", moose in sources.present)
        }

    /**
     * A pack is removed and its successor installed, under another package and the same id.
     * The removed one goes on reporting [SourceStanding.ABSENT].
     */
    @Test
    fun `another app answering for the same source is the one listened to from then on`() =
        runTest {
            val sources =
                LibrarySources(
                    LibrarySourceStore(app.getSharedPreferences("t3", Context.MODE_PRIVATE)),
                    listOf(MusicSource.LOCAL),
                    emptySet(),
                )
            opsOver(sources).follow(app, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            installed = listOf(arriving)
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
            testScheduler.advanceUntilIdle()

            val successor = MutableStateFlow(SourceStanding.READY)
            installed = listOf(answering(successor))
            // what the removed one reports from here on
            standing.value = SourceStanding.ABSENT
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
            testScheduler.advanceUntilIdle()

            assertTrue("the source stays offered", moose in sources.present)
            assertTrue("the source is signed in, as its new app reports", moose in sources.reach.signedIn)
        }
}
