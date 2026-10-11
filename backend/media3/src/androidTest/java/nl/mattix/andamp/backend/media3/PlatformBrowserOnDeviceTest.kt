// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.playback.TransportRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The service as a car meets it: the platform's own browser binds to
 * [PlaybackService] with no player built, lists the playlist, and has rows
 * played by id and by words.
 *
 * Method names are plain identifiers: a name with spaces is illegal in DEX
 * below version 040, and this module runs from API 26.
 */
@RunWith(AndroidJUnit4::class)
class PlatformBrowserOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val backend = Silent(SONGS)
    private lateinit var browser: MediaBrowser
    private var wakes = 0

    @Before
    fun connect() {
        // no service of its own in front: the test only binds
        Media3PlaybackHost.start = { _, _ -> }
        Media3PlaybackHost.wake = { app ->
            wakes++
            Media3PlaybackHost.host(app, backend)
        }
        val connected = CountDownLatch(1)
        instrumentation.runOnMainSync {
            browser =
                MediaBrowser(
                    context,
                    ComponentName(context, PlaybackService::class.java),
                    object : MediaBrowser.ConnectionCallback() {
                        override fun onConnected() = connected.countDown()
                    },
                    null,
                )
            browser.connect()
        }
        assertTrue("the browser connects", connected.await(WAIT_S, TimeUnit.SECONDS))
    }

    @After
    fun disconnect() {
        instrumentation.runOnMainSync {
            browser.disconnect()
            Media3PlaybackHost.reset(context)
        }
    }

    @Test
    fun theFirstToAskGetsAPlayerBuilt() {
        assertEquals(1, wakes)
    }

    @Test
    fun theRootHoldsThePlaylistAsPlayableRows() {
        val rows = children()

        assertEquals(SessionCallback.ROOT, browser.root)
        assertEquals(listOf("Mysterons", "Sour Times", "Teardrop"), rows.map { it.description.title.toString() })
        assertTrue("every row can be played", rows.all { it.isPlayable && !it.isBrowsable })
    }

    @Test
    fun aRowPickedByItsIdIsPlayed() {
        val third = children()[2].mediaId

        controller().transportControls.playFromMediaId(third, null)

        awaitRow(2)
    }

    @Test
    fun aSongAskedForInWordsIsPlayed() {
        controller().transportControls.playFromSearch("sour times", null)

        awaitRow(1)
    }

    @Test
    fun noWordsCarryOnWithTheRowThatIsOn() {
        controller().transportControls.playFromSearch("", null)

        awaitRow(0)
    }

    @Test
    fun theQueueIsThePlaylist() {
        children() // the session is active once the browser has been answered
        val queue = controller().queue.orEmpty()

        assertEquals(listOf("Mysterons", "Sour Times", "Teardrop"), queue.map { it.description.title.toString() })
    }

    private fun controller() = MediaController(context, browser.sessionToken)

    private fun children(): List<MediaBrowser.MediaItem> {
        val loaded = CountDownLatch(1)
        var rows = emptyList<MediaBrowser.MediaItem>()
        instrumentation.runOnMainSync {
            browser.subscribe(
                browser.root,
                object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(
                        parentId: String,
                        children: MutableList<MediaBrowser.MediaItem>,
                    ) {
                        rows = children
                        loaded.countDown()
                    }
                },
            )
        }
        assertTrue("the rows arrive", loaded.await(WAIT_S, TimeUnit.SECONDS))
        return rows
    }

    /** Waits for the player to be playing [row]; the request crosses a binder and the main thread. */
    private fun awaitRow(row: Int) {
        val giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (System.nanoTime() < giveUp) {
            val now = backend.state.value
            if (now.currentIndex == row && now.transport == Transport.Playing) return
            Thread.sleep(POLL_MS)
        }
        val now = backend.state.value
        assertEquals("the row that is on", row, now.currentIndex)
        assertEquals(Transport.Playing, now.transport)
    }

    /** Winamp's transport rules over a playlist, with no audio. */
    private class Silent(
        rows: List<Track>,
    ) : PlaybackBackend {
        override val state = MutableStateFlow(BackendState(queue = rows))
        override val capabilities = Capabilities(canSeek = true)

        override fun setQueue(
            tracks: List<Track>,
            startIndex: Int,
        ) = Unit

        override fun play() {
            state.value = TransportRules.play(state.value)
        }

        override fun pause() {
            state.value = TransportRules.pause(state.value)
        }

        override fun stop() {
            state.value = TransportRules.stop(state.value)
        }

        override fun next() = Unit

        override fun previous() = Unit

        override fun playAt(index: Int) {
            state.value = TransportRules.playAt(state.value, index)
        }

        override fun seekTo(positionMs: Long) = Unit

        override fun setVolume(fraction: Float) = Unit

        override fun setShuffle(enabled: Boolean) = Unit

        override fun setRepeat(enabled: Boolean) = Unit

        override fun release() = Unit
    }

    private companion object {
        const val WAIT_S = 10L
        const val POLL_MS = 20L

        val SONGS =
            listOf(
                Track("t1", "Portishead", "Mysterons", 300_000, uri = "remote:track:t1"),
                Track("t2", "Portishead", "Sour Times", 250_000, uri = "remote:track:t2"),
                Track("t3", "Massive Attack", "Teardrop", 330_000, uri = "remote:track:t3"),
            )
    }
}
