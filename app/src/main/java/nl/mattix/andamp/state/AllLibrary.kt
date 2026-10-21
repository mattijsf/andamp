// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryPlaylist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.SourceUnreachable
import java.util.concurrent.ConcurrentHashMap

/** A library that can tell which sources have an album, by the mark each source's rows carry. */
interface SourceMarks {
    /** The marks of the sources that have the album [albumId], in the order they are played from; empty when not known. */
    fun marksOf(albumId: String): List<SourceMark>

    /** The mark of the source the playlist [listId] belongs to; null when not known. */
    fun markOfList(listId: String): SourceMark?

    /** The mark of [source], when it is one of these; null otherwise. */
    fun markOfSource(source: MusicSource): SourceMark?

    /** The one source the folders and genres come from; null when none does, or several. */
    fun foldersAndGenresFrom(): MusicSource?
}

/**
 * Every source the listener can show, as one library: the phone and each source with an
 * account here, in that order ([members]).
 *
 * An artist is one row however many sources have them, matched by name without case, accents
 * or spacing. An album is one row per artist and title; the copies the sources have are its
 * editions, and its tracks come from the first edition that answers, so the phone's copy
 * plays before one from a server, and another source's copy plays when the first cannot be
 * reached. A source that cannot be reached leaves its rows out ([whole] is false); only when
 * none can is the question unanswered ([SourceUnreachable]).
 *
 * What belongs to one source is passed to it: its catalog, its own lists, and the albums and
 * tracks found there. Their ids carry the source they came from.
 */
class AllLibrary(
    private val members: () -> List<Member>,
) : BrowseSource,
    SectionedSearch,
    SourceMarks,
    PartialAnswers,
    TrackHomes,
    EveryTrack,
    FoldersAndGenres {
    /** One source in the library: its library and the mark its rows carry. */
    class Member(
        val source: MusicSource,
        val library: BrowseSource,
        val mark: SourceMark,
    )

    override val capabilities: BrowseCapabilities
        get() {
            val all = members().map { it.library.capabilities }
            return BrowseCapabilities(
                hasArtists = all.any { it.hasArtists },
                hasAlbums = all.any { it.hasAlbums },
                canSearch = all.any { it.canSearch },
                hasPlaylists = all.any { it.hasPlaylists },
                hasCatalogue = all.any { it.hasCatalogue },
            )
        }

    override val available: Boolean get() = members().any { it.library.available }

    @Volatile
    override var whole: Boolean = true
        private set

    /** One album's copies, the first played first. */
    private class Edition(
        val member: Member,
        val album: LibraryAlbum,
    )

    /** The editions behind each album row handed out, by its id. */
    private val editions = ConcurrentHashMap<String, List<Edition>>()

    override suspend fun artists(): List<LibraryArtist> {
        val answers = ask { it.library.artists() }
        return answers
            .flatMap { (_, artists) -> artists }
            .groupBy { ShelfBuilder.fold(it.name) }
            .map { (key, same) ->
                val counts = same.mapNotNull { it.albumCount }
                LibraryArtist(
                    id = ARTIST + key,
                    name = same.first().name,
                    albumCount = counts.sum().takeIf { counts.isNotEmpty() },
                )
            }.sortedWith(compareBy({ ShelfBuilder.fold(it.name).isEmpty() }, { ShelfBuilder.fold(it.name) }))
    }

    override suspend fun albums(artistId: String?): List<LibraryAlbum> {
        if (artistId == null) return merged(ask { member -> member.library.albums(null).map { Edition(member, it) } })
        routed(artistId)?.let { (member, id) -> return member.library.albums(id).map { routedAlbum(member, it) } }
        val key = artistId.removePrefix(ARTIST)
        val answers =
            ask { member ->
                member.library
                    .artists()
                    .filter { ShelfBuilder.fold(it.name) == key }
                    .flatMap { artist -> member.library.albums(artist.id) }
                    .map { Edition(member, it) }
            }
        return merged(answers)
    }

    override suspend fun tracks(albumId: String): List<Track> {
        routed(albumId)?.let { (member, id) -> return member.library.tracks(id) }
        // an album handed out before the editions were known is looked up once more
        val copies = editions[albumId] ?: albums(null).let { editions[albumId].orEmpty() }
        var unreachable: SourceUnreachable? = null
        for (copy in copies) {
            try {
                val tracks = copy.member.library.tracks(copy.album.id)
                if (tracks.isNotEmpty()) return tracks
            } catch (down: SourceUnreachable) {
                // the next copy, from another source, may play
                unreachable = down
            }
        }
        unreachable?.let { throw it }
        return emptyList()
    }

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<Track> = distinctTracks(ask { it.library.search(query, limit) }.flatMap { it.second }).take(limit)

    override suspend fun searchSections(
        query: String,
        limit: Int,
    ): SectionedSearch.Sections {
        val answers =
            ask { member ->
                val library = member.library
                if (library is SectionedSearch) {
                    library.searchSections(query, limit)
                } else {
                    SectionedSearch.Sections(emptyList(), emptyList(), library.search(query, limit))
                }
            }
        val artists =
            answers
                .flatMap { (_, found) -> found.artists }
                .groupBy { ShelfBuilder.fold(it.name) }
                .map { (key, same) ->
                    LibraryArtist(ARTIST + key, same.first().name, albumCount = same.mapNotNull { it.albumCount }.sum())
                }
        val albums = merged(answers.map { (member, found) -> member to found.albums.map { Edition(member, it) } })
        return SectionedSearch.Sections(artists, albums, distinctTracks(answers.flatMap { it.second.tracks }).take(limit))
    }

    override suspend fun playlists(): List<LibraryPlaylist> =
        ask { member ->
            if (!member.library.capabilities.hasPlaylists) {
                emptyList()
            } else {
                member.library.playlists().map { it.copy(id = route(member, it.id)) }
            }
        }.flatMap { it.second }

    override suspend fun playlistTracks(id: String): List<Track> {
        val (member, listId) = routed(id) ?: return emptyList()
        return member.library.playlistTracks(listId)
    }

    override suspend fun findArtists(
        query: String,
        limit: Int,
    ): List<LibraryArtist> =
        ask { member ->
            if (!member.library.capabilities.hasCatalogue) {
                emptyList()
            } else {
                member.library.findArtists(query, limit).map { it.copy(id = route(member, it.id)) }
            }
        }.flatMap { it.second }

    /** Every track of the members that can hand theirs over, the phone's first, each song once. */
    override suspend fun everyTrack(): List<Track> =
        distinctTracks(
            ask { member -> (member.library as? EveryTrack)?.everyTrack().orEmpty() }.flatMap { it.second },
        )

    override suspend fun folders(): List<Pair<String, Int?>> = grouped { it.folders() }

    override suspend fun folderTracks(folder: String): List<Track> =
        ask {
            (it.library as? FoldersAndGenres)?.folderTracks(folder).orEmpty()
        }.flatMap { it.second }

    override suspend fun genres(): List<Pair<String, Int?>> = grouped { it.genres() }

    override suspend fun genreTracks(genre: String): List<Track> =
        distinctTracks(ask { (it.library as? FoldersAndGenres)?.genreTracks(genre).orEmpty() }.flatMap { it.second })

    /**
     * What the members that know folders and genres list, one row per name with the counts
     * added up; a count no member knows stays unknown.
     */
    private suspend fun grouped(list: suspend (FoldersAndGenres) -> List<Pair<String, Int?>>): List<Pair<String, Int?>> =
        ask { (it.library as? FoldersAndGenres)?.let { library -> list(library) }.orEmpty() }
            .flatMap { it.second }
            .groupBy { ShelfBuilder.fold(it.first) }
            .map { (_, same) -> same.first().first to same.mapNotNull { it.second }.takeIf { it.isNotEmpty() }?.sum() }
            .sortedBy { ShelfBuilder.fold(it.first) }

    /** The track's album as the source that plays it knows it, as this library's row for it. */
    override suspend fun albumOf(track: Track): LibraryAlbum? {
        val member = homeOf(track) ?: return null
        val album = (member.library as TrackHomes).albumOf(track) ?: return null
        val id = ALBUM + ShelfBuilder.fold(album.artist) + SEPARATOR + ShelfBuilder.fold(album.title)
        editions.putIfAbsent(id, listOf(Edition(member, album)))
        return album.copy(id = id)
    }

    override suspend fun artistOf(track: Track): LibraryArtist? {
        val member = homeOf(track) ?: return null
        val artist = (member.library as TrackHomes).artistOf(track) ?: return null
        return artist.copy(id = ARTIST + ShelfBuilder.fold(artist.name))
    }

    /** The member that plays [track] and can follow it home; null when none can. */
    private fun homeOf(track: Track): Member? {
        val source = SourceForRows.of(track)
        return members().firstOrNull { it.source == source && it.library is TrackHomes }
    }

    override fun marksOf(albumId: String): List<SourceMark> = editions[albumId].orEmpty().map { it.member.mark }.distinct()

    override fun markOfList(listId: String): SourceMark? = routed(listId)?.first?.mark

    override fun markOfSource(source: MusicSource): SourceMark? = members().firstOrNull { it.source.id == source.id }?.mark

    override fun foldersAndGenresFrom(): MusicSource? = members().filter { it.library is FoldersAndGenres }.singleOrNull()?.source

    /**
     * [question] asked of every member at once, with the answers of those that gave one, in
     * member order. A member that cannot be reached is left out, and [whole] says so; when no
     * member can be reached, the question throws as a single source's would.
     */
    private suspend fun <T> ask(question: suspend (Member) -> T): List<Pair<Member, T>> {
        val everyone = members().filter { it.library.available }
        val answers =
            coroutineScope {
                everyone
                    .map { member ->
                        async {
                            try {
                                member to question(member)
                            } catch (_: SourceUnreachable) {
                                null
                            }
                        }
                    }.awaitAll()
            }
        val answered = answers.filterNotNull()
        whole =
            answered.size == everyone.size && answered.all { (member, _) -> (member.library as? PartialAnswers)?.whole ?: true }
        if (answered.isEmpty() && everyone.isNotEmpty()) throw SourceUnreachable("no source could be reached")
        return answered
    }

    /**
     * Albums as one row per album artist and title, the editions in member order, and each
     * row's editions kept for [tracks] and [marksOf].
     */
    private fun merged(answers: List<Pair<Member, List<Edition>>>): List<LibraryAlbum> =
        answers
            .flatMap { it.second }
            .groupBy { ALBUM + ShelfBuilder.fold(it.album.artist) + SEPARATOR + ShelfBuilder.fold(it.album.title) }
            .map { (id, copies) ->
                editions[id] = copies
                val first = copies.first().album
                first.copy(id = id, year = copies.mapNotNull { it.album.year }.minOrNull())
            }.sortedBy { ShelfBuilder.fold(it.title) }

    /** An album from one source's catalog, with an id that leads back to that source. */
    private fun routedAlbum(
        member: Member,
        album: LibraryAlbum,
    ) = album.copy(id = route(member, album.id))

    /** Tracks with the same title and artist, from two sources, once: the first member's copy. */
    private fun distinctTracks(tracks: List<Track>): List<Track> =
        tracks.distinctBy { ShelfBuilder.fold(it.title) + SEPARATOR + ShelfBuilder.fold(it.artist) }

    private fun route(
        member: Member,
        id: String,
    ) = "$VIA${member.source.id}$SEPARATOR$id"

    /** The member and its own id behind a routed id; null for an id that is not routed. */
    private fun routed(id: String): Pair<Member, String>? {
        if (!id.startsWith(VIA)) return null
        val sourceId = id.removePrefix(VIA).substringBefore(SEPARATOR)
        val member = members().firstOrNull { it.source.id == sourceId } ?: return null
        return member to id.substringAfter(SEPARATOR)
    }

    private companion object {
        const val ARTIST = "all:artist:"
        const val ALBUM = "all:album:"
        const val VIA = "all:via:"
        const val SEPARATOR = '\u001f'
    }
}

/** A library that can follow a track it lists to where it belongs: its album and its artist. */
interface TrackHomes {
    /** The album [track] is on; null when the library does not know it. */
    suspend fun albumOf(track: Track): LibraryAlbum?

    /** The artist of the album [track] is on; null when the library does not know it. */
    suspend fun artistOf(track: Track): LibraryArtist?
}

/** A library that can hand over every track it has at once, for the row that stands for all of them. */
interface EveryTrack {
    suspend fun everyTrack(): List<Track>
}

/**
 * A library that knows the folders and the genres of its tracks, for the LISTS tab's FOLDERS and
 * GENRES. A folder or a genre is named, and asked for, by the name the window shows.
 */
interface FoldersAndGenres {
    /** The folders, each with how many tracks it holds; null when that is not known. */
    suspend fun folders(): List<Pair<String, Int?>>

    suspend fun folderTracks(folder: String): List<Track>

    /** The genres, each with how many tracks name it; null when that is not known. */
    suspend fun genres(): List<Pair<String, Int?>>

    suspend fun genreTracks(genre: String): List<Track>
}
