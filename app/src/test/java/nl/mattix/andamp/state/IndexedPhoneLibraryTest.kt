// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.BrowseSourceContractTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The phone's own library, from the library's index, against the shared browse contract.
 *
 * The phone's media index is a content provider backed by SQLite ([FakeMediaStore]), so the
 * one read of every file is answered as the platform would answer it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IndexedPhoneLibraryTest : BrowseSourceContractTest() {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: FakeMediaStore

    /** The phone's media index as the library reads it, counting the full reads. */
    private inner class CountingReader : PhoneShelfReader(app.contentResolver) {
        var reads = 0

        override fun read(): List<PhoneFile> {
            reads++
            return super.read()
        }
    }

    private val reader by lazy { CountingReader() }

    /** The clock the library checks the phone by; moved by hand. */
    private var now = 1_000_000L

    @Before
    fun grantAndRegister() {
        shadowOf(app).grantPermissions(permission())
        provider =
            org.robolectric.Robolectric
                .buildContentProvider(FakeMediaStore::class.java)
                .create(FakeMediaStore.AUTHORITY)
                .get()
    }

    private fun permission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun library() =
        IndexedPhoneLibrary(
            canRead = { true },
            reader = reader,
            index = LibraryIndex(app, null),
            words = EnglishWords,
            io = Dispatchers.Unconfined,
            clock = { now },
        )

    override fun createSource(shelf: Shelf): BrowseSource {
        provider.seed(shelf)
        return library()
    }

    /**
     * Access revoked between the permission check and the query makes the
     * provider throw a SecurityException. Each read then returns an empty list.
     */
    @Test
    fun `access taken away mid-read reads as nothing rather than a crash`() =
        runTest {
            val source = createSource(shelf())
            provider.refuse = true

            assertEquals(emptyList<Any>(), source.artists())
            assertEquals(emptyList<Any>(), source.albums(null))
            assertEquals(emptyList<Any>(), source.tracks("1"))
            assertEquals(emptyList<Any>(), source.search("wow", 10))
        }

    /**
     * MediaStore identifies an album by its tags and the folder its files sit
     * in, so one album split across two folders arrives as two rows. This case
     * is outside the shared contract because it is specific to MediaStore.
     */
    @Test
    fun `an album split across two folders is one album`() =
        runTest {
            val source = createSource(shelf())
            // the same album, filed again under a second id, as MediaStore would
            provider.splitOff("The Wow! Signal", "Space Debris")

            val wow = source.albums(null).filter { it.title == "The Wow! Signal" }

            assertEquals("the split album is listed once", 1, wow.size)
            assertEquals(4, source.tracks(wow.single().id).size)
        }

    /** The track from the other folder sorts by its track number with the rest of the album. */
    @Test
    fun `the strays from another folder sort into the album`() =
        runTest {
            val source = createSource(shelf())
            provider.splitOff("The Wow! Signal", "Space Debris")

            val album = source.albums(null).first { it.title == "The Wow! Signal" }
            val titles = source.tracks(album.id).map { it.title }

            assertEquals(listOf("The Dark Forest", "Cryogen", "Hexagons", "Space Debris"), titles)
        }

    /** A file with no track number sorts first. */
    @Test
    fun `a track with no number still has a place`() =
        runTest {
            val source = createSource(shelf())
            provider.unnumber("Cryogen")

            val album = source.albums(null).first { it.title == "The Wow! Signal" }
            val titles = source.tracks(album.id).map { it.title }

            assertEquals("Cryogen", titles.first())
        }

    /**
     * An artist id that is not one the library made did not come from this source. It
     * has no albums here.
     */
    @Test
    fun `an artist id this source never handed out has no albums`() =
        runTest {
            val source = createSource(shelf())

            assertEquals(emptyList<LibraryAlbum>(), source.albums("remote:artist:artist-1"))
        }

    @Test
    fun `an album tagged with an album artist is filed under that artist`() =
        runTest {
            val source = createSource(shelf())
            provider.tagAlbumArtist("Dummy", "Portishead & Friends")

            val names = source.artists().map { it.name }

            assertTrue(names.contains("Portishead & Friends"))
            assertFalse("the track artist has no album of their own left", names.contains("Portishead"))
            assertEquals("Portishead & Friends", source.albums(null).first { it.title == "Dummy" }.artist)
        }

    @Test
    fun `an untagged album of many artists in one folder is filed under Various Artists`() =
        runTest {
            val source = createSource(shelf())
            provider.add("Teardrop", "Massive Attack", "Late Night Tales", albumId = 900)
            provider.add("Unfinished Sympathy", "Massive Attack", "Late Night Tales", albumId = 900)
            provider.add("Glory Box", "Portishead", "Late Night Tales", albumId = 900)

            val various = source.artists().single { it.name == "Various Artists" }
            val names = source.artists().map { it.name }

            assertFalse("a compilation does not make an artist of each of its artists", names.contains("Massive Attack"))
            assertEquals(listOf("Late Night Tales"), source.albums(various.id).map { it.title })
            assertEquals("Various Artists", source.albums(null).first { it.title == "Late Night Tales" }.artist)
            assertEquals("Various Artists sorts after the named artists", "Various Artists", names.last())
        }

    @Test
    fun `the phone is read again once it says its music changed, and not before`() =
        runTest {
            val source = createSource(shelf()) as IndexedPhoneLibrary
            assertEquals(2, source.artists().size)
            provider.add("Teardrop", "Massive Attack", "Mezzanine", albumId = 901)

            assertEquals("nothing said it changed, and it was checked a moment ago", 2, source.artists().size)
            source.markDirty()
            assertEquals(3, source.artists().size)
            assertEquals(2, source.revision.value)
        }

    @Test
    fun `the phone is checked again after a while even when nothing said so`() =
        runTest {
            val source = createSource(shelf()) as IndexedPhoneLibrary
            source.artists()
            provider.add("Teardrop", "Massive Attack", "Mezzanine", albumId = 901)

            now += IndexedPhoneLibrary.CHECK_EVERY_MS

            assertEquals(3, source.artists().size)
        }

    @Test
    fun `music that did not change is not read again`() =
        runTest {
            val source = createSource(shelf()) as IndexedPhoneLibrary
            source.artists()

            source.markDirty()
            source.artists()
            now += IndexedPhoneLibrary.CHECK_EVERY_MS
            source.albums(null)

            assertEquals(1, reader.reads)
            assertEquals(1, source.revision.value)
        }

    @Test
    fun `a search finds artists, albums and tracks, each kind apart`() =
        runTest {
            val source = createSource(shelf()) as IndexedPhoneLibrary

            assertEquals(listOf("Portishead"), source.searchSections("portis").artists.map { it.name })
            assertEquals(listOf("Dummy"), source.searchSections("dumm").albums.map { it.title })
            assertEquals(listOf("Sour Times"), source.searchSections("sour").tracks.map { it.title })
            // a track is found by its album and its artist too
            assertTrue(
                source
                    .searchSections("dummy")
                    .tracks
                    .map { it.title }
                    .containsAll(listOf("Mysterons", "Sour Times")),
            )
        }

    @Test
    fun `a search ignores case and accents`() =
        runTest {
            val source = createSource(shelf()) as IndexedPhoneLibrary
            provider.add("Hoppípolla", "Sigur Rós", "Takk...", albumId = 902)

            assertEquals(listOf("Sigur Rós"), source.searchSections("SIGUR ROS").artists.map { it.name })
            assertEquals(listOf("Hoppípolla"), source.searchSections("hoppipolla").tracks.map { it.title })
        }

    @Test
    fun `the counts are of the artists and albums the window lists`() =
        runTest {
            val source = createSource(shelf()) as IndexedPhoneLibrary

            assertEquals(Triple(2, 3, 7), source.counts())
        }
}
