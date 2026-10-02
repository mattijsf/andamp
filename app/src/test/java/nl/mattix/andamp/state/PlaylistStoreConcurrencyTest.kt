// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The queue is saved on every change, so two saves can overlap: a track added
 * while the previous save is still writing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistStoreConcurrencyTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    // a stored entry needs a uri; the codec leaves out a track without one
    private fun track(title: String) =
        Track(id = title, artist = "someone", title = title, durationMs = 1000, uri = "file:///music/$title.mp3")

    @Test
    fun `overlapping saves all report success`() {
        val store = PlaylistStore(app)
        val savers = 8
        val ready = CountDownLatch(savers)
        val go = CountDownLatch(1)
        val done = CountDownLatch(savers)
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())

        repeat(savers) { n ->
            Thread {
                ready.countDown()
                go.await()
                results += store.save(List(n + 1) { track("track $it") }, 0)
                done.countDown()
            }.start()
        }
        ready.await(5, TimeUnit.SECONDS)
        go.countDown()
        assertTrue("every saver finishes", done.await(10, TimeUnit.SECONDS))

        // each write has its own scratch file; with a shared one, overlapping renames would fail
        assertTrue("every save reports success: $results", results.all { it })
        assertTrue("the queue reads back", store.load()!!.tracks.isNotEmpty())
    }

    @Test
    fun `a save reports whether the queue reached the disk`() {
        val store = PlaylistStore(app)

        assertTrue(store.save(listOf(track("one")), 0))
        assertEquals(listOf("one"), store.load()!!.tracks.map { it.title })
    }

    @Test
    fun `saving leaves no scratch behind`() {
        val store = PlaylistStore(app)

        repeat(5) { store.save(listOf(track("x")), 0) }

        val scratch =
            app.filesDir
                .listFiles()
                .orEmpty()
                .filter { it.name.endsWith(".tmp") }
        assertTrue("no scratch file remains: ${scratch.map { it.name }}", scratch.isEmpty())
    }
}
