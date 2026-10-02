// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.state.MusicSource
import nl.mattix.andamp.state.SkinLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/**
 * The home screen following what is playing, with no window of the player's
 * open.
 *
 * A press on the widget starts the process without building a view model, so
 * [WidgetOps] follows the source's skin itself: it stores what is worn and
 * asks the home screen to redraw.
 *
 * The source is made up here, as in `SourceSkinsTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetOpsSkinTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val example = MusicSource.foreign("example")
    private val phoneTrack = Track("1", "A Band", "From the phone", 1_000, uri = "file:///music/a.mp3")
    private val sourceTrack = Track("2", "A Band", "From the source", 1_000, uri = "example:track:2")

    private lateinit var library: SkinLibrary

    @Before
    fun setUp() {
        library = SkinLibrary(app)
        // the listener's own choice, and the skin the source asks for
        library.currentId = BundledSkins.LIGHT.id
        app
            .getSharedPreferences("source_skins", Context.MODE_PRIVATE)
            .edit()
            .putString(example.id, BundledSkins.SPOT.id)
            .commit()
    }

    /**
     * The follower reads and writes the library off the main thread. Kept on
     * the thread the test drives, every write has landed by the time the call
     * that caused it returns.
     */
    private fun start(
        backend: MockBackend,
        skinnable: (MusicSource) -> Boolean = { it == example },
    ): WidgetOps =
        WidgetOps(app, PlayerFacade(backend), scope, skinnable = skinnable, io = Dispatchers.Unconfined)
            .also { it.start() }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * The launcher goes on drawing what it was last sent until it is sent
     * something else, so stopping writes a stopped player.
     */
    @Test
    fun `stopping leaves the home screen showing a stopped player`() {
        val ops = start(MockBackend(listOf(phoneTrack), scope))
        WidgetSnapshot.write(app, WidgetSnapshot(title = "A Song", transport = Transport.Playing, positionSec = 42))

        ops.stop()

        val left = WidgetSnapshot.read(app)
        assertEquals(Transport.Stopped, left.transport)
        assertEquals(0, left.positionSec)
        assertEquals("the stopped widget keeps the song's title", "A Song", left.title)
        assertTrue(WidgetVisFrames.frames.isEmpty())
    }

    @Test
    fun `a track from a source puts its skin on with no player window anywhere`() {
        val backend = MockBackend(listOf(phoneTrack), scope)
        val ops = start(backend)

        backend.setQueue(listOf(sourceTrack), 0)
        settle()

        assertEquals(BundledSkins.SPOT.id, library.wearing)
        assertEquals("the home screen draws what is worn", BundledSkins.SPOT.id, library.worn)
        assertEquals("the listener's own choice is untouched", BundledSkins.LIGHT.id, library.currentId)
        ops.stop()
    }

    @Test
    fun `a track from the phone's own files takes the source's skin off again`() {
        val backend = MockBackend(listOf(sourceTrack), scope)
        val ops = start(backend)
        settle()
        assertEquals(BundledSkins.SPOT.id, library.wearing)

        backend.setQueue(listOf(phoneTrack), 0)
        settle()

        assertNull(library.wearing)
        assertEquals(BundledSkins.LIGHT.id, library.worn)
        ops.stop()
    }

    @Test
    fun `a source that says the setting is unsupported wears the listener's own`() {
        val backend = MockBackend(listOf(phoneTrack), scope)
        val ops = start(backend, skinnable = { false })

        backend.setQueue(listOf(sourceTrack), 0)
        settle()

        assertNull(library.wearing)
        ops.stop()
    }

    @Test
    fun `a skin the listener picks while a track is arriving is not undone by it`() {
        // the pick lands after the track change was heard and before the
        // source's skin is stored; the source's skin must not go on over it
        val backend = MockBackend(emptyList(), scope)
        val ops =
            WidgetOps(
                app,
                PlayerFacade(backend),
                scope,
                skinnable = { it == example },
                io = PickFirst { SkinLibrary.choosing() },
            ).also { it.start() }

        backend.setQueue(listOf(sourceTrack), 0)
        settle()

        assertNull("the listener's pick stays on", library.wearing)
        assertEquals(BundledSkins.LIGHT.id, library.worn)
        ops.stop()
    }

    @After
    fun tearDown() {
        // the player it attached to is process-wide, and the next test's
        // presses must not reach this one's
        WidgetControl.detach()
        scope.cancel()
    }

    /**
     * Runs [pick] once, on the way to the library and back on the caller's own
     * thread: a listener choosing a skin in the instant between the two.
     */
    private class PickFirst(
        private val pick: () -> Unit,
    ) : CoroutineDispatcher() {
        private var picked = false

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            if (!picked) {
                picked = true
                pick()
            }
            block.run()
        }
    }
}
