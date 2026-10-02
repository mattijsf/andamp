// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

/**
 * Something the backend needs the listener to know.
 *
 * A notice is a state: it says what is true now, and it stays until the backend clears it.
 * A backend raises one with [BackendState.raising], which numbers it, so a second press that
 * meets the same failure is reported again.
 *
 * It is a type and carries no text; the player chooses the words.
 */
sealed interface BackendNotice {
    /**
     * Nothing plays, and the source itself is why.
     *
     * A single track that fails is skipped without a notice. This is raised after several
     * failures in a row, or at once when the source knows that trying again cannot help, such
     * as an account that is not allowed to stream.
     */
    data object SourceCannotPlay : BackendNotice

    /**
     * Play was pressed and no row in the queue has a source that can play it: the rows belong
     * to a source that is not installed or not signed in. Nothing was tried, which is the
     * difference from [SourceCannotPlay].
     */
    data object NothingPlayableHere : BackendNotice

    /**
     * A station that was playing lost its connection and did not come back.
     *
     * A dropped station is retried first; this is raised only when retrying has gone on for
     * minutes without sound.
     */
    data object StationLost : BackendNotice

    /**
     * The connection to a music server was lost while one of its songs was playing, and it
     * could not be reached again. Like [StationLost], it is raised only after minutes of
     * retrying.
     */
    data object ServerLost : BackendNotice
}
