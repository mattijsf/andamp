// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [DocumentFilePath]: the file a storage document stands for. */
class DocumentFilePathTest {
    private val storage = "content://com.android.externalstorage.documents"
    private val phone = "/storage/emulated/0"

    @Test
    fun `a row under a folder on the phone is a file under the phone's storage`() {
        val row = "$storage/tree/primary%3AMusic%2FRoad/document/primary%3AMusic%2FRoad%2FCD%201%2Fone.mp3"

        assertEquals("/storage/emulated/0/Music/Road/CD 1/one.mp3", DocumentFilePath.of(row, phone))
    }

    @Test
    fun `a row on a card is a file under the card`() {
        val row = "$storage/tree/1A2B-3C4D%3AMusic/document/1A2B-3C4D%3AMusic%2Fx.mp3"

        assertEquals("/storage/1A2B-3C4D/Music/x.mp3", DocumentFilePath.of(row, phone))
    }

    @Test
    fun `a single picked file has a path too`() {
        assertEquals(
            "/storage/emulated/0/Download/song.mp3",
            DocumentFilePath.of("$storage/document/primary%3ADownload%2Fsong.mp3", phone),
        )
    }

    @Test
    fun `the Documents shortcut is the Documents folder`() {
        assertEquals(
            "/storage/emulated/0/Documents/Tapes/a.mp3",
            DocumentFilePath.of("$storage/document/home%3ATapes%2Fa.mp3", phone),
        )
    }

    @Test
    fun `a document of another provider has no path`() {
        assertNull(DocumentFilePath.of("content://com.android.providers.media.documents/document/audio%3A42", phone))
        assertNull(DocumentFilePath.of("content://media/external/audio/media/9", phone))
        assertNull(DocumentFilePath.of("https://radio.example.org/stream", phone))
    }

    @Test
    fun `a folder's own address and a whole storage are not files`() {
        assertNull(DocumentFilePath.of("$storage/tree/primary%3AMusic", phone))
        assertNull(DocumentFilePath.of("$storage/document/primary%3A", phone))
    }
}
