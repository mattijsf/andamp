// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.BrowseSourceContractTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The phone's own library, against the shared browse contract.
 *
 * The source runs against a content provider backed by SQLite ([FakeMediaStore]),
 * so its selections and sort orders are applied to the rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaStoreBrowseSourceTest : BrowseSourceContractTest() {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: FakeMediaStore

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

    override fun createSource(shelf: Shelf): BrowseSource {
        provider.seed(shelf)
        return MediaStoreBrowseSource(app)
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
     * An artist id that is not a row number did not come from this source. It
     * has no albums here.
     */
    @Test
    fun `an artist id this source never handed out has no albums`() =
        runTest {
            val source = createSource(shelf())

            assertEquals(emptyList<LibraryAlbum>(), source.albums("remote:artist:artist-1"))
        }
}
