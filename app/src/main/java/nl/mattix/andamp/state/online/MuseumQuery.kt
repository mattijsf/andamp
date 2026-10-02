// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

/**
 * What the museum's search will accept.
 *
 * `search_classic_skins` answers `"Unexpected error."` for a query holding any of
 * `. - : ! ? & ( ) '`. Spaces, `_`, `+`, `*`, digits and letters of any alphabet are
 * accepted. `search_skins`, the endpoint's other search field, fails on the same
 * characters. The museum's website searches a different index and does not have this limit.
 *
 * Punctuation is therefore turned into spaces before asking: `zelda.wsz` becomes
 * `zelda wsz`. It is a whitelist, because only some punctuation was tested and an unknown
 * mark may also be an operator.
 */
object MuseumQuery {
    /** [raw] as the museum can take it, or empty when nothing usable is left. */
    fun sanitise(raw: String): String =
        raw
            .map { if (it.isLetterOrDigit() || it in KEPT) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** Punctuation the endpoint accepts. */
    private val KEPT = setOf('_', '+', '*')
}
