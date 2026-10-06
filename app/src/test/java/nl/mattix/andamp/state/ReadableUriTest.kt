// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReadableUri]: which uri a file is opened under right now. The row's own while a grant
 * covers it, the library's for the same file when not, and the row's own again when there
 * is no way at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadableUriTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val road = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FRoad"
    private val gym = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FGym"
    private val one = "$road/document/primary%3AMusic%2FRoad%2Fone.mp3"
    private val two = "$road/document/primary%3AMusic%2FRoad%2Ftwo.mp3"
    private val three = "$gym/document/primary%3AMusic%2FGym%2Fthree.mp3"
    private val inLibrary = "content://media/external/audio/media/8"

    /** A library that lists the files at [listed] and counts how often it is asked. */
    private class Library(
        app: Application,
        val listed: Set<String> = emptySet(),
    ) : MediaStoreAudio(app) {
        var asked = 0

        override fun findAt(documentUri: String): Uri? {
            asked++
            return if (documentUri in listed) Uri.parse("content://media/external/audio/media/8") else null
        }
    }

    @Test
    fun `a row a grant covers is opened under its own uri and the library is not asked`() {
        val library = Library(app, listed = setOf(one))
        val readable = ReadableUri({ listOf(road) }, library)

        assertEquals(one, readable.of(one))
        assertEquals(0, library.asked)
    }

    @Test
    fun `a row whose folder is gone is opened under the library's uri for that file`() {
        val readable = ReadableUri({ emptyList() }, Library(app, listed = setOf(one)))

        assertEquals(inLibrary, readable.of(one))
    }

    @Test
    fun `a row that can be read neither way keeps its own uri`() {
        val readable = ReadableUri({ emptyList() }, Library(app))

        assertEquals(one, readable.of(one))
    }

    @Test
    fun `a grant on the picked file itself is a grant too`() {
        val picked = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fsong.mp3"
        val library = Library(app, listed = setOf(picked))

        assertEquals(picked, ReadableUri({ listOf(picked) }, library).of(picked))
        assertEquals(0, library.asked)
    }

    @Test
    fun `what is not a file in storage is never looked up`() {
        val library = Library(app)
        val readable = ReadableUri({ emptyList() }, library)

        listOf(
            "content://media/external/audio/media/9",
            "content://com.android.providers.media.documents/document/audio%3A42",
            "https://radio.example.org/stream",
            "asset:///audio/intro.mp3",
        ).forEach { assertEquals(it, readable.of(it)) }
        assertEquals(0, library.asked)
    }

    @Test
    fun `a folder is unreached when it has no grant and the library does not list its music`() {
        val uris = listOf(one, two, three)

        assertEquals(setOf(road, gym), ReadableUri({ emptyList() }, Library(app)).unreached(uris))
        assertEquals(setOf(gym), ReadableUri({ emptyList() }, Library(app, listed = setOf(one))).unreached(uris))
        assertEquals(setOf(road), ReadableUri({ listOf(gym) }, Library(app)).unreached(uris))
        assertEquals(emptySet<String>(), ReadableUri({ listOf(gym, road) }, Library(app)).unreached(uris))
    }

    @Test
    fun `one row stands for its folder, so a long list costs one lookup a folder`() {
        val library = Library(app)
        val uris = (1..200).map { "$road/document/primary%3AMusic%2FRoad%2F$it.mp3" }

        ReadableUri({ emptyList() }, library).unreached(uris)

        assertEquals(1, library.asked)
    }
}
