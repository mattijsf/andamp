// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A track added from a picked folder keeps the folder's own uri.
 *
 * The grant is on the tree, and [GrantScope] keeps a tree grant only while a queued uri
 * lies under it. A library uri in the queue would let the prune release the folder, and
 * it would not say where the file is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderTrackUriTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val folder = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FAlbum"
    private val document = "$folder/document/primary%3AMusic%2FAlbum%2Fone.mp3"

    /** A library that has a uri for every file. */
    private class KnowsEverything(
        app: Application,
    ) : MediaStoreAudio(app) {
        override fun hasPermission() = true

        override fun libraryUriFor(
            documentUri: Uri,
            displayName: String?,
            durationMs: Long,
        ): Uri = Uri.parse("content://media/external/audio/media/4711")
    }

    @Test
    fun `a folder's track keeps its document uri when the library knows the file`() {
        val files = MediaFiles(app, KnowsEverything(app))

        val track = files.readInTree(Uri.parse(document))

        assertEquals(document, track.uri)
    }

    /**
     * A file picked from the phone's storage is named by where it is, which still means
     * the same file on another phone. The library's uri for it is a number in this
     * phone's library.
     */
    @Test
    fun `a file picked from storage keeps its own uri although the library knows the file`() {
        val files = MediaFiles(app, KnowsEverything(app))
        val picked = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fsong.mp3"

        val track = files.readAdded(Uri.parse(picked))

        assertEquals(picked, track.uri)
    }

    @Test
    fun `a picked file whose uri does not say where it is takes the library's uri`() {
        val files = MediaFiles(app, KnowsEverything(app))

        val track = files.readAdded(Uri.parse("content://com.android.providers.downloads.documents/document/msf%3A23"))

        assertEquals("content://media/external/audio/media/4711", track.uri)
    }

    @Test
    fun `the prune keeps a folder whose track came through readInTree`() {
        val files = MediaFiles(app, KnowsEverything(app))
        val track = files.readInTree(Uri.parse(document))

        assertTrue(
            "the folder grant is kept for the folder's track",
            GrantScope.isNeeded(folder, setOf(track.uri!!)),
        )
    }
}
