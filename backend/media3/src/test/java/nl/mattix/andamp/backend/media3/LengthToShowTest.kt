// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What counts as a track's length.
 *
 * A live HLS stream reports the length of a sliding window, and taking it for
 * a duration would make a station seekable. Icecast reports no length.
 */
class LengthToShowTest {
    @Test
    fun `a live stream has no length, however long its window is`() {
        assertNull(lengthToShow(live = true, reported = 57_000))
    }

    @Test
    fun `a measured track keeps its length`() {
        assertEquals(315_000L, lengthToShow(live = false, reported = 315_000))
    }

    @Test
    fun `a length the player does not know yet is not a length`() {
        assertNull(lengthToShow(live = false, reported = C.TIME_UNSET))
    }

    @Test
    fun `zero is not a length either, and neither is less`() {
        assertNull(lengthToShow(live = false, reported = 0))
        assertNull(lengthToShow(live = false, reported = -1))
    }

    @Test
    fun `an entry marked as a stream counts as live and has no length`() {
        // the caller passes live = true for an entry marked as a stream, even
        // when the player does not report the item as live
        assertNull(lengthToShow(live = true, reported = 57_000))
    }
}
