// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.PlaybackBackend

/**
 * Any backend, as a Media3 `Player`.
 *
 * The notification, the lock screen, headset buttons, the car and the watch
 * all talk to a `MediaSession`, and a session talks to a `Player`. This adapter
 * wraps [PlaybackBackend], the contract every backend keeps, so one session,
 * service and notification serve all of them, including a backend that decodes
 * its own audio.
 *
 * It is not a queue editor. Media3's commands for editing the playlist are
 * left unavailable: the playlist belongs to the app.
 *
 * It runs on the main looper, where the state is collected and where Media3
 * expects to hear about a change.
 */
@UnstableApi
internal class BackendPlayer(
    private val backend: PlaybackBackend,
    scope: CoroutineScope,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    init {
        scope.launch(Dispatchers.Main) {
            // Invalidate on what changes the shape of the state, not on the
            // position. Media3 reads the position through the supplier in
            // getState, and invalidating on each position update would rebuild
            // the whole playlist on the main thread for no new information.
            backend.state
                .map(::shapeOf)
                .distinctUntilChanged()
                .collect { invalidateState() }
        }
    }

    /**
     * The part of a backend's state that changes what the session shows:
     * everything except the position. The queue is compared by rows, not by
     * ids, because metadata that arrives later (a station's song title, tags,
     * a length, a cover) keeps the id.
     */
    private fun shapeOf(state: BackendState) =
        listOf(
            state.transport,
            state.currentIndex,
            state.shuffle,
            state.repeat,
            state.volumeFraction,
            state.queue,
        )

    override fun getState(): State {
        val now = backend.state.value
        val at = now.currentIndex.coerceIn(0, (now.queue.size - 1).coerceAtLeast(0))
        val current = now.queue.getOrNull(at)
        return State
            .Builder()
            .setAvailableCommands(if (current != null && seekable(current)) COMMANDS else UNSEEKABLE)
            .setPlayWhenReady(now.transport == Transport.Playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (now.queue.isEmpty()) STATE_IDLE else STATE_READY)
            .setPlaylist(itemsOf(now.queue))
            .setCurrentMediaItemIndex(at)
            .setShuffleModeEnabled(now.shuffle)
            .setRepeatMode(if (now.repeat) REPEAT_MODE_ALL else REPEAT_MODE_OFF)
            // reported because COMMAND_SET_VOLUME is granted: a controller
            // reads the volume back after setting it
            .setVolume(now.volumeFraction.coerceIn(0f, 1f))
            // the position is read from the backend, not counted here
            .setContentPositionMs { backend.state.value.positionMs }
            .build()
    }

    /**
     * Media3's play and pause are setters; the backend's are a restart and a
     * toggle.
     *
     * A car sends PLAY when it connects and a headset sends PAUSE whatever the
     * player is doing, and Media3 passes both on even when nothing would
     * change. So the backend is called only when the requested state differs
     * from its own.
     */
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val playing = backend.state.value.transport == Transport.Playing
        when {
            // from paused that resumes, and from stopped it starts
            playWhenReady && !playing -> backend.play()

            // playing to paused; a pause while paused would toggle back to playing
            !playWhenReady && playing -> backend.pause()

            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        backend.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                backend.next()
            }

            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                backend.previous()
            }

            // a jump to another row is the one seek whose index means anything
            COMMAND_SEEK_TO_MEDIA_ITEM -> {
                val now = backend.state.value
                val row = if (mediaItemIndex == C_INDEX_UNSET) now.currentIndex else mediaItemIndex
                if (row != now.currentIndex) backend.playAt(row)
                seekWithin(now.queue.getOrNull(row), positionMs)
            }

            // Every other seek is within the current row. Media3 fills the
            // index in from what it last read, which can be a row behind when a
            // track has just ended and advanced, so the index is ignored.
            else -> {
                seekWithin(backend.state.value.currentTrack, positionMs)
            }
        }
        return Futures.immediateVoidFuture()
    }

    private fun seekWithin(
        track: Track?,
        positionMs: Long,
    ) {
        if (positionMs != C_TIME_UNSET && track != null && seekable(track)) backend.seekTo(positionMs)
    }

    /**
     * Whether a row has a position to go to: the rule the app's own position
     * bar keeps in `PlayerFacade.seekToFraction`, repeated here because the
     * session does not pass through the facade. A scrub from a car or a watch
     * on a station would otherwise reconnect it.
     */
    private fun seekable(track: Track) = backend.capabilities.canSeek && !track.isStream && track.durationMs > 0

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        backend.setShuffle(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        backend.setRepeat(repeatMode != REPEAT_MODE_OFF)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        backend.setVolume(volume)
        return Futures.immediateVoidFuture()
    }

    /**
     * The queue as Media3 items, cached per queue because `getState` is called
     * often.
     *
     * Every row gets a uid of its own, because Media3 throws on a playlist with
     * two of one and a queue can hold the same track twice. The first row of
     * an id keeps the id as its uid; the repeats are numbered after it.
     */
    private fun itemsOf(queue: List<Track>): List<MediaItemData> {
        val same = made
        if (same != null && same.first == queue) return same.second
        val taken = HashSet<String>()
        val items = queue.map { track -> itemOf(track, uid = uniqueUid(track.id, taken)) }
        made = queue to items
        return items
    }

    private var made: Pair<List<Track>, List<MediaItemData>>? = null

    /** [id], or [id] numbered until it is not in [taken]; the result is added to [taken]. */
    private fun uniqueUid(
        id: String,
        taken: MutableSet<String>,
    ): String {
        var uid = id
        var repeat = 1
        while (!taken.add(uid)) {
            repeat++
            uid = "$id#$repeat"
        }
        return uid
    }

    /** The row as Media3 item data, which the notification and the lock screen read. */
    private fun itemOf(
        track: Track,
        uid: String,
    ): MediaItemData {
        // a stream has no length, whatever durationMs holds: a length would
        // show a scrubber over a live broadcast
        val length = if (!track.isStream && track.durationMs > 0) track.durationMs * 1_000 else C_TIME_UNSET
        return MediaItemData
            .Builder(uid)
            .setMediaItem(
                MediaItem
                    .Builder()
                    .setMediaId(track.id)
                    .setUri(track.uri)
                    .setMediaMetadata(track.toMediaMetadata())
                    .build(),
            ).setDurationUs(length)
            .setIsSeekable(seekable(track))
            .setIsDynamic(track.isStream)
            .build()
    }

    private companion object {
        const val C_INDEX_UNSET = androidx.media3.common.C.INDEX_UNSET
        const val C_TIME_UNSET = androidx.media3.common.C.TIME_UNSET

        /**
         * What a controller may ask for: the transport, shuffle, repeat and
         * volume. No command edits the playlist, which belongs to the app.
         */
        val COMMANDS: Player.Commands =
            Player.Commands
                .Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_STOP,
                    COMMAND_PREPARE,
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_MEDIA_ITEM,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_TIMELINE,
                    COMMAND_GET_METADATA,
                    COMMAND_SET_SHUFFLE_MODE,
                    COMMAND_SET_REPEAT_MODE,
                    COMMAND_GET_VOLUME,
                    COMMAND_SET_VOLUME,
                ).build()

        /**
         * The same without a seek inside the row, for a row with nowhere to
         * seek to. ExoPlayer withholds it for a live item too.
         */
        val UNSEEKABLE: Player.Commands =
            COMMANDS
                .buildUpon()
                .remove(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .build()
    }
}
