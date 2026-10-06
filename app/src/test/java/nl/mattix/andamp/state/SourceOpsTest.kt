// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * What a pick, a sign-in and a sign-out do to the library window, and how the app's record
 * of who is signed in catches up with the device.
 *
 * The library window runs over two made-up shelves, the phone's and a second source's. The
 * tests assert whether the window is open and whose music it lists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceOpsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val example = MusicSource("EXAMPLE", "Example")

    private val state = WinampState()
    private lateinit var sources: LibrarySources
    private lateinit var library: LibraryOps

    private val forgotLibraries = mutableListOf<MusicSource>()
    private val forgotPlayers = mutableListOf<MusicSource>()

    /** Whether this device holds a credential for the made-up source; null is a source that could not be asked. */
    private var held: Boolean? = false

    /** What happens while the device is being read, for the one test that needs something to. */
    private var reading: () -> Unit = {}

    private val extra =
        object : ExtraSource {
            override val source = example

            override fun signedIn(context: Context): Boolean? {
                reading()
                return held
            }

            override fun backend(
                context: Context,
                scope: CoroutineScope,
            ): PlaybackBackend? = null

            override fun browse(context: Context): BrowseSource? = null

            @Composable
            override fun Summary(signedIn: Boolean) = ""

            @Composable
            override fun Page(
                onSignedIn: () -> Unit,
                onSignedOut: () -> Unit,
            ) = Unit
        }

    /** One artist with one record, named for whose shelf it is. */
    private class Shelf(
        private val whose: String,
    ) : BrowseSource {
        override val capabilities = BrowseCapabilities()
        override val available = true

        override suspend fun artists() = listOf(LibraryArtist("$whose-artist", "$whose's artist", albumCount = 1))

        override suspend fun albums(artistId: String?) = listOf(LibraryAlbum("$whose-album", "$whose's album", "$whose's artist"))

        override suspend fun tracks(albumId: String) = emptyList<Track>()
    }

    private val shelves = mapOf(MusicSource.LOCAL to Shelf("Phone"), example to Shelf("Example"))

    /** Whose music the window lists. */
    private val listing: List<String> get() = library.rows.map { it.label }

    private fun ops(signedIn: Boolean): SourceOps {
        val prefs = app.getSharedPreferences("library-${System.nanoTime()}", Context.MODE_PRIVATE)
        val signed: Set<MusicSource> = if (signedIn) setOf(example) else emptySet()
        sources = LibrarySources(LibrarySourceStore(prefs), listOf(MusicSource.LOCAL, example), signed)
        library =
            LibraryOps(
                { shelves.getValue(sources.showing) },
                PlayerFacade(MockBackend(FakeTracks.tracks, scope)),
                state,
                scope,
                PlaylistLibrary(temp.newFolder("playlists")),
                StationStore(File(temp.newFolder("radio"), "stations.m3u")),
            )
        return SourceOps(
            state,
            sources,
            library,
            extras = { listOf(extra) },
            reachUnanswered = { reached++ },
            io = Dispatchers.Unconfined,
            forgetLibrary = { forgotLibraries += it },
            forgetPlayer = { forgotPlayers += it },
        )
    }

    /** How often the packs that have not answered were asked to; see [SourceOps.reconcile]. */
    private var reached = 0

    @Test
    fun `a source nobody is signed in to sends the pick to its page in Preferences`() {
        val ops = ops(signedIn = false)
        var sent = 0

        ops.libraryFrom(example, onSignIn = { sent++ })

        assertEquals(1, sent)
        assertEquals("source/example", state.arrivalPage)
        assertFalse("the library window stays closed", state.libraryOpen)
    }

    @Test
    fun `picking the source the window already shows closes it`() {
        val ops = ops(signedIn = true)
        ops.libraryFrom(example, onSignIn = {})
        assertTrue(state.libraryOpen)

        ops.libraryFrom(example, onSignIn = {})

        assertFalse("picking the shown source closes the window", state.libraryOpen)
    }

    @Test
    fun `picking a source this install does not have does nothing`() {
        val ops = ops(signedIn = false)
        var sent = 0

        ops.libraryFrom(MusicSource("ELSEWHERE", "Elsewhere"), onSignIn = { sent++ })

        assertEquals("an unknown source sends nobody to sign in", 0, sent)
        assertNull(state.arrivalPage)
        assertFalse(state.libraryOpen)
    }

    @Test
    fun `signing in after a pick of a signed-out source opens the library on it`() {
        val ops = ops(signedIn = false)
        ops.libraryFrom(example, onSignIn = {})

        ops.signedIn(example)

        assertTrue("the library window opens after the sign-in", state.libraryOpen)
        assertEquals(listOf("Example's artist"), listing)
    }

    @Test
    fun `a sign-in nobody picked the library for leaves a closed window closed`() {
        val ops = ops(signedIn = false)

        ops.signedIn(example)

        assertFalse(state.libraryOpen)
    }

    @Test
    fun `leaving Preferences without signing in forgets the pick`() {
        val ops = ops(signedIn = false)
        ops.libraryFrom(example, onSignIn = {})

        ops.preferencesClosed()
        ops.signedIn(example)

        assertFalse("a sign-in after leaving Preferences leaves the window closed", state.libraryOpen)
    }

    @Test
    fun `a pick opens the window on the first sign-in only`() {
        val ops = ops(signedIn = false)
        ops.libraryFrom(example, onSignIn = {})
        ops.signedIn(example)
        state.libraryOpen = false

        ops.signedIn(example)

        assertFalse("the window the listener closed stays closed", state.libraryOpen)
    }

    @Test
    fun `a sign-in while the library is open moves the window onto the source`() {
        val ops = ops(signedIn = false)
        ops.libraryFrom(MusicSource.LOCAL, onSignIn = {})
        assertEquals(listOf("Phone's artist"), listing)

        ops.signedIn(example)

        assertTrue(state.libraryOpen)
        assertEquals(listOf("Example's artist"), listing)
    }

    @Test
    fun `signing out of the source the window shows takes the window back to the phone`() {
        val ops = ops(signedIn = true)
        ops.libraryFrom(example, onSignIn = {})
        assertEquals(listOf("Example's artist"), listing)

        ops.signedOut(example)

        assertTrue(state.libraryOpen)
        assertEquals("the window lists the phone's music", listOf("Phone's artist"), listing)
    }

    @Test
    fun `signing out of a source the window is not showing leaves the window where it is`() {
        val ops = ops(signedIn = true)
        ops.libraryFrom(MusicSource.LOCAL, onSignIn = {})
        library.tapRow(0)
        assertEquals(1, library.depth)

        ops.signedOut(example)

        assertEquals("the listener's place in the phone's music is kept", 1, library.depth)
        assertEquals(listOf("Phone's album"), listing)
    }

    @Test
    fun `a sign-out lets go of the source's library and its player`() {
        val ops = ops(signedIn = true)

        ops.signedOut(example)

        assertEquals(SourceAbsence.SIGNED_OUT, sources.reach.absence(example))
        assertEquals(listOf(example), forgotLibraries)
        assertEquals(listOf(example), forgotPlayers)
    }

    @Test
    fun `a sign-in that finished with nobody listening is found when Preferences looks`() =
        runTest {
            val ops = ops(signedIn = false)
            // the device holds a credential the app's record does not know of
            held = true

            ops.reconcile(app)

            assertNull("the source counts as signed in", sources.reach.absence(example))
            assertEquals("the source's library is read again", listOf(example), forgotLibraries)
        }

    /** A pack whose first binding failed is not among the sources until it answers, so each look asks it again. */
    @Test
    fun `each look asks the packs that have not answered yet`() =
        runTest {
            val ops = ops(signedIn = false)

            ops.reconcile(app)
            ops.reconcile(app)

            assertEquals(2, reached)
        }

    @Test
    fun `a late sign-in after a Media Library pick still opens the window`() =
        runTest {
            val ops = ops(signedIn = false)
            ops.libraryFrom(example, onSignIn = {})
            held = true

            ops.reconcile(app)

            assertTrue(state.libraryOpen)
            assertEquals(listOf("Example's artist"), listing)
        }

    @Test
    fun `a credential gone from the device is a sign-out`() =
        runTest {
            val ops = ops(signedIn = true)
            held = false

            ops.reconcile(app)

            assertEquals(SourceAbsence.SIGNED_OUT, sources.reach.absence(example))
            assertEquals("the source's player is let go", listOf(example), forgotPlayers)
        }

    @Test
    fun `a record that agrees with the device is left alone`() =
        runTest {
            val ops = ops(signedIn = true)
            held = true
            ops.libraryFrom(MusicSource.LOCAL, onSignIn = {})

            ops.reconcile(app)

            assertEquals("no library is forgotten", emptyList<MusicSource>(), forgotLibraries)
            assertEquals("the window stays on the phone", MusicSource.LOCAL, sources.showing)
        }

    @Test
    fun `a sign-out on the page while the device is being read is not told twice`() =
        runTest {
            val ops = ops(signedIn = true)
            held = true
            reading = {
                // Sign out on the source's page: the record goes first, then the credential
                ops.signedOut(example)
                held = false
            }

            ops.reconcile(app)

            assertEquals(listOf(example), forgotPlayers)
            assertEquals(SourceAbsence.SIGNED_OUT, sources.reach.absence(example))
        }

    /**
     * Guards against a sign-in made in the source's own app going unnoticed:
     * [SourceOps.reconcileWhileShown] reads the device each time the player's screen is
     * resumed.
     */
    @Test
    fun `a sign-in in the source's own app is found when the player comes back to the front`() =
        runTest {
            val ops = ops(signedIn = false)
            val screen = Shown()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ops.reconcileWhileShown(app, screen.lifecycle) }
            screen.comesForward()
            assertEquals("the source starts signed out", SourceAbsence.SIGNED_OUT, sources.reach.absence(example))

            // over to the source's app, signed in there, and back
            screen.goesBehind()
            held = true
            screen.comesForward()

            assertNull("the source counts as signed in", sources.reach.absence(example))
            assertEquals("the source's library is read again", listOf(example), forgotLibraries)
        }

    @Test
    fun `a sign-out in the source's own app is found when the player comes back to the front`() =
        runTest {
            val ops = ops(signedIn = true)
            held = true
            val screen = Shown()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ops.reconcileWhileShown(app, screen.lifecycle) }
            screen.comesForward()

            screen.goesBehind()
            held = false
            screen.comesForward()

            assertEquals(SourceAbsence.SIGNED_OUT, sources.reach.absence(example))
            assertEquals("the source's player is let go", listOf(example), forgotPlayers)
        }

    /** A source that cannot be asked, whose `signedIn` answers null, keeps the record it has. */
    @Test
    fun `a source that cannot be reached when the player comes back is not taken for signed out`() =
        runTest {
            val ops = ops(signedIn = true)
            held = true
            val screen = Shown()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ops.reconcileWhileShown(app, screen.lifecycle) }
            screen.comesForward()

            screen.goesBehind()
            held = null
            screen.comesForward()

            assertNull("the source stays signed in", sources.reach.absence(example))
            assertEquals("the source's player is kept", emptyList<MusicSource>(), forgotPlayers)
        }

    /** The player's screen, brought forward and sent behind the way switching apps does. */
    private class Shown : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry

        fun comesForward() = settle(Lifecycle.State.RESUMED)

        fun goesBehind() = settle(Lifecycle.State.CREATED)

        private fun settle(to: Lifecycle.State) {
            registry.currentState = to
            // repeatOnLifecycle starts and stops its block on the main thread
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
