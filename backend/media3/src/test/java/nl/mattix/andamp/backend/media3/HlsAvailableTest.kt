// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * That a `.m3u8` station can be opened.
 *
 * `DefaultMediaSourceFactory` finds the HLS factory by reflection, so there is
 * no call site to test: the dependency is the feature. This test does not see
 * what R8 strips from a release build.
 */
class HlsAvailableTest {
    @Test
    fun `the HLS media source is on the classpath`() {
        assertNotNull(Class.forName("androidx.media3.exoplayer.hls.HlsMediaSource\$Factory"))
    }
}
