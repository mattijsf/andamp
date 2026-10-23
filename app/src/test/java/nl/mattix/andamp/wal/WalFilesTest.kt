// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A skin reads the skins beside it by folder name, and only those a path names are opened. */
class WalFilesTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun wal(
        name: String,
        vararg entries: Pair<String, String>,
    ): File =
        dir.newFile(name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                for ((path, text) in entries) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun `a path into a sibling reads that sibling, by folder name without spaces`() {
        val bento = wal("Bento.wal", "skin.xml" to "<a/>")
        val big = wal("Big Bento.wal", "skin.xml" to "<b/>", "xml/player.xml" to "<player/>")

        val files = WalFiles.open(bento, listOf(bento, big))

        // the two ways Bento and Big Bento write it
        assertEquals("<player/>", files.read("../Big Bento/xml/player.xml")?.decodeToString())
        assertEquals("<player/>", files.read("@SKINSPATH@\\Big Bento\\xml\\player.xml")?.decodeToString())
        assertEquals("the skin's file", "<a/>", files.read("skin.xml")?.decodeToString())
    }

    @Test
    fun `a sibling named "Big Bento" resolves from a hash-named file`() {
        // library files are named by their hash
        val bento = wal("5f1c.wal", "skin.xml" to "<a/>")
        val big = wal("9e07.wal", "skin.xml" to "<b/>", "xml/player.xml" to "<player/>")

        val files = WalFiles.open(bento, mapOf("Bento" to bento, "Big Bento" to big))

        assertEquals("<player/>", files.read("../Big Bento/xml/player.xml")?.decodeToString())
        assertEquals("<player/>", files.read("@SKINSPATH@\\Big Bento\\xml\\player.xml")?.decodeToString())
        assertNull("the file's name is not the skin's", files.read("../9e07/xml/player.xml"))
        assertEquals("the skin's file", "<a/>", files.read("skin.xml")?.decodeToString())
    }

    @Test
    fun `a skin known by two names opens once and reads the same under both`() {
        val bento = wal("5f1c.wal", "skin.xml" to "<a/>")
        val big = wal("9e07.wal", "xml/player.xml" to "<player/>")

        // its name in the library, and the name its skininfo gives
        val files = WalFiles.open(bento, mapOf("Big Bento" to big, "Big Bento Deluxe" to big))
        assertEquals("<player/>", files.read("../Big Bento/xml/player.xml")?.decodeToString())
        big.delete()

        assertEquals("<player/>", files.read("../Big Bento Deluxe/xml/player.xml")?.decodeToString())
    }

    @Test
    fun `a sibling is opened when a path first names it, not before`() {
        val bento = wal("Bento.wal", "skin.xml" to "<a/>")
        // not a zip yet when the skin's files are made: opened then, it would read as missing
        val later = dir.newFile("Later.wal").apply { writeText("not a zip") }

        val files = WalFiles.open(bento, listOf(bento, later))
        later.delete()
        wal("Later.wal", "skin.xml" to "<later/>")

        assertEquals("<later/>", files.read("../Later/skin.xml")?.decodeToString())
    }

    @Test
    fun `a sibling that does not open reads as missing`() {
        val bento = wal("Bento.wal", "skin.xml" to "<a/>")
        val broken = dir.newFile("Broken.wal").apply { writeText("not a zip") }

        val files = WalFiles.open(bento, listOf(bento, broken))

        assertEquals("<a/>", files.read("skin.xml")?.decodeToString())
        assertNull(files.read("../Broken/skin.xml"))
    }
}
