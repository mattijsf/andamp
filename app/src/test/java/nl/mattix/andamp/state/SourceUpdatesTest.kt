// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A source's `update.json`: read, compared, and turned into what the page says.
 *
 * Under Robolectric only because `org.json` is the platform's parser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceUpdatesTest {
    @Test
    fun `the file names a version and a download page`() {
        val latest = SourceUpdates.parse("""{"version": "0.5.0", "page": "https://example.org/source/download"}""")

        assertEquals(SourceUpdates.Latest("0.5.0", "https://example.org/source/download"), latest)
    }

    @Test
    fun `a file without a page, or with one that is not https, is no answer`() {
        assertNull(SourceUpdates.parse("""{"version": "0.5.0"}"""))
        assertNull(SourceUpdates.parse("""{"version": "0.5.0", "page": "http://example.org/download"}"""))
        assertNull(SourceUpdates.parse("""{"page": "https://example.org/download"}"""))
    }

    @Test
    fun `versions compare number by number, and a tag after the dash is not a version`() {
        assertTrue(SourceUpdates.isNewer("0.10.0", "0.9.3"))
        assertTrue(SourceUpdates.isNewer("0.5.0", "0.4.0-stream"))
        assertTrue(SourceUpdates.isNewer("1.0", "0.9.9"))
        assertFalse(SourceUpdates.isNewer("0.4.0", "0.4.0-stream"))
        assertFalse(SourceUpdates.isNewer("0.4.0", "0.4.1"))
        assertFalse(SourceUpdates.isNewer("v0.4.0", "0.4.0"))
    }

    @Test
    fun `the page says up to date, newer, or that it could not tell`() {
        val newer = SourceUpdates.Latest("0.5.0", "https://example.org/download")

        assertEquals(SourceUpdates.Check.Available(newer), SourceUpdates.answer(newer, "0.4.0-stream"))
        assertEquals(SourceUpdates.Check.UpToDate("0.5.0"), SourceUpdates.answer(newer, "0.5.0"))
        assertEquals(SourceUpdates.Check.Failed, SourceUpdates.answer(null, "0.4.0"))
    }
}
