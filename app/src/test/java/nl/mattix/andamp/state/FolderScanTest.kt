// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.state.FolderScan.Doc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD > DIR: what a picked folder contributes to the playlist, and in what order. */
class FolderScanTest {
    private fun dir(
        id: String,
        name: String = id,
    ) = Doc(id, name, FolderScan.MIME_DIRECTORY)

    private fun file(
        name: String,
        mime: String = "audio/mpeg",
        id: String = name,
    ) = Doc(id, name, mime)

    private fun treeOf(vararg folders: Pair<String, List<Doc>>): FolderScan.Tree {
        val map = folders.toMap()
        return FolderScan.Tree { map[it].orEmpty() }
    }

    @Test
    fun `a flat folder yields its audio in name order`() {
        val tree = treeOf("root" to listOf(file("c.mp3"), file("a.mp3"), file("B.mp3")))

        val scan = FolderScan.audioFiles(tree, "root")

        assertEquals(listOf("a.mp3", "B.mp3", "c.mp3"), scan.files.map { it.name })
        assertEquals(0, scan.dropped)
    }

    @Test
    fun `subfolders are walked after the folder's own files, depth first`() {
        val tree =
            treeOf(
                "root" to listOf(dir("sub2", "second"), file("top.mp3"), dir("sub1", "first")),
                "sub1" to listOf(file("one.mp3"), dir("deep", "deeper")),
                "deep" to listOf(file("deep.mp3")),
                "sub2" to listOf(file("two.mp3")),
            )

        val scan = FolderScan.audioFiles(tree, "root")

        assertEquals(listOf("top.mp3", "one.mp3", "deep.mp3", "two.mp3"), scan.files.map { it.name })
    }

    @Test
    fun `non-audio is left behind`() {
        val tree =
            treeOf(
                "root" to
                    listOf(
                        file("cover.jpg", "image/jpeg"),
                        file("notes.txt", "text/plain"),
                        file("song.mp3"),
                        file("clip.mp4", "video/mp4"),
                    ),
            )

        assertEquals(listOf("song.mp3"), FolderScan.audioFiles(tree, "root").files.map { it.name })
    }

    @Test
    fun `an audio file reported as octet-stream is still audio`() {
        // some providers report application/octet-stream for ordinary audio files
        val tree =
            treeOf(
                "root" to
                    listOf(
                        file("song.flac", "application/octet-stream"),
                        file("archive.zip", "application/octet-stream"),
                    ),
            )

        assertEquals(listOf("song.flac"), FolderScan.audioFiles(tree, "root").files.map { it.name })
    }

    @Test
    fun `a folder that loops back on itself terminates`() {
        val tree =
            treeOf(
                "root" to listOf(dir("sub"), file("a.mp3")),
                "sub" to listOf(dir("root"), file("b.mp3")),
            )

        val scan = FolderScan.audioFiles(tree, "root")

        assertEquals(listOf("a.mp3", "b.mp3"), scan.files.map { it.name })
    }

    @Test
    fun `an enormous folder is capped, and says how much it left out`() {
        val many = (1..FolderScan.MAX_FILES + 25).map { file("track%04d.mp3".format(it)) }
        val tree = treeOf("root" to many)

        val scan = FolderScan.audioFiles(tree, "root")

        assertEquals(FolderScan.MAX_FILES, scan.files.size)
        assertEquals(25, scan.dropped)
    }

    @Test
    fun `an empty folder adds nothing`() {
        val scan = FolderScan.audioFiles(FolderScan.Tree { emptyList() }, "root")

        assertTrue(scan.files.isEmpty())
        assertEquals(0, scan.dropped)
    }
}
