// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * How far a source is from being able to play. A source known only from a playlist row
 * ([ABSENT]) is kept apart from one whose app is installed but signed out ([SIGNED_OUT]),
 * because a row is labeled "Missing source" or "Signed out" accordingly.
 */
enum class SourceStanding {
    /** Nothing on this phone can play its rows: no app for it, or one built for another contract version. */
    ABSENT,

    /** Its app is here, and nobody has signed in to it. */
    SIGNED_OUT,

    /** It can play and it can be browsed. */
    READY,
}
