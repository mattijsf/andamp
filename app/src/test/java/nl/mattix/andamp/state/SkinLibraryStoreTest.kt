// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel

/**
 * How [SkinLibrary] stores an added skin, so a phone that switches off right after the add keeps
 * it: the bytes are written and flushed under a temporary name, renamed into place, and the
 * skins folder is flushed, before the name is kept. A store that fails leaves no part and keeps
 * the copy stored before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkinLibraryStoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val library = SkinLibrary(app)

    private val skinsPrefs get() = app.getSharedPreferences("skins", Application.MODE_PRIVATE)

    private fun bytes(seed: Int) = ByteArray(64) { (it + seed).toByte() }

    private val skins get() = File(app.filesDir, "skins")

    /** The size of [name] in the skins folder, or "none" when it is not there. */
    private fun sizeOf(name: String) = File(skins, name).let { if (it.exists()) "${it.length()}" else "none" }

    private val failingFlush: (FileChannel) -> Unit = { throw IOException("the disk failed") }

    /** [bytes] streamed to a temporary file in the cache folder, as a pick leaves them for [SkinLibrary.storeModern]. */
    private fun picked(bytes: ByteArray) =
        File
            .createTempFile("pick", ".tmp", app.cacheDir)
            .apply { writeBytes(bytes) }

    /** Whether [name] in the skins folder holds [bytes]. */
    private fun holds(
        name: String,
        bytes: ByteArray,
    ): Boolean {
        val file = File(skins, name)
        return file.readBytes().contentEquals(bytes)
    }

    @Test
    fun `a classic skin is flushed whole before the rename names it, and the folder after it, before its name is kept`() {
        val id = library.idFor(bytes(14))
        val seen = ArrayList<String>()
        val flushing =
            SkinLibrary(app) { channel ->
                seen += "part ${sizeOf("$id.wsz.tmp")}, stored ${sizeOf("$id.wsz")}, named ${skinsPrefs.contains("name:$id")}"
                channel.force(true)
            }

        flushing.save(bytes(14), "Flushed.wsz")

        assertEquals(listOf("part 64, stored none, named false", "part none, stored 64, named false"), seen)
        assertTrue(holds("$id.wsz", bytes(14)))
        assertEquals("Flushed.wsz", library.nameOf(id))
        assertEquals("no part is left", listOf("$id.wsz"), skins.list()!!.toList())
    }

    @Test
    fun `a modern skin's copy is flushed whole before the rename names it, and the folder after it`() {
        val bytes = zipOf("skin.xml")
        val tmp = picked(bytes)
        val id = library.idFor(bytes)
        val seen = ArrayList<String>()
        val flushing =
            SkinLibrary(app) { channel ->
                seen += "part ${sizeOf("$id.wal.tmp")}, stored ${sizeOf("$id.wal")}"
                channel.force(true)
            }

        flushing.storeModern(tmp, id)

        assertEquals(listOf("part ${bytes.size}, stored none", "part none, stored ${bytes.size}"), seen)
    }

    @Test
    fun `a classic skin that cannot be flushed is not stored, and the copy stored before stays`() {
        val before = library.save(bytes(15), "Before.wsz")
        val failing = SkinLibrary(app, failingFlush)
        val added = library.idFor(bytes(16))

        assertThrows(IOException::class.java) { failing.save(bytes(15), "Again.wsz") }
        assertThrows(IOException::class.java) { failing.save(bytes(16), "Added.wsz") }

        assertTrue(holds("${before.id}.wsz", bytes(15)))
        assertEquals("Before.wsz", library.nameOf(before.id))
        assertFalse(library.has(added))
        assertFalse(skinsPrefs.contains("name:$added"))
        assertEquals("no part is left", listOf("${before.id}.wsz"), skins.list()!!.toList())
    }

    @Test
    fun `a modern skin that cannot be flushed is not stored, and the copy stored before stays`() {
        val before = library.addModern(app, "Bento")
        val kept = File(skins, "$before.wal").readBytes()
        val again = picked(kept)
        val addedBytes = zipOf("skin.xml", "xml/other.xml")
        val added = picked(addedBytes)
        val failing = SkinLibrary(app, failingFlush)

        assertThrows(IOException::class.java) { failing.storeModern(again, before) }
        assertThrows(IOException::class.java) { failing.storeModern(added, library.idFor(addedBytes)) }

        assertTrue(holds("$before.wal", kept))
        assertEquals("Bento", library.nameOf(before))
        assertFalse(library.has(library.idFor(addedBytes)))
        assertEquals("no part is left", listOf("$before.wal"), skins.list()!!.toList())
    }

    @Test
    fun `a skins folder that cannot be flushed still stores the skin, and a classic one keeps its name`() {
        var flushes = 0
        val folderFails =
            SkinLibrary(app) { channel ->
                // each store flushes its bytes first and the folder second
                if (++flushes % 2 == 0) throw IOException("the folder was not flushed")
                channel.force(true)
            }
        val modernBytes = zipOf("skin.xml", "xml/folder.xml")
        val modern = library.idFor(modernBytes)

        val classic = folderFails.save(bytes(19), "Folder.wsz")
        val stored = folderFails.storeModern(picked(modernBytes), modern)

        assertEquals(4, flushes)
        assertTrue(holds("${classic.id}.wsz", bytes(19)))
        assertEquals("Folder.wsz", library.nameOf(classic.id))
        assertEquals("$modern.wal", stored.name)
        assertTrue(holds("$modern.wal", modernBytes))
        assertEquals("no part is left", listOf("${classic.id}.wsz", "$modern.wal").sorted(), skins.list()!!.sorted())
    }

    @Test
    fun `a skin whose rename fails keeps the copy stored before and its name`() {
        val before = library.save(bytes(20), "Before.wsz")
        val modern = library.addModern(app, "Bento")
        val kept = File(skins, "$modern.wal").readBytes()
        val partGone =
            SkinLibrary(app) { channel ->
                // the part goes before the rename, so the rename has nothing to move
                skins.listFiles { file -> file.name.endsWith(".tmp") }!!.forEach { it.delete() }
                channel.force(true)
            }

        assertThrows(IllegalStateException::class.java) { partGone.save(bytes(20), "Again.wsz") }
        assertThrows(IllegalStateException::class.java) { partGone.storeModern(picked(kept), modern) }

        assertTrue(holds("${before.id}.wsz", bytes(20)))
        assertEquals("Before.wsz", library.nameOf(before.id))
        assertTrue(holds("$modern.wal", kept))
        assertEquals("Bento", library.nameOf(modern))
        assertEquals(listOf("${before.id}.wsz", "$modern.wal").sorted(), skins.list()!!.sorted())
    }

    @Test
    fun `a skin that cannot be renamed into place leaves no part and no name`() {
        val classic = library.idFor(bytes(18))
        val modernBytes = zipOf("skin.xml", "xml/blocked.xml")
        val modern = library.idFor(modernBytes)
        val tmp = picked(modernBytes)
        // a non-empty directory in each store's place, which a rename cannot replace
        File(skins, "$classic.wsz/occupied").mkdirs()
        File(skins, "$modern.wal/occupied").mkdirs()

        assertThrows(IllegalStateException::class.java) { library.save(bytes(18), "Blocked.wsz") }
        assertThrows(IllegalStateException::class.java) { library.storeModern(tmp, modern) }

        assertEquals("none", sizeOf("$classic.wsz.tmp"))
        assertEquals("none", sizeOf("$modern.wal.tmp"))
        assertFalse(skinsPrefs.contains("name:$classic"))
    }
}
