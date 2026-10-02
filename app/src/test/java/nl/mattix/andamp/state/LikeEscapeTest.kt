// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Test

/** LIKE's wildcards in typed text are escaped, so each matches only itself. */
class LikeEscapeTest {
    @Test
    fun `plain text is wrapped for a contains match`() {
        assertEquals("%nirvana%", LikeEscape.contains("nirvana"))
    }

    @Test
    fun `percent is escaped`() {
        assertEquals("%100\\%%", LikeEscape.contains("100%"))
    }

    @Test
    fun `underscore is escaped`() {
        assertEquals("%a\\_b%", LikeEscape.contains("a_b"))
    }

    @Test
    fun `the escape character escapes itself`() {
        assertEquals("%a\\\\b%", LikeEscape.contains("a\\b"))
    }

    @Test
    fun `empty text gives the pattern that matches anything`() {
        assertEquals("%%", LikeEscape.contains(""))
    }
}
