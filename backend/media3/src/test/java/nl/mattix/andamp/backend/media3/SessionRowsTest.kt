// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * What a browser is shown of the playlist, and which row a request to play
 * means.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionRowsTest {
    private val scope = CoroutineScope(Dispatchers.Main)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun rowsOf(
        tracks: List<Track> = SONGS,
        on: Int = 0,
    ) = SessionRows(BackendPlayer(MockBackend(tracks, scope, startIndex = on), scope)).also { ShadowLooper.idleMainLooper() }

    private fun titles(rows: List<MediaItem>) = rows.map { it.mediaMetadata.title.toString() }

    @Test
    fun `every row is listed, in playlist order`() {
        assertEquals(listOf("Mysterons", "Sour Times", "Déjà Vu"), titles(rowsOf().all()))
    }

    @Test
    fun `a row can be played and cannot be opened`() {
        val row = rowsOf().all().first()

        assertEquals(true, row.mediaMetadata.isPlayable)
        assertEquals(false, row.mediaMetadata.isBrowsable)
    }

    @Test
    fun `a row is listed without its cover`() {
        val withCover = SONGS.map { it.copy(artworkUri = "https://covers.example.org/${it.id}.jpg") }

        assertNull(
            rowsOf(withCover)
                .all()
                .first()
                .mediaMetadata.artworkUri,
        )
    }

    @Test
    fun `a song that is in twice is listed under two ids`() {
        val ids = rowsOf(SONGS + SONGS.take(1)).all().map { it.mediaId }

        assertEquals(listOf("t1", "t2", "t3", "t1#2"), ids)
    }

    @Test
    fun `words find a row by its title, whatever the case`() {
        assertEquals(listOf("Sour Times"), titles(rowsOf().matching("sour TIMES")))
    }

    @Test
    fun `words find rows by their artist`() {
        assertEquals(listOf("Mysterons", "Sour Times"), titles(rowsOf().matching("portishead")))
    }

    @Test
    fun `every word has to be in the row`() {
        assertEquals(listOf("Sour Times"), titles(rowsOf().matching("portishead sour")))
        assertEquals(emptyList<String>(), titles(rowsOf().matching("portishead vu")))
    }

    @Test
    fun `accents make no difference`() {
        assertEquals(listOf("Déjà Vu"), titles(rowsOf().matching("deja vu beyonce")))
    }

    @Test
    fun `no words find nothing`() {
        assertEquals(emptyList<String>(), titles(rowsOf().matching("  ")))
    }

    @Test
    fun `a request with a row's id means that row`() {
        val asked = rowsOf().asked(MediaItem.Builder().setMediaId("t2").build())

        assertEquals("t2", asked?.mediaId)
    }

    @Test
    fun `a request in words means the first row they match`() {
        val asked = rowsOf().asked(said("portishead"))

        assertEquals("t1", asked?.mediaId)
    }

    @Test
    fun `a request with no words means the row that is on`() {
        val asked = rowsOf(on = 2).asked(said(""))

        assertEquals("t3", asked?.mediaId)
    }

    @Test
    fun `words that match nothing mean no row`() {
        assertNull(rowsOf().asked(said("massive attack")))
    }

    @Test
    fun `a request with neither an id nor words means no row`() {
        assertNull(rowsOf().asked(MediaItem.Builder().build()))
    }

    @Test
    fun `an empty playlist has no row to carry on with`() {
        assertNull(rowsOf(emptyList()).asked(said("")))
    }

    private fun said(words: String) =
        MediaItem
            .Builder()
            .setRequestMetadata(
                MediaItem.RequestMetadata
                    .Builder()
                    .setSearchQuery(words)
                    .build(),
            ).build()

    private companion object {
        val SONGS =
            listOf(
                Track("t1", "Portishead", "Mysterons", 300_000, uri = "remote:track:t1"),
                Track("t2", "Portishead", "Sour Times", 250_000, uri = "remote:track:t2"),
                Track("t3", "Beyoncé", "Déjà Vu", 240_000, uri = "remote:track:t3"),
            )
    }
}
