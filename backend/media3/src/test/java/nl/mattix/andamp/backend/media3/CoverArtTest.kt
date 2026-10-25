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
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Where a row's cover is looked for, and what a row tells others about it.
 *
 * The routing is tested: which addresses are opened as files and which are
 * fetched. A station's address is never opened for a cover. So is the size a
 * cover is decoded at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverArtTest {
    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Its file is no picture and opens for no other app, so it is not given out as one. */
    @Test
    fun `a song on the phone names its own file for its cover, and no picture's address`() {
        val told = song("content://media/audio/9").toMediaMetadata()

        assertNull(told.artworkUri)
        assertEquals("content://media/audio/9", told.extras?.getString(COVER_IN))
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
        val told = song.toMediaMetadata()

        assertEquals(Uri.parse("https://example.test/cover.jpg"), told.artworkUri)
        assertNull(told.extras)
    }

    @Test
    fun `a station is never opened in the hunt for a picture`() {
        val station =
            Track(id = "3", artist = "", title = "Radio", durationMs = 0, uri = "https://stream.test/live", isStream = true)
        val told = station.toMediaMetadata()

        assertNull(told.artworkUri)
        assertNull(told.extras)
    }

    /** In this test no file holds a picture, so a file that is read is handed on, which shows it was. */
    @Test
    fun `a row that names its file has its cover read from that file`() {
        val fetching = Recording()
        val loader = CoverBitmapLoader(app, fetching)

        loader.loadBitmapFromMetadata(song("content://media/audio/9").toMediaMetadata())?.get(WAIT, TimeUnit.SECONDS)

        assertEquals(listOf(Uri.parse("content://media/audio/9")), fetching.asked)
    }

    @Test
    fun `a file asked for again is not read again`() {
        val fetching = Recording()
        val loader = CoverBitmapLoader(app, fetching)
        val told = song("content://media/audio/9").toMediaMetadata()

        loader.loadBitmapFromMetadata(told)?.get(WAIT, TimeUnit.SECONDS)
        loader.loadBitmapFromMetadata(told)?.get(WAIT, TimeUnit.SECONDS)

        assertEquals(1, fetching.asked.size)
    }

    @Test
    fun `the next song's cover is read from its own file`() {
        val fetching = Recording()
        val loader = CoverBitmapLoader(app, fetching)

        loader.loadBitmapFromMetadata(song("content://media/audio/9").toMediaMetadata())?.get(WAIT, TimeUnit.SECONDS)
        loader.loadBitmapFromMetadata(song("content://media/audio/10").toMediaMetadata())?.get(WAIT, TimeUnit.SECONDS)

        assertEquals(listOf(Uri.parse("content://media/audio/9"), Uri.parse("content://media/audio/10")), fetching.asked)
    }

    @Test
    fun `a row with a picture's address has that address fetched`() {
        val fetching = Recording()
        val loader = CoverBitmapLoader(app, fetching)
        val told = song("content://media/audio/9").copy(artworkUri = "https://example.test/cover.jpg").toMediaMetadata()

        loader.loadBitmapFromMetadata(told)?.get(WAIT, TimeUnit.SECONDS)

        assertEquals(listOf(Uri.parse("https://example.test/cover.jpg")), fetching.asked)
    }

    @Test
    fun `a row with no cover to look for is given none`() {
        val station =
            Track(id = "3", artist = "", title = "Radio", durationMs = 0, uri = "https://stream.test/live", isStream = true)

        assertNull(CoverBitmapLoader(app, Recording()).loadBitmapFromMetadata(station.toMediaMetadata()))
    }

    @Test
    fun `an address that is fetched is left to the loader that fetches`() {
        val fetching = Recording()
        val loader = CoverBitmapLoader(app, fetching)
        val cover = Uri.parse("https://example.test/cover.jpg")
        loader.loadBitmap(cover).get(WAIT, TimeUnit.SECONDS)
        assertEquals(listOf(cover), fetching.asked)
    }

    /**
     * A row can name a file through an address that no longer opens while the file is still
     * reachable another way. The cover is looked for under the address that opens now, in
     * the file's tags and by the loader the file is handed on to alike.
     */
    @Test
    fun `a cover is looked for under the address the file can be read under now`() {
        val fetching = Recording()
        val loader = CoverBitmapLoader(app, fetching)
        val row =
            Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fone.mp3",
            )
        val readable = Uri.parse("content://media/external/audio/media/8")
        val before = Media3PlaybackHost.readableUri
        Media3PlaybackHost.readableUri = { if (it == row) readable else it }
        try {
            loader.loadBitmap(row).get(WAIT, TimeUnit.SECONDS)
        } finally {
            Media3PlaybackHost.readableUri = before
        }

        assertEquals(listOf(readable), fetching.asked)
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

    private fun song(at: String) = Track(id = "1", artist = "Muse", title = "Hysteria", durationMs = 1, uri = at)

    private companion object {
        const val WAIT = 5L
    }
}
