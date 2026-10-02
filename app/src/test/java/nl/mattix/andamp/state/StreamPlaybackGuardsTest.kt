// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A live stream is a track whose duration is zero: a radio station, or an entry of an imported
 * M3U with EXTINF:-1. The position bar and the dead-file check have to cope with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamPlaybackGuardsTest {
    private val stream = Track("station:1", "", "Live stream", 0, uri = "http://example.com/stream")

    @Test
    fun `position fraction is zero when the duration is unknown`() {
        val s = WinampState()
        s.playlist = listOf(stream)
        s.currentIndex = 0

        // 0 * 1000f / 0 is NaN, NaN through coerceIn stays NaN, and the posbar's
        // roundToInt throws on it
        assertEquals(0f, s.positionFraction)

        s.currentTimeSec = 42
        assertEquals(0f, s.positionFraction)
        assertTrue(!s.positionFraction.isNaN())
    }

    @Test
    fun `http tracks are never declared dead`() {
        // MediaFiles.isAlive probes asset, content:// and file:// uris; any other
        // scheme counts as alive
        val app = ApplicationProvider.getApplicationContext<Application>()
        val files = MediaFiles(app)
        assertTrue(files.isAlive(stream))
        assertTrue(files.isAlive(stream.copy(uri = "https://example.com/stream")))
    }
}
