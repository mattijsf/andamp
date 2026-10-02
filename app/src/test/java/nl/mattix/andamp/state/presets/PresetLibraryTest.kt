// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.presets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PresetLibraryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun library() = PresetLibrary(temp.newFolder("presets"))

    private fun zipOf(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test
    fun `presets and their textures are extracted, other files are not`() {
        val pack =
            library().importZip(
                zipOf(
                    "a.milk" to "preset",
                    "sub/b.milk" to "preset",
                    "textures/noise.jpg" to "image",
                    "readme.txt" to "not a preset",
                    "cover.exe" to "definitely not",
                ),
                "Cream of the Crop",
            )

        assertEquals(2, pack.presetCount)
        assertTrue(File(pack.dir, "sub/b.milk").exists())
        assertTrue(File(pack.dir, "textures/noise.jpg").exists())
        assertFalse(File(pack.dir, "readme.txt").exists())
        assertFalse(File(pack.dir, "cover.exe").exists())
    }

    @Test
    fun `avs presets are extracted and counted as their own kind`() {
        val pack =
            library().importZip(
                zipOf(
                    "one.avs" to "preset",
                    "sub/two.AVS" to "preset",
                    "three.milk" to "preset",
                    "plugin.ape" to "a dll this platform cannot run",
                ),
                "Winamp AVS",
            )

        assertEquals(2, pack.avsCount)
        assertEquals(1, pack.presetCount)
        assertTrue(File(pack.dir, "sub/two.AVS").exists())
        assertFalse("an .ape file is not extracted", File(pack.dir, "plugin.ape").exists())
    }

    @Test
    fun `a pack of only avs files is still a pack`() {
        val library = library()
        library.importZip(zipOf("only.avs" to "preset"), "AVS only")

        val pack = library.packs().single()

        assertEquals(0, pack.presetCount)
        assertTrue(pack.runsOn(nl.mattix.andamp.state.VisPlugin.Avs))
        assertFalse(pack.runsOn(nl.mattix.andamp.state.VisPlugin.Milkdrop))
    }

    @Test
    fun `a textures directory is registered, and so is the pack root`() {
        val pack = library().importZip(zipOf("a.milk" to "p", "textures/n.png" to "i"), "pack")
        assertEquals(
            listOf(File(pack.dir, "textures"), pack.dir),
            pack.textureDirs,
        )
    }

    @Test
    fun `a pack whose textures sit loose beside the presets still finds them`() {
        val pack = library().importZip(zipOf("a.milk" to "p", "n.png" to "i"), "loose")
        assertEquals(listOf(pack.dir), pack.textureDirs)
        assertTrue(File(pack.dir, "n.png").exists())
    }

    /** An entry named `../` would otherwise write anywhere this process can write. */
    @Test
    fun `a zip-slip entry is not written outside the pack directory`() {
        val library = library()
        val pack = library.importZip(zipOf("../escaped.milk" to "p", "kept.milk" to "p"), "slip")
        assertEquals(1, pack.presetCount)
        assertFalse(File(pack.dir.parentFile, "escaped.milk").exists())
    }

    @Test
    fun `a name that is not a safe directory name is made into one`() {
        val pack = library().importZip(zipOf("a.milk" to "p"), "../../etc/pass wd?.zip")
        assertEquals(".._.._etc_pass wd_.zip", pack.name)
        assertEquals(1, pack.presetCount)
    }

    @Test
    fun `importing the same name twice replaces the pack`() {
        val library = library()
        library.importZip(zipOf("old.milk" to "p", "also-old.milk" to "p"), "pack")
        val second = library.importZip(zipOf("new.milk" to "p"), "pack")

        assertEquals(1, second.presetCount)
        assertFalse(File(second.dir, "old.milk").exists())
        assertEquals(1, library.packs().size)
    }

    @Test
    fun `a zip with no presets in it is not listed as a pack`() {
        val library = library()
        library.importZip(zipOf("textures/only.png" to "i"), "textures only")
        assertEquals(emptyList<InstalledPack>(), library.packs())
    }

    @Test
    fun `progress counts every file written`() {
        val seen = mutableListOf<Int>()
        library().importZip(
            zipOf("a.milk" to "p", "b.milk" to "p", "skipped.txt" to "x", "t/c.png" to "i"),
            "pack",
            onProgress = { seen += it },
        )
        assertEquals(listOf(1, 2, 3), seen)
    }

    @Test
    fun `a bundled pack installs once and says so only the first time`() {
        val library = library()

        assertTrue(
            "the first install reports a new pack",
            library.install("AndAmp", mapOf("a.avs" to byteArrayOf(1)), 1),
        )
        assertFalse("the same version again reports no new pack", library.install("AndAmp", mapOf("a.avs" to byteArrayOf(1)), 1))
        assertEquals(1, checkNotNull(library.pack("AndAmp")).avsCount)
    }

    @Test
    fun `a version bump rewrites the bundled pack without re-announcing it`() {
        val library = library()
        library.install("AndAmp", mapOf("old.avs" to byteArrayOf(1)), 1)

        assertFalse(
            "an upgrade reports no new pack",
            library.install("AndAmp", mapOf("new.avs" to byteArrayOf(2)), 2),
        )
        val files = checkNotNull(library.pack("AndAmp")).dir.listFiles()!!.map { it.name }
        assertEquals(listOf("new.avs"), files.filter { it.endsWith(".avs") })
    }

    @Test
    fun `a deleted bundled pack stays deleted`() {
        val library = library()
        library.install("AndAmp", mapOf("a.avs" to byteArrayOf(1)), 1)
        library.delete(checkNotNull(library.pack("AndAmp")))

        assertFalse(library.install("AndAmp", mapOf("a.avs" to byteArrayOf(1)), 1))
        assertFalse(
            "a deleted pack stays deleted across an upgrade",
            library.install(
                "AndAmp",
                mapOf("a.avs" to byteArrayOf(1)),
                2,
            ),
        )
        assertEquals(null, library.pack("AndAmp"))
    }

    @Test
    fun `packs are listed by name and can be looked up and deleted`() {
        val library = library()
        library.importZip(zipOf("a.milk" to "p"), "Zebra")
        library.importZip(zipOf("a.milk" to "p"), "apple")

        assertEquals(listOf("apple", "Zebra"), library.packs().map { it.name })
        val zebra = checkNotNull(library.pack("Zebra"))
        assertTrue(library.delete(zebra))
        assertEquals(listOf("apple"), library.packs().map { it.name })
    }
}
