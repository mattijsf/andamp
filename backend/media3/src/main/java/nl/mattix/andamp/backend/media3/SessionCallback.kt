// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Answers whoever controls the session: the notification, a headset, a watch,
 * a car.
 *
 * The transport goes to the player itself. Two things arrive here. Shuffle and
 * repeat, Winamp's two buttons, are custom commands. And a controller may
 * browse: the library is one folder, [ROOT], whose children are the playlist's
 * rows ([SessionRows]).
 *
 * A row picked from that list, and a song asked for by voice, both arrive as a
 * request to set a media item. It is answered with the row it means, which the
 * player goes to, and turned down when it means none. So nothing a controller
 * sends changes the playlist.
 */
internal class SessionCallback(
    private val player: Player,
) : MediaLibrarySession.Callback {
    private val rows = SessionRows(player)

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult
            .AcceptedResultBuilder(session)
            .setAvailableSessionCommands(
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    .buildUpon()
                    .add(SessionCommand(SHUFFLE, Bundle.EMPTY))
                    .add(SessionCommand(REPEAT, Bundle.EMPTY))
                    .build(),
            ).build()

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            SHUFFLE -> {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
            }

            REPEAT -> {
                player.repeatMode =
                    if (player.repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(FOLDER, params))

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = if (mediaId == ROOT) FOLDER else rows.named(mediaId)
        return Futures.immediateFuture(
            if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
        )
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        Futures.immediateFuture(
            if (parentId == ROOT) {
                LibraryResult.ofItemList(paged(rows.all(), page, pageSize), params)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            },
        )

    /** A search is answered in two steps: how many rows match, and then the rows. */
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, rows.matching(query).size, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        Futures.immediateFuture(LibraryResult.ofItemList(paged(rows.matching(query), page, pageSize), params))

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val row =
            mediaItems.firstOrNull()?.let(rows::asked)
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("not a row of the playlist"))
        return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(row), 0, C.TIME_UNSET))
    }

    /** One page of [all]; a browser that does not page asks for one page of every row. */
    private fun paged(
        all: List<MediaItem>,
        page: Int,
        pageSize: Int,
    ): List<MediaItem> = all.drop((page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()).take(pageSize)

    companion object {
        /** The id of the one folder, which holds the playlist. */
        const val ROOT = "playlist"

        const val SHUFFLE = "nl.mattix.andamp.SHUFFLE"
        const val REPEAT = "nl.mattix.andamp.REPEAT"

        private val FOLDER =
            MediaItem
                .Builder()
                .setMediaId(ROOT)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .build(),
                ).build()
    }
}
