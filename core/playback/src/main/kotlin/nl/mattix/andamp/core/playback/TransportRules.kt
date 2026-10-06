// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport

/**
 * Winamp's transport, as decisions about state: play while playing restarts the track,
 * pause toggles, the skip buttons depend on what the player was doing, and the end of the
 * queue without repeat stops.
 *
 * A backend that owns its own queue applies a rule and compares the transport it had with
 * the transport it got; the difference tells it to start playing, to stop, or to do
 * nothing. A backend whose engine owns its own progression does not call these rules;
 * `PlaybackBackendContractTest` holds both kinds to the same behavior.
 *
 * Metadata arriving for rows already in the queue is [QueuePatch]'s rule.
 */
object TransportRules {
    /**
     * Play, or restart when already playing, as Winamp's play button did.
     *
     * From stopped it plays from the current position. [stop] resets that to zero, so a
     * non-zero position is a seek made while stopped, and it is kept.
     */
    fun play(state: BackendState): BackendState {
        if (state.queue.isEmpty()) return state
        return when (state.transport) {
            Transport.Playing -> state.copy(positionMs = 0)
            Transport.Paused -> state.copy(transport = Transport.Playing)
            Transport.Stopped -> state.copy(transport = Transport.Playing)
        }
    }

    /** Pause toggles, and does nothing while stopped. */
    fun pause(state: BackendState): BackendState =
        when (state.transport) {
            Transport.Playing -> state.copy(transport = Transport.Paused)
            Transport.Paused -> state.copy(transport = Transport.Playing)
            Transport.Stopped -> state
        }

    /** Stop returns to the head of the track, and lets a station go. */
    fun stop(state: BackendState): BackendState = state.copy(transport = Transport.Stopped, positionMs = 0).withStationsAtRest()

    /**
     * Next wraps to the head; an empty queue has no next.
     *
     * Under shuffle it is one of the other rows, so that the button does what shuffle says
     * and not what the list says. [pickShuffled] is handed how many other rows there are and
     * answers with one of them, counted from the row below, so the randomness belongs to the
     * backend. A backend that keeps an order of its own for shuffle follows that instead.
     */
    fun next(
        state: BackendState,
        pickShuffled: (Int) -> Int = { 0 },
    ): BackendState {
        val size = state.queue.size
        val others = size - 1
        val step = if (state.shuffle && others > 1) pickShuffled(others).coerceIn(0, others - 1) else 0
        return jumpTo(state, (state.currentIndex + 1 + step) % size.coerceAtLeast(1))
    }

    /** Previous wraps to the tail. */
    fun previous(state: BackendState): BackendState =
        jumpTo(state, if (state.currentIndex == 0) state.queue.size - 1 else state.currentIndex - 1)

    /**
     * A row picked in the playlist: land there and play, whatever the transport was. An
     * empty queue is left as it is.
     */
    fun playAt(
        state: BackendState,
        index: Int,
    ): BackendState {
        if (state.queue.isEmpty()) return state
        val landed = jumpTo(state, index)
        if (landed.transport == Transport.Playing) return landed
        return landed.copy(transport = Transport.Playing)
    }

    /** A position inside the current track, clamped to its length. */
    fun seekTo(
        state: BackendState,
        positionMs: Long,
    ): BackendState {
        val duration = state.currentTrack?.durationMs ?: return state
        return state.copy(positionMs = positionMs.coerceIn(0, duration))
    }

    /**
     * What follows a track that has finished.
     *
     * [pickShuffled] is handed the queue size and answers with an index, so the randomness
     * belongs to the backend.
     *
     * With [stopAfterCurrent] (Winamp's Stop after current track) it stops with the cursor
     * on the track that ended. At the end of the queue without repeat it stops with the
     * cursor at the top of the list, as Winamp does.
     */
    fun trackEnded(
        state: BackendState,
        pickShuffled: (Int) -> Int,
        stopAfterCurrent: Boolean = false,
    ): BackendState =
        when {
            state.queue.isEmpty() -> stop(state)
            stopAfterCurrent -> stop(state)
            state.shuffle -> jumpTo(state, pickShuffled(state.queue.size))
            state.currentIndex + 1 < state.queue.size -> jumpTo(state, state.currentIndex + 1)
            state.repeat -> jumpTo(state, 0)
            else -> stop(state).copy(currentIndex = 0)
        }

    /**
     * Replace the queue, keeping the music going when the track being played is still in
     * it, matched by id.
     */
    fun setQueue(
        state: BackendState,
        tracks: List<Track>,
        startIndex: Int,
    ): BackendState {
        val surviving = state.currentTrack?.id?.let { id -> tracks.indexOfFirst { it.id == id } } ?: -1
        if (state.transport != Transport.Stopped && surviving >= 0) {
            return state.copy(queue = tracks, currentIndex = surviving)
        }
        return state.copy(
            transport = Transport.Stopped,
            positionMs = 0,
            currentIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)),
            queue = tracks,
        )
    }

    /** Append, and change nothing else. */
    fun enqueue(
        state: BackendState,
        tracks: List<Track>,
    ): BackendState = if (tracks.isEmpty()) state else state.copy(queue = state.queue + tracks)

    /**
     * What the skip buttons do, which depends on the transport.
     *
     * Paused, they play what they land on. Stopped, they only move the cursor. Playing,
     * they carry on playing. In every case a station left behind goes back to its own name
     * ([withStationsAtRest]).
     */
    fun jumpTo(
        state: BackendState,
        index: Int,
    ): BackendState {
        if (state.queue.isEmpty()) return state
        val landed =
            state
                .copy(currentIndex = index.coerceIn(0, state.queue.size - 1), positionMs = 0)
                .withStationsAtRest()
        return if (state.transport == Transport.Paused) {
            landed.copy(transport = Transport.Playing)
        } else {
            landed
        }
    }
}
