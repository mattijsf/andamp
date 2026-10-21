// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

import nl.mattix.andamp.core.model.LibraryGroup
import nl.mattix.andamp.core.model.Track

/**
 * A library that also files its tracks by folder and by genre, for the library window's
 * FOLDERS and GENRES.
 *
 * It is optional, beside [BrowseSource]. A source app says in its manifest which of the
 * two it gives (`PackApi.META_FOLDERS`, `PackApi.META_GENRES`), and the player asks
 * nothing of a source that does not say so. A source that gives only one keeps the defaults
 * for the other, which answer empty.
 *
 * A folder is a place that holds tracks itself, named by its path from the top of the
 * library; a folder that holds only other folders is not one. A genre is a name the tracks
 * are tagged with. Both are [LibraryGroup]s, with the source's id for each, which their
 * tracks are asked for by.
 *
 * As in [BrowseSource], an empty list is an answer and [SourceUnreachable] is the lack of
 * one.
 */
interface BrowseGroups {
    /** The folders that hold tracks. */
    suspend fun folders(): List<LibraryGroup> = emptyList()

    /** The tracks in the folder [id], not those in folders below it, in the order the source keeps them. */
    suspend fun folderTracks(id: String): List<Track> = emptyList()

    /** The genres the tracks are tagged with. */
    suspend fun genres(): List<LibraryGroup> = emptyList()

    /** The tracks tagged with the genre [id]. */
    suspend fun genreTracks(id: String): List<Track> = emptyList()
}
