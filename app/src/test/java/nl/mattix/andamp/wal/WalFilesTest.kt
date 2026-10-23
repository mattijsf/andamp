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
    fun `a path into the skin's folder by name reads the skin`() {
        // as Big Bento names its notifier script and its window pictures
        val big = wal("Big Bento.wal", "skin.xml" to "<b/>", "scripts/notifier.maki" to "maki")
        val bento = wal("Bento.wal", "skin.xml" to "<a/>")

        val inFolder = WalFiles.open(big, listOf(bento, big))
        assertEquals("maki", inFolder.read("@SKINSPATH@\\Big Bento\\scripts\\notifier.maki")?.decodeToString())
        assertEquals("maki", inFolder.read("../Big Bento/scripts/notifier.maki")?.decodeToString())

        // in the library, under its hash, by its name
        val hashed = wal("9e07.wal", "scripts/notifier.maki" to "maki")
        val inLibrary = WalFiles.open(hashed, mapOf("Big Bento" to hashed))
        assertEquals("maki", inLibrary.read("@SKINSPATH@\\Big Bento\\scripts\\notifier.maki")?.decodeToString())
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

    /** A skin.xml whose skininfo gives [name]. */
    private fun named(name: String) =
        """<WinampAbstractionLayer version="0.8"><skininfo><name>$name</name></skininfo></WinampAbstractionLayer>"""

    @Test
    fun `a library skin is known by its name in the library and by the name its skininfo gives`() {
        val big = wal("9e07.wal", "skin.xml" to named("Big Bento Deluxe"), "xml/player.xml" to "<player/>")

        val siblings = WalFiles.siblings(listOf("Big Bento" to big))

        assertEquals(mapOf("bigbento" to big, "bigbentodeluxe" to big), siblings)
        val files = WalFiles.open(wal("5f1c.wal", "skin.xml" to "<a/>"), siblings)
        assertEquals("<player/>", files.read("../Big Bento Deluxe/xml/player.xml")?.decodeToString())
        assertEquals("<player/>", files.read("../Big Bento/xml/player.xml")?.decodeToString())
    }

    @Test
    fun `a skin just stored, not named yet in the library, is known by its skininfo name`() {
        // an import names its row only once the skin has loaded: until then its name is its hash
        val big = wal("9e07.wal", "skin.xml" to named("Big Bento"), "scripts/notifier.maki" to "maki")

        val files = WalFiles.open(big, WalFiles.siblings(listOf("9e07" to big)))

        assertEquals("maki", files.read("@SKINSPATH@\\Big Bento\\scripts\\notifier.maki")?.decodeToString())
    }

    @Test
    fun `a name in the library wins over another skin's skininfo name`() {
        val mine = wal("aaaa.wal", "skin.xml" to named("Bento"))
        val bento = wal("bbbb.wal", "skin.xml" to named("Something else"))

        val siblings = WalFiles.siblings(listOf("Other" to mine, "Bento" to bento))

        assertEquals(bento, siblings["bento"])
        assertEquals(mine, siblings["other"])
    }

    @Test
    fun `the skininfo name is read from a skin xml in a folder, and a file without one gives none`() {
        val inFolder = wal("folder.wal", "Bento/skin.xml" to named("Bento Pro"), "Bento/xml/player.xml" to "<player/>")
        val unnamed = wal("unnamed.wal", "skin.xml" to """<WinampAbstractionLayer version="0.8"/>""")
        val blankName = wal("blank.wal", "skin.xml" to named(" "))
        val notAZip = dir.newFile("broken.wal").apply { writeText("not a zip") }
        val noSkinXml = wal("other.wal", "readme.txt" to named("Readme"))

        assertEquals("Bento Pro", WalFiles.infoName(inFolder))
        for (f in listOf(unnamed, blankName, notAZip, noSkinXml)) assertNull(f.name, WalFiles.infoName(f))
        assertEquals(
            "only the library names",
            setOf("unnamed", "broken", "other"),
            WalFiles.siblings(listOf("Unnamed" to unnamed, "Broken" to notAZip, "Other" to noSkinXml)).keys,
        )
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
