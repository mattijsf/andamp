// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** What [PlaylistStore.initial] restores for a track packaged with the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistFoldTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val local = listOf(Track("f1", "Somebody", "A File", 1_000, uri = "content://x/1"))

    private fun store() = PlaylistStore(app)

    @Before
    fun clean() {
        File(app.filesDir, PlaylistStore.FILE_NAME).delete()
    }

    @Test
    fun `a stored row for a track packaged with the app takes the name this build gives it`() {
        val packaged = Track("llama", "", "New Name", 5_000, uri = "asset:///audio/intro.mp3")
        store().save(listOf(packaged.copy(title = "Old Name")) + local, currentIndex = 1)

        val restored = store().initial(fallback = listOf(packaged))

        assertEquals(listOf("New Name", "A File"), restored.tracks.map { it.title })
        assertEquals(1, restored.currentIndex)
    }
}
