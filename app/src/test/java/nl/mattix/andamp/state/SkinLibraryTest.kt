// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.BundledSkins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkinLibraryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val library = SkinLibrary(app)

    private fun bytes(
        seed: Int,
        size: Int = 64,
    ) = ByteArray(size) { (it + seed).toByte() }

    @Test
    fun `a fresh library offers the skins that ship with the app and nothing else`() {
        assertEquals(BundledSkins.all.map { it.id }, library.list().map { it.id })
        assertEquals(SkinEntry.BASE_ID, library.currentId)
    }

    @Test
    fun `saved skins round-trip their bytes and names`() {
        val entry = library.save(bytes(1), "TopazAmp.wsz")
        assertEquals("TopazAmp.wsz", entry.name)
        assertTrue(library.open(entry.id)!!.use { it.readBytes() }.contentEquals(bytes(1)))
        assertEquals((BundledSkins.all.map { it.id } + entry.id).sorted(), library.list().map { it.id }.sorted())
    }

    @Test
    fun `the same file loaded twice is one entry`() {
        val first = library.save(bytes(7), "Zaxon.wsz")
        val second = library.save(bytes(7), "Zaxon-copy.wsz")
        assertEquals(first.id, second.id)
        assertEquals(BundledSkins.all.size + 1, library.list().size)
    }

    @Test
    fun `re-adding under a new name refreshes the display name`() {
        val first = library.save(bytes(7), "Zaxon.wsz")
        library.save(bytes(7), "Zaxon-renamed.wsz")
        assertEquals("Zaxon-renamed.wsz", library.nameOf(first.id))
    }

    @Test
    fun `different files are different entries`() {
        val a = library.save(bytes(1), "A.wsz")
        val b = library.save(bytes(2), "B.wsz")
        assertTrue(a.id != b.id)
        assertEquals(BundledSkins.all.size + 2, library.list().size)
    }

    @Test
    fun `delete removes the file and its name`() {
        val entry = library.save(bytes(3), "Gone.wsz")
        library.delete(entry.id)
        assertNull(library.open(entry.id))
        assertTrue(library.list().none { it.id == entry.id })
    }

    @Test
    fun `deleting the active skin falls back to the bundled one`() {
        val entry = library.save(bytes(4), "Active.wsz")
        library.currentId = entry.id
        library.delete(entry.id)
        assertEquals(SkinEntry.BASE_ID, library.currentId)
    }

    @Test
    fun `a skin this install can wear is one that ships with the app or one on disk`() {
        val entry = library.save(bytes(10), "Here.wsz")

        assertTrue(library.has(entry.id))
        assertTrue(library.has(BundledSkins.SPOT.id))

        library.delete(entry.id)

        assertFalse(library.has(entry.id))
        assertFalse(library.has("a-skin-from-a-build-that-no-longer-exists"))
    }

    @Test
    fun `a source's skin goes on over the listener's own without changing it`() {
        library.choose(BundledSkins.LIGHT.id)
        val asked = SkinLibrary.choices

        assertEquals(BundledSkins.SPOT.id, library.wear(BundledSkins.SPOT.id, asked))

        assertEquals(BundledSkins.SPOT.id, library.worn)
        assertEquals("the listener's own choice stays", BundledSkins.LIGHT.id, library.currentId)
    }

    /**
     * A source's skin is decided on one thread and stored on another. A skin the listener
     * picks in between wins.
     */
    @Test
    fun `a source's skin asked for before the listener chose one is turned down`() {
        val own = library.save(bytes(11), "Own.wsz")
        val asked = SkinLibrary.choices
        library.choose(own.id)

        assertNull(library.wear(BundledSkins.SPOT.id, asked))

        assertNull(library.wearing)
        assertEquals(own.id, library.worn)
    }

    /** Deleting the skin that is on counts as a choice. */
    @Test
    fun `a source's skin asked for before the worn skin was taken away is turned down`() {
        val worn = library.save(bytes(12), "Worn.wsz")
        library.choose(worn.id)
        val asked = SkinLibrary.choices

        library.delete(worn.id)

        assertNull(library.wear(BundledSkins.SPOT.id, asked))
        assertEquals(SkinEntry.BASE_ID, library.worn)
    }

    @Test
    fun `a source asking for the skin that is already the listener's own wears nothing`() {
        library.choose(BundledSkins.SPOT.id)

        assertEquals(BundledSkins.SPOT.id, library.wear(BundledSkins.SPOT.id, SkinLibrary.choices))

        assertNull("nothing is worn over a skin that is already on", library.wearing)
    }

    @Test
    fun `a skin that was worn for a source and will not open is taken off`() {
        library.choose(BundledSkins.LIGHT.id)
        library.wear(BundledSkins.SPOT.id, SkinLibrary.choices)

        library.unwear(BundledSkins.SPOT.id)

        assertNull(library.wearing)
        assertEquals(BundledSkins.LIGHT.id, library.worn)
    }

    @Test
    fun `a skin that ships with the app cannot be deleted`() {
        BundledSkins.all.forEach { library.delete(it.id) }
        assertEquals(BundledSkins.all.map { it.id }, library.list().map { it.id })
    }

    /** They have no file of their own, so the library reads them out of the assets. */
    @Test
    fun `a skin that ships with the app opens like any other`() {
        BundledSkins.all.forEach { bundled ->
            val bytes = library.open(bundled.id)!!.use { it.readBytes() }
            assertTrue("${bundled.id} opens with its bytes", bytes.size > 1000)
        }
    }

    @Test
    fun `the library and the active choice survive a restart`() {
        val entry = library.save(bytes(5), "Kept.wsz")
        library.currentId = entry.id

        val reopened = SkinLibrary(app) // a fresh process would build a new one
        assertEquals(entry.id, reopened.currentId)
        assertEquals("Kept.wsz", reopened.nameOf(entry.id))
        assertTrue(reopened.open(entry.id) != null)
    }

    @Test
    fun `the bundled skins head the list and the user's follow them sorted`() {
        library.save(bytes(6), "zzz.wsz")
        library.save(bytes(8), "aaa.wsz")
        val names = library.list().map { it.name }
        assertEquals(BundledSkins.all.map { it.name }, names.take(BundledSkins.all.size))
        val stored = names.drop(BundledSkins.all.size)
        assertEquals(stored.sortedBy { it.lowercase() }, stored)
    }

    @Test
    fun `a skin from the legacy single-slot store is imported under its stored name`() {
        // the legacy slot: skins/current.wsz, with its name in the "skin" prefs
        val dir = java.io.File(app.filesDir, "skins").apply { mkdirs() }
        java.io.File(dir, "current.wsz").writeBytes(bytes(9))
        app
            .getSharedPreferences("skin", Application.MODE_PRIVATE)
            .edit()
            .putString("name", "OldFavourite.wsz")
            .apply()

        val migrated = SkinLibrary(app)
        val entry = migrated.list().single { BundledSkins.of(it.id) == null }
        assertEquals("OldFavourite.wsz", entry.name)
        assertEquals(entry.id, migrated.currentId)
        assertTrue(migrated.open(entry.id)!!.use { it.readBytes() }.contentEquals(bytes(9)))
        assertTrue("the legacy slot file is removed", !java.io.File(dir, "current.wsz").exists())
    }

    @Test
    fun `a failed migration keeps the legacy slot and its name`() {
        // learn where those bytes would be stored, before planting the legacy
        // slot: constructing a library is what runs the migration
        val hash = SkinLibrary(app).save(bytes(9), "probe").id
        SkinLibrary(app).delete(hash)
        val dir = java.io.File(app.filesDir, "skins")
        // a non-empty directory in the store's place: the write-then-rename
        // cannot rename over it and cannot delete it either, so the save fails
        java.io.File(dir, "$hash.wsz/occupied").mkdirs()
        val legacy = java.io.File(dir, "current.wsz").apply { writeBytes(bytes(9)) }
        app
            .getSharedPreferences("skin", Application.MODE_PRIVATE)
            .edit()
            .putString("name", "OnlyCopy.wsz")
            .apply()

        val migrated = SkinLibrary(app)

        assertTrue("the legacy slot file is kept", legacy.exists())
        assertEquals(
            "the legacy slot's name is kept",
            "OnlyCopy.wsz",
            app.getSharedPreferences("skin", Application.MODE_PRIVATE).getString("name", null),
        )
        assertEquals(SkinEntry.BASE_ID, migrated.currentId)
    }
}
