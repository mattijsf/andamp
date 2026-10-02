// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * A listener's search text as an SQL LIKE pattern that matches literally: "100%" finds the
 * track called 100%. The query that uses the pattern must declare `ESCAPE '\'`.
 */
object LikeEscape {
    /** `%text%` with LIKE's specials escaped, for a contains match. */
    fun contains(raw: String): String = "%${escape(raw)}%"

    private fun escape(raw: String): String =
        buildString(raw.length) {
            for (c in raw) {
                if (c == '\\' || c == '%' || c == '_') append('\\')
                append(c)
            }
        }
}
