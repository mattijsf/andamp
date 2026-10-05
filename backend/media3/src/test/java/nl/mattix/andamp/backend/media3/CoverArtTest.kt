// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Where the notification's cover is looked for.
 *
 * The routing is tested: which addresses are opened as files and which are
 * fetched. A station's address is never opened for a cover. So is the size a
 * cover is decoded at.
 */
@RunWith(RobolectricTestRunner::class)
class CoverArtTest {
    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `a song on the phone stands in as its own cover`() {
        val song = Track(id = "1", artist = "Muse", title = "Hysteria", durationMs = 1, uri = "content://media/audio/9")
        assertEquals(Uri.parse("content://media/audio/9"), song.toMediaMetadata().artworkUri)
    }

    @Test
    fun `a backend that knows where the cover is keeps the last word`() {
        val song =
            Track(
                id = "2",
                artist = "Muse",
                title = "Hysteria",
                durationMs = 1,
                uri = "content://media/audio/9",
                artworkUri = "https://example.test/cover.jpg",
            )
        assertEquals(Uri.parse("https://example.test/cover.jpg"), song.toMediaMetadata().artworkUri)
    }

    @Test
    fun `a station is never opened in the hunt for a picture`() {
        val station =
            Track(id = "3", artist = "", title = "Radio", durationMs = 0, uri = "https://stream.test/live", isStream = true)
        assertNull(station.toMediaMetadata().artworkUri)
    }

    @Test
    fun `an address that is fetched is left to the loader that fetches`() {
        val fetching = Recording()
        val loader = CoverBitmapLoader(app, fetching)
        val cover = Uri.parse("https://example.test/cover.jpg")
        loader.loadBitmap(cover).get(WAIT, TimeUnit.SECONDS)
        assertEquals(listOf(cover), fetching.asked)
    }

    @Test
    fun `a file holding no picture is handed on rather than failed`() {
        val fetching = Recording()
        val loader = CoverBitmapLoader(app, fetching)
        val song = Uri.parse("content://media/audio/9")
        loader.loadBitmap(song).get(WAIT, TimeUnit.SECONDS)
        assertEquals(listOf(song), fetching.asked)
    }

    @Test
    fun `a cover that already fits is decoded as it is`() {
        assertEquals(1, coverSampleSize(600, 600, 1024))
        assertEquals(1, coverSampleSize(1024, 768, 1024))
    }

    @Test
    fun `a large cover is halved until its longer side fits`() {
        assertEquals(2, coverSampleSize(1025, 1025, 1024))
        assertEquals(4, coverSampleSize(3000, 3000, 1024))
        assertEquals(4, coverSampleSize(500, 4096, 1024))
    }

    @Test
    fun `a picture whose size could not be read is not sampled`() {
        assertEquals(1, coverSampleSize(-1, -1, 1024))
    }

    /** Stands in for Media3's loader and records what it was asked for. */
    private class Recording : BitmapLoader {
        val asked = mutableListOf<Uri>()

        override fun supportsMimeType(mimeType: String) = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = Futures.immediateFuture(dot())

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            asked += uri
            return Futures.immediateFuture(dot())
        }

        private fun dot(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val WAIT = 5L
    }
}
