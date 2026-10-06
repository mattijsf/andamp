// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [MediaStoreAudio.findAt]: the library entry for the file a storage document stands for,
 * asked of a library backed by SQLite ([FakeMediaStore]) so the selection is applied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaStoreAudioFindAtTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val storage = "content://com.android.externalstorage.documents"
    private val onCard = "$storage/tree/1A2B-3C4D%3AMusic/document/1A2B-3C4D%3AMusic%2FRoad%2Fone.mp3"
    private lateinit var library: FakeMediaStore

    @Before
    fun register() {
        shadowOf(app).grantPermissions(MediaStoreAudio.permission())
        library =
            org.robolectric.Robolectric
                .buildContentProvider(FakeMediaStore::class.java)
                .create(FakeMediaStore.AUTHORITY)
                .get()
    }

    @Test
    fun `the file at the row's place is found, not another of the same name`() {
        library.file(7, "/storage/1A2B-3C4D/Music/Gym/one.mp3")
        library.file(8, "/storage/1A2B-3C4D/Music/Road/one.mp3")

        assertEquals("content://media/external/audio/media/8", MediaStoreAudio(app).findAt(onCard)?.toString())
    }

    @Test
    fun `a row on the phone's own storage is found under where that is mounted`() {
        val phone = Environment.getExternalStorageDirectory().path
        library.file(9, "$phone/Music/two.mp3")

        assertEquals(
            "content://media/external/audio/media/9",
            MediaStoreAudio(app).findAt("$storage/tree/primary%3AMusic/document/primary%3AMusic%2Ftwo.mp3")?.toString(),
        )
    }

    @Test
    fun `a file the library does not list is not found`() {
        library.file(7, "/storage/1A2B-3C4D/Music/Gym/one.mp3")

        assertNull(MediaStoreAudio(app).findAt(onCard))
    }

    @Test
    fun `without the audio permission nothing is looked up`() {
        library.file(8, "/storage/1A2B-3C4D/Music/Road/one.mp3")
        shadowOf(app).denyPermissions(MediaStoreAudio.permission())

        assertNull(MediaStoreAudio(app).findAt(onCard))
    }

    @Test
    fun `a document of another provider has no place to look in`() {
        assertNull(MediaStoreAudio(app).findAt("content://com.android.providers.media.documents/document/audio%3A42"))
    }
}
