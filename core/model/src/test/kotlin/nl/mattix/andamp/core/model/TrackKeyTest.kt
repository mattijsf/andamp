// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The key per-track settings are filed under is the same for one song reached through the
 * media library and through the document picker.
 */
class TrackKeyTest {
    private val fromLibrary =
        Track("media:53", "Muse", "Hexagons", 60_000, uri = "content://media/external/audio/media/53")
    private val fromPicker =
        Track(
            "picked-991",
            "Muse",
            "Hexagons",
            60_000,
            uri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fhex.mp3",
        )

    @Test
    fun `the same song is one key however it was reached`() {
        assertEquals(TrackKey.of(fromLibrary), TrackKey.of(fromPicker))
    }

    @Test
    fun `a different row id gives the same key`() {
        assertEquals(TrackKey.of(fromPicker), TrackKey.of(fromPicker.copy(id = "picked-4")))
    }

    @Test
    fun `a library row renumbered by a rescan keeps its key`() {
        assertEquals(
            TrackKey.of(fromLibrary),
            TrackKey.of(fromLibrary.copy(id = "media:812", uri = "content://media/external/audio/media/812")),
        )
    }

    @Test
    fun `case and spacing are not identity`() {
        assertEquals(TrackKey.of(fromLibrary), TrackKey.of(fromLibrary.copy(artist = "MUSE", title = "  Hexagons ")))
    }

    @Test
    fun `an untagged file is known by its name, whichever uri carries it`() {
        val picked =
            Track(
                "picked-1",
                "",
                "hex.mp3",
                0,
                uri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fhex.mp3",
            )
        // the library uses the display name as the title when the tags are blank
        val scanned = Track("media:7", "", "hex.mp3", 0, uri = "content://media/external/audio/media/7")

        assertEquals(TrackKey.of(picked), TrackKey.of(scanned))
    }

    @Test
    fun `a file with neither tags nor a title is known by the name in its uri`() {
        val bare = Track("x", "", "", 0, uri = "file:///storage/emulated/0/Music/hex.mp3")

        assertEquals("hex.mp3", TrackKey.of(bare))
    }

    @Test
    fun `a stream is keyed by its address`() {
        val station = Track("s", "", "", 0, uri = "http://ice.example.org:8000/stream")

        assertEquals("http://ice.example.org:8000/stream", TrackKey.of(station))
    }

    @Test
    fun `a library row with no tags at all is not mistaken for a file name`() {
        val row = Track("media:9", "", "", 0, uri = "content://media/external/audio/media/9")

        assertEquals("content://media/external/audio/media/9", TrackKey.of(row))
    }

    @Test
    fun `two different songs are two keys`() {
        assertNotEquals(TrackKey.of(fromLibrary), TrackKey.of(fromLibrary.copy(title = "Space Debris")))
    }
}
