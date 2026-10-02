// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.core.model.BackendNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording of each notice the listener is shown: it names a cause and
 * offers a remedy that works.
 */
class BackendNoticesTest {
    private val everyNotice =
        listOf(
            BackendNotice.SourceCannotPlay,
            BackendNotice.NothingPlayableHere,
            BackendNotice.StationLost,
            BackendNotice.ServerLost,
        )

    @Test
    fun `the notice says what happened and what to do about it`() {
        val body = promptFor(BackendNotice.SourceCannotPlay) {}.body

        assertTrue("the notice names a cause", body.contains("account") && body.contains("connection"))
        assertTrue("the notice offers a remedy", body.contains("play again"))
    }

    @Test
    fun `it names the source`() {
        val body = promptFor(BackendNotice.SourceCannotPlay) {}.body

        assertTrue(body.contains("source"))
    }

    /** It can come after a run of tracks, after one on a short queue, or at once. */
    @Test
    fun `it names no count of tracks`() {
        val body = promptFor(BackendNotice.SourceCannotPlay) {}.body

        assertFalse("the notice names no count: $body", COUNT.containsMatchIn(body))
    }

    /** The player outlives its windows, so closing and reopening the app is no remedy. */
    @Test
    fun `it does not suggest leaving the app`() {
        val body = promptFor(BackendNotice.SourceCannotPlay) {}.body

        assertFalse("the notice does not suggest leaving the app: $body", LEAVING.containsMatchIn(body))
    }

    @Test
    fun `nothing here to play says where the reason is`() {
        val body = promptFor(BackendNotice.NothingPlayableHere) {}.body

        assertTrue("the notice names a reason", body.contains("installed") && body.contains("signed in"))
        assertTrue("the notice points at the rows that say which", body.contains("row"))
    }

    @Test
    fun `a lost station says it was the station, and that play tries again`() {
        val body = promptFor(BackendNotice.StationLost) {}.body

        assertTrue("the notice names the station: $body", body.contains("station"))
        assertTrue("the notice offers a remedy: $body", body.contains("play"))
        assertFalse("the notice does not suggest leaving the app: $body", LEAVING.containsMatchIn(body))
    }

    /** It names the server, not a station or an account, and gives no count. */
    @Test
    fun `a lost server says it was the server, and that play tries again`() {
        val body = promptFor(BackendNotice.ServerLost) {}.body

        assertTrue("the notice names the server: $body", body.contains("server"))
        assertFalse("the notice does not name a station: $body", body.contains("station"))
        assertFalse("the notice does not name an account: $body", body.contains("account"))
        assertTrue("the notice offers a remedy: $body", body.contains("play"))
        assertFalse("the notice does not suggest leaving the app: $body", LEAVING.containsMatchIn(body))
        assertFalse("the notice names no count: $body", COUNT.containsMatchIn(body))
    }

    @Test
    fun `a notice has one button, and it closes it`() {
        everyNotice.forEach { notice ->
            var closed = false
            val prompt = promptFor(notice) { closed = true }

            assertEquals("OK", prompt.confirmLabel)
            assertNull("$notice offers nothing to decline", prompt.dismissLabel)
            prompt.onConfirm()
            assertTrue("the only button closes $notice", closed)
        }
    }

    private companion object {
        val COUNT = Regex("""\d|\b(two|three|four|five|six|seven|eight|nine|ten)\b""", RegexOption.IGNORE_CASE)
        val LEAVING = Regex("clos|quit|restart|reopen|opening", RegexOption.IGNORE_CASE)
    }
}
