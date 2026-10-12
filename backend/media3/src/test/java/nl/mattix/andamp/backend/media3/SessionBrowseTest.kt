// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionCommand
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The session as a browser meets it: Media3's own browser connected to the
 * session the host builds, over a backend with no audio.
 *
 * A car connects through the platform's older interface, which Media3 serves
 * from the same callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionBrowseTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val backend = MockBackend(SONGS, scope)
    private val heard = mutableListOf<Pair<String, Int>>()
    private lateinit var real: (Context, Boolean) -> Unit
    private lateinit var browser: MediaBrowser

    @Before
    fun connect() {
        real = Media3PlaybackHost.start
        Media3PlaybackHost.start = { _, _ -> }
        Media3PlaybackHost.host(app, backend)
        val session = checkNotNull(Media3PlaybackHost.session(app))
        val listener =
            object : MediaBrowser.Listener {
                override fun onChildrenChanged(
                    browser: MediaBrowser,
                    parentId: String,
                    itemCount: Int,
                    params: LibraryParams?,
                ) {
                    heard += parentId to itemCount
                }
            }
        browser = await(MediaBrowser.Builder(app, session.token).setListener(listener).buildAsync())
    }

    @After
    fun tearDown() {
        browser.release()
        Media3PlaybackHost.reset(app)
        Media3PlaybackHost.start = real
        scope.cancel()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `the library is one folder`() {
        val root = await(browser.getLibraryRoot(null)).value

        assertEquals(SessionCallback.ROOT, root?.mediaId)
        assertEquals(true, root?.mediaMetadata?.isBrowsable)
    }

    @Test
    fun `the folder holds the playlist`() {
        assertEquals(listOf("Mysterons", "Sour Times", "Teardrop"), titles(rows()))
    }

    @Test
    fun `a long playlist is handed over a page at a time`() {
        val second = await(browser.getChildren(SessionCallback.ROOT, 1, 2, null)).value.orEmpty()

        assertEquals(listOf("Teardrop"), titles(second))
    }

    @Test
    fun `no other folder can be opened`() {
        val children = await(browser.getChildren("t1", 0, 100, null))

        assertTrue("a row has no children", children.value.isNullOrEmpty())
    }

    @Test
    fun `a row picked in the list is played`() {
        browser.setMediaItem(rows()[2])
        ShadowLooper.idleMainLooper()

        assertEquals(2, backend.state.value.currentIndex)
        assertEquals(Transport.Playing, backend.state.value.transport)
    }

    @Test
    fun `a song asked for in words is played`() {
        browser.setMediaItem(said("sour times"))
        ShadowLooper.idleMainLooper()

        assertEquals(1, backend.state.value.currentIndex)
        assertEquals(Transport.Playing, backend.state.value.transport)
    }

    @Test
    fun `asking for a song the playlist does not hold changes nothing`() {
        browser.setMediaItem(said("unfinished sympathy"))
        ShadowLooper.idleMainLooper()

        assertEquals(0, backend.state.value.currentIndex)
        assertEquals(Transport.Stopped, backend.state.value.transport)
        assertEquals(SONGS, backend.state.value.queue)
    }

    @Test
    fun `a search finds rows by words`() {
        await(browser.search("portishead", null))
        ShadowLooper.idleMainLooper()

        val found = await(browser.getSearchResult("portishead", 0, 100, null)).value.orEmpty()

        assertEquals(listOf("Mysterons", "Sour Times"), titles(found))
    }

    @Test
    fun `a browser showing the playlist hears when it changes`() {
        await(browser.subscribe(SessionCallback.ROOT, null))

        backend.enqueue(listOf(Track("t4", "Massive Attack", "Angel", 380_000, uri = "remote:track:t4")))
        ShadowLooper.idleMainLooper()

        assertEquals(SessionCallback.ROOT to 4, heard.lastOrNull())
    }

    @Test
    fun `the shuffle button reaches the player`() {
        await(browser.sendCustomCommand(SessionCommand(SessionCallback.SHUFFLE, Bundle.EMPTY), Bundle.EMPTY))
        ShadowLooper.idleMainLooper()

        assertTrue(backend.state.value.shuffle)
    }

    private fun rows(): List<MediaItem> = await(browser.getChildren(SessionCallback.ROOT, 0, 100, null)).value.orEmpty()

    private fun titles(rows: List<MediaItem>) = rows.map { it.mediaMetadata.title.toString() }

    private fun said(words: String) =
        MediaItem
            .Builder()
            .setRequestMetadata(
                MediaItem.RequestMetadata
                    .Builder()
                    .setSearchQuery(words)
                    .build(),
            ).build()

    /** Runs the main looper, where Media3 answers, until [future] has its answer. */
    private fun <T> await(future: ListenableFuture<T>): T {
        val giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!future.isDone && System.nanoTime() < giveUp) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(1)
        }
        return future.get(0, TimeUnit.SECONDS)
    }

    private companion object {
        const val WAIT_S = 5L

        val SONGS =
            listOf(
                Track("t1", "Portishead", "Mysterons", 300_000, uri = "remote:track:t1"),
                Track("t2", "Portishead", "Sour Times", 250_000, uri = "remote:track:t2"),
                Track("t3", "Massive Attack", "Teardrop", 330_000, uri = "remote:track:t3"),
            )
    }
}
