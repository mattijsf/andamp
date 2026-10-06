// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [readableSources]: a local file is opened under the uri the app says it can be read
 * under right now, asked at the moment it is opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadableSourcesTest {
    /** A source that opens nothing and remembers what it was asked to open. */
    private class Recording : DataSource {
        var opened: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            opened = dataSpec.uri
            return 0
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = -1

        override fun getUri(): Uri? = opened

        override fun close() = Unit

        override fun addTransferListener(transferListener: TransferListener) = Unit
    }

    private val row =
        Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fone.mp3",
        )
    private val inLibrary = Uri.parse("content://media/external/audio/media/8")

    private fun open(
        uri: Uri,
        readable: (Uri) -> Uri,
    ): Uri? {
        val upstream = Recording()
        readableSources({ upstream }, readable).createDataSource().open(DataSpec(uri))
        return upstream.opened
    }

    @Test
    fun `a row is opened under the uri it can be read under`() {
        assertEquals(inLibrary, open(row) { inLibrary })
    }

    @Test
    fun `a row that can be read as it is is opened as it is`() {
        assertEquals(row, open(row) { it })
    }

    @Test
    fun `a station's address is not asked about`() {
        var asked = 0
        val station = Uri.parse("https://radio.example.org/stream")

        val opened =
            open(station) {
                asked++
                inLibrary
            }

        assertEquals(station, opened)
        assertEquals(0, asked)
    }

    @Test
    fun `the uri is asked for each time the file is opened`() {
        var asked = 0
        val upstream = Recording()
        val sources =
            readableSources({ upstream }) {
                asked++
                it
            }

        sources.createDataSource().open(DataSpec(row))
        sources.createDataSource().open(DataSpec(row))

        assertEquals(2, asked)
    }
}
