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

    // modern skins

    private val skinsPrefs get() = app.getSharedPreferences("skins", Application.MODE_PRIVATE)

    @Test
    fun `a modern skin is stored as a wal file and listed by name with the classic ones`() {
        library.save(bytes(6), "zzz.wsz")
        val modern = library.addModern(app, "Bento")
        library.save(bytes(8), "aaa.wsz")

        assertTrue(java.io.File(app.filesDir, "skins/$modern.wal").isFile)
        val stored = library.list().drop(BundledSkins.all.size)
        assertEquals(listOf("aaa.wsz", "Bento", "zzz.wsz"), stored.map { it.name })
        assertEquals(SkinKind.MODERN, stored.single { it.id == modern }.kind)
        assertTrue("classic rows stay classic", stored.filter { it.id != modern }.all { it.kind == SkinKind.CLASSIC })
    }

    @Test
    fun `a stored modern skin opens, has a file, and reads modern`() {
        val modern = library.addModern(app, "Bento")

        assertTrue(library.has(modern))
        assertEquals(SkinKind.MODERN, library.kindOf(modern))
        assertEquals("$modern.wal", library.fileOf(modern)!!.name)
        assertTrue(library.open(modern)!!.use { it.readBytes() }.isNotEmpty())
        assertFalse("the classic-only check turns it down", library.hasClassic(modern))
    }

    @Test
    fun `a classic skin, a skin that ships with the app and a missing id all read classic`() {
        val classic = library.save(bytes(1), "A.wsz").id

        assertEquals(SkinKind.CLASSIC, library.kindOf(classic))
        assertEquals(SkinKind.CLASSIC, library.kindOf(BundledSkins.SPOT.id))
        assertEquals(SkinKind.CLASSIC, library.kindOf("a-skin-from-a-build-that-no-longer-exists"))
        assertNull("a skin that ships with the app has no file", library.fileOf(BundledSkins.SPOT.id))
        assertNull(library.fileOf("a-skin-from-a-build-that-no-longer-exists"))
    }

    @Test
    fun `deleting a modern skin removes its file and name`() {
        val modern = library.addModern(app, "Bento")

        library.delete(modern)

        assertFalse(library.has(modern))
        assertNull(library.open(modern))
        assertEquals(modern, library.nameOf(modern))
    }

    @Test
    fun `a stored modern skin is a copy, and the temporary file goes`() {
        val tmp =
            java.io.File
                .createTempFile("pick", ".tmp", app.cacheDir)
                .apply { writeBytes(zipOf("skin.xml")) }
        val id = library.idFor(tmp.readBytes())

        val stored = library.storeModern(tmp, id)

        assertEquals("$id.wal", stored.name)
        assertTrue(stored.readBytes().contentEquals(zipOf("skin.xml")))
        assertFalse(tmp.exists())
        assertFalse("no part is left", java.io.File(app.filesDir, "skins/$id.wal.tmp").exists())
    }

    @Test
    fun `what a store cut short leaves is dropped, and finished skins stay`() {
        val classic = library.save(bytes(1), "A.wsz").id
        val modern = library.addModern(app, "Bento")
        val skins = java.io.File(app.filesDir, "skins")
        val unnamed = java.io.File(skins, "0123456789abcdef0123456789abcdef.wal").apply { writeBytes(zipOf("skin.xml")) }
        val part = java.io.File(skins, "$modern.wal.tmp").apply { writeBytes(bytes(2)) }

        library.dropUnfinished()

        assertFalse(unnamed.exists())
        assertFalse(part.exists())
        assertTrue(library.has(classic))
        assertTrue(library.has(modern))
    }

    @Test
    fun `a modern name drops a trailing wal in any case and keeps anything else`() {
        assertEquals("Bento", SkinLibrary.modernName("Bento.wal"))
        assertEquals("Bento", SkinLibrary.modernName("Bento.WAL"))
        assertEquals("Bento.wsz", SkinLibrary.modernName("Bento.wsz"))
        assertEquals("", SkinLibrary.modernName(".wal"))
    }

    @Test
    fun `the last classic choice is the current skin until one is stored`() {
        library.currentId = BundledSkins.LIGHT.id

        assertEquals(BundledSkins.LIGHT.id, library.lastClassic)
    }

    @Test
    fun `every classic choice becomes the last classic choice`() {
        library.choose(BundledSkins.SPOT.id)
        library.choose(BundledSkins.LIGHT.id)

        assertEquals(BundledSkins.LIGHT.id, skinsPrefs.getString("classic", null))
    }

    @Test
    fun `a modern choice keeps the classic choice before it as the last one`() {
        // a current skin from before the key existed, so the key is still absent
        library.currentId = BundledSkins.LIGHT.id
        val modern = library.addModern(app, "Bento")

        library.choose(modern)

        assertEquals(modern, library.currentId)
        assertEquals(BundledSkins.LIGHT.id, library.lastClassic)
        assertEquals(BundledSkins.LIGHT.id, skinsPrefs.getString("classic", null))
    }

    @Test
    fun `with no classic choice to keep the last classic choice is the base`() {
        val modern = library.addModern(app, "Bento")
        library.currentId = modern

        assertEquals(SkinEntry.BASE_ID, library.lastClassic)
    }

    @Test
    fun `a last classic choice that is no longer installed gives way`() {
        val gone = library.save(bytes(2), "Gone.wsz").id
        library.choose(gone)
        java.io.File(app.filesDir, "skins/$gone.wsz").delete()

        assertEquals(
            "not the missing skin, and not the current one, which is missing too",
            SkinEntry.BASE_ID,
            library.lastClassic,
        )
    }

    /**
     * The order inside choose: the last classic choice is read before the modern id is
     * stored, or the widget would be pinned to the base.
     */
    @Test
    fun `a modern skin going on over a classic one pins a following widget to that classic skin`() {
        library.currentId = BundledSkins.LIGHT.id
        val modern = library.addModern(app, "Bento")

        library.choose(modern)

        assertEquals(BundledSkins.LIGHT.id, library.widgetSkin)
        assertTrue("the pin is marked as automatic", library.widgetSkinAutomatic)
    }

    @Test
    fun `a widget pinned automatically to the base stores the base id`() {
        library.choose(SkinEntry.BASE_ID)

        library.choose(library.addModern(app, "Bento"))

        assertEquals(SkinEntry.BASE_ID, library.widgetSkin)
    }

    @Test
    fun `a widget skin picked by hand is not replaced when a modern skin goes on`() {
        library.choose(BundledSkins.LIGHT.id)
        library.chooseWidgetSkin(BundledSkins.SPOT.id)

        library.choose(library.addModern(app, "Bento"))

        assertEquals(BundledSkins.SPOT.id, library.widgetSkin)
        assertFalse(library.widgetSkinAutomatic)
    }

    @Test
    fun `a modern skin after a modern skin does not pin the widget again`() {
        library.choose(BundledSkins.LIGHT.id)
        library.choose(library.addModern(app, "Bento", seed = 1))
        library.chooseWidgetSkin(null)

        library.choose(library.addModern(app, "Big Bento", seed = 2))

        assertNull(library.widgetSkin)
    }

    @Test
    fun `choosing the modern skin that is already chosen does not pin the widget again`() {
        library.choose(BundledSkins.LIGHT.id)
        val modern = library.addModern(app, "Bento")
        library.choose(modern)
        library.chooseWidgetSkin(null)

        library.choose(modern)

        assertNull(library.widgetSkin)
    }

    @Test
    fun `a classic skin chosen over a modern one lets go of the automatic widget pin`() {
        library.choose(BundledSkins.LIGHT.id)
        library.choose(library.addModern(app, "Bento"))

        library.choose(BundledSkins.SPOT.id)

        assertNull("the widget follows the player again", library.widgetSkin)
        assertFalse(library.widgetSkinAutomatic)
    }

    @Test
    fun `the base chosen over a modern skin lets go of the automatic widget pin`() {
        library.choose(BundledSkins.LIGHT.id)
        library.choose(library.addModern(app, "Bento"))

        library.choose(SkinEntry.BASE_ID)

        assertNull(library.widgetSkin)
        assertEquals(SkinEntry.BASE_ID, library.lastClassic)
    }

    @Test
    fun `a widget skin picked by hand stays when a classic skin goes on again`() {
        library.choose(BundledSkins.LIGHT.id)
        library.choose(library.addModern(app, "Bento"))
        library.chooseWidgetSkin(BundledSkins.SPOT.id)

        library.choose(BundledSkins.LIGHT.id)

        assertEquals(BundledSkins.SPOT.id, library.widgetSkin)
    }

    @Test
    fun `a source's skin going on or off touches neither the last classic choice nor the widget`() {
        library.choose(BundledSkins.LIGHT.id)
        library.choose(library.addModern(app, "Bento"))

        library.wear(BundledSkins.SPOT.id, SkinLibrary.choices)
        library.unwear(BundledSkins.SPOT.id)

        assertEquals(BundledSkins.LIGHT.id, library.lastClassic)
        assertEquals(BundledSkins.LIGHT.id, library.widgetSkin)
        assertTrue(library.widgetSkinAutomatic)
    }

    @Test
    fun `dropping a modern choice puts the last classic choice back without counting a choice`() {
        library.choose(BundledSkins.LIGHT.id)
        library.choose(library.addModern(app, "Bento"))
        library.wear(BundledSkins.SPOT.id, SkinLibrary.choices)
        val choices = SkinLibrary.choices

        assertTrue(library.dropModern())

        assertEquals(BundledSkins.LIGHT.id, library.currentId)
        assertNull("the automatic pin goes", library.widgetSkin)
        assertEquals("a source's skin worn over it stays on", BundledSkins.SPOT.id, library.wearing)
        assertEquals(choices, SkinLibrary.choices)
    }

    @Test
    fun `dropping a classic choice changes nothing`() {
        library.choose(BundledSkins.LIGHT.id)

        assertFalse(library.dropModern())

        assertEquals(BundledSkins.LIGHT.id, library.currentId)
    }

    @Test
    fun `deleting the worn modern skin puts the last classic choice on and lets go of the pin`() {
        library.choose(BundledSkins.LIGHT.id)
        val modern = library.addModern(app, "Bento")
        library.choose(modern)

        library.delete(modern)

        assertEquals(BundledSkins.LIGHT.id, library.currentId)
        assertNull(library.widgetSkin)
    }

    @Test
    fun `deleting the worn modern skin with no classic choice kept puts the base on`() {
        val modern = library.addModern(app, "Bento")
        library.currentId = modern

        library.delete(modern)

        assertEquals(SkinEntry.BASE_ID, library.currentId)
    }

    @Test
    fun `deleting the last classic choice makes the base the last classic choice`() {
        val mine = library.save(bytes(3), "Mine.wsz").id
        library.choose(mine)
        library.choose(library.addModern(app, "Bento"))

        library.delete(mine)

        assertEquals(SkinEntry.BASE_ID, skinsPrefs.getString("classic", null))
        assertEquals(SkinEntry.BASE_ID, library.lastClassic)
    }

    @Test
    fun `deleting the widget's skin has the widget follow the player`() {
        val mine = library.save(bytes(3), "Mine.wsz").id
        library.chooseWidgetSkin(mine)

        library.delete(mine)

        assertNull(library.widgetSkin)
    }

    @Test
    fun `the widget's skin and the last classic choice survive a restart`() {
        library.choose(BundledSkins.LIGHT.id)
        library.choose(library.addModern(app, "Bento"))

        val reopened = SkinLibrary(app)

        assertEquals(BundledSkins.LIGHT.id, reopened.lastClassic)
        assertEquals(BundledSkins.LIGHT.id, reopened.widgetSkin)
        assertTrue(reopened.widgetSkinAutomatic)
    }

    // modern skins stored as classic files before the library told them apart

    /** Stores [bytes] the way a classic pick did, as `<id>.wsz`, and returns the id. */
    private fun plantClassic(
        bytes: ByteArray,
        name: String,
    ): String = library.save(bytes, name).id

    @Test
    fun `a stored classic file holding a skin xml becomes a modern skin under the same id`() {
        val junk = plantClassic(zipOf("skin.xml", "xml/player.xml"), "Bento.wal")
        val classic = plantClassic(zipOf("main.bmp", "pledit.txt"), "Classic.wsz")
        val notAZip = plantClassic(bytes(4), "Broken.wsz")

        assertTrue(library.migrateModern())

        assertEquals(SkinKind.MODERN, library.kindOf(junk))
        assertFalse(java.io.File(app.filesDir, "skins/$junk.wsz").exists())
        assertEquals("the name drops .wal", "Bento", library.nameOf(junk))
        assertEquals(SkinKind.CLASSIC, library.kindOf(classic))
        assertEquals(SkinKind.CLASSIC, library.kindOf(notAZip))
        assertEquals("Classic.wsz", library.nameOf(classic))
    }

    @Test
    fun `a skin xml in a folder of the zip counts too`() {
        val junk = plantClassic(zipOf("Bento/skin.xml", "Bento/xml/player.xml"), "Bento.wal")

        library.migrateModern()

        assertEquals(SkinKind.MODERN, library.kindOf(junk))
    }

    @Test
    fun `the move to the modern extension runs once`() {
        library.migrateModern()
        val later = plantClassic(zipOf("skin.xml"), "Later.wal")

        assertFalse(library.migrateModern())

        assertEquals(SkinKind.CLASSIC, library.kindOf(later))
    }

    @Test
    fun `a renamed skin keeps the choice that names it`() {
        val junk = plantClassic(zipOf("skin.xml"), "Bento.wal")
        library.currentId = junk

        library.migrateModern()

        assertEquals(junk, library.currentId)
        assertEquals(SkinEntry.BASE_ID, library.lastClassic)
    }

    @Test
    fun `a renamed skin stops being a source's skin, the last classic choice and the widget's skin`() {
        val junk = plantClassic(zipOf("skin.xml"), "Bento.wal")
        library.choose(junk)
        library.chooseWidgetSkin(junk)
        library.wear(junk, SkinLibrary.choices)
        // worn over another choice, so the source's record is written
        library.currentId = BundledSkins.LIGHT.id
        library.wear(junk, SkinLibrary.choices)

        library.migrateModern()

        assertNull(library.wearing)
        assertNull(skinsPrefs.getString("classic", null))
        assertNull(library.widgetSkin)
        assertFalse(library.widgetSkinAutomatic)
    }

    @Test
    fun `records that name other skins are left alone by the move`() {
        plantClassic(zipOf("skin.xml"), "Bento.wal")
        library.choose(BundledSkins.LIGHT.id)
        library.chooseWidgetSkin(BundledSkins.SPOT.id)
        library.wear(BundledSkins.SPOT.id, SkinLibrary.choices)

        library.migrateModern()

        assertEquals(BundledSkins.SPOT.id, library.wearing)
        assertEquals(BundledSkins.LIGHT.id, skinsPrefs.getString("classic", null))
        assertEquals(BundledSkins.SPOT.id, library.widgetSkin)
    }
}
