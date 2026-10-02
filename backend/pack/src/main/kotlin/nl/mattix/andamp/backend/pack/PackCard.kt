// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

/**
 * What a player needs to name a source: its label and the scheme its rows start with.
 *
 * Both come from the pack's descriptor. [PackClient] stores them between launches, so that a
 * row can be drawn before the pack's process has come up.
 *
 * The scheme is also an id: a playlist records which source each row came from by it, so a
 * pack must keep its scheme across versions.
 */
data class PackCard(
    /** What its rows start with, with no colon: `example`, for rows reading `example:track:…`. */
    val scheme: String,
    /** What the listener sees: the row in Preferences and the entry in Media Library. */
    val label: String,
) {
    /**
     * The scheme as an id: trimmed, without a trailing colon, in upper case. A pack may write
     * `example` or `example:` and gets the same id.
     */
    val id: String get() = scheme.trim().trimEnd(':').uppercase()
}
