// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.model.LibraryGroup
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseGroups
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.state.LibraryIndex.Piece
import kotlin.coroutines.CoroutineContext

/** Which of FOLDERS and GENRES a source gives, as its app says in its manifest; see [PackSource.groupsOffered]. */
data class GroupsOffered(
    val folders: Boolean,
    val genres: Boolean,
) {
    val any: Boolean get() = folders || genres

    companion object {
        val NONE = GroupsOffered(folders = false, genres = false)
    }
}

/**
 * A source behind a server that also gives its folders and genres ([FoldersAndGenres]), kept in
 * the library's index as [CachedSourceLibrary] keeps the rest.
 *
 * The lists of folders and of genres are two more pieces ([Piece.FOLDERS],
 * [Piece.GENRES]): answered from the index once stored, and asked again behind that answer as
 * the other pieces are. A server lists its folders by walking every track it has, so the LISTS
 * tab does not wait for that: before the first list is stored, it is asked behind an empty
 * answer and shown when it arrives ([revision]).
 *
 * The window asks for a folder or a genre by the name it shows; the index keeps which of the
 * source's ids go by that name, and the tracks are asked of the source by those ids each time.
 * A view the source does not give ([offered]) is answered empty without asking.
 */
class GroupedSourceLibrary(
    key: String,
    upstream: BrowseSource,
    private val groups: BrowseGroups,
    private val offered: GroupsOffered,
    index: LibraryIndex,
    scope: CoroutineScope,
    io: CoroutineContext = Dispatchers.IO,
    clock: () -> Long = System::currentTimeMillis,
    wholeOf: (BrowseSource) -> Boolean = { true },
) : CachedSourceLibrary(key, upstream, index, scope, io, clock, wholeOf),
    FoldersAndGenres {
    override suspend fun folders(): List<Pair<String, Int?>> =
        if (offered.folders) listed(Piece.FOLDERS) { groups.folders() } else emptyList()

    override suspend fun folderTracks(folder: String): List<Track> =
        if (offered.folders) tracksOf(Piece.FOLDERS, folder, groups::folderTracks) else emptyList()

    override suspend fun genres(): List<Pair<String, Int?>> =
        if (offered.genres) listed(Piece.GENRES) { groups.genres() } else emptyList()

    override suspend fun genreTracks(genre: String): List<Track> =
        if (offered.genres) tracksOf(Piece.GENRES, genre, groups::genreTracks) else emptyList()

    /** The folders or genres, one row per name with the counts of the groups so named added up. */
    private suspend fun listed(
        piece: Piece,
        ask: suspend () -> List<LibraryGroup>,
    ): List<Pair<String, Int?>> =
        cached(
            piece,
            read = { index.storedGroups(key, piece) },
            ask = ask,
            store = { found, at -> index.storeGroups(key, piece, found, at) },
            first = First.BEHIND,
        ).groupBy { it.name }
            .map { (name, same) -> name to same.mapNotNull { it.trackCount }.takeIf { it.isNotEmpty() }?.sum() }

    /** The tracks of every group called [name], asked of the source by their ids. */
    private suspend fun tracksOf(
        piece: Piece,
        name: String,
        ask: suspend (String) -> List<Track>,
    ): List<Track> = withContext(io) { index.groupIds(key, piece, name) }.flatMap { ask(it) }
}
