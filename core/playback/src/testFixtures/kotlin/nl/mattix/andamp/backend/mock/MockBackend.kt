// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.backend.mock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.playback.QueuePatch
import nl.mattix.andamp.core.playback.TransportRules
import kotlin.random.Random

/**
 * Playback backend with no audio: a coroutine clock advances the position, and every
 * transport decision comes from [TransportRules].
 */
class MockBackend(
    tracks: List<Track>,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val tickMs: Long = 1000,
    /** Row to start on, so a restored playlist comes back with its cursor. */
    startIndex: Int = 0,
) : PlaybackBackend {
    private val _state =
        MutableStateFlow(
            BackendState(queue = tracks, currentIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))),
        )
    override val state: StateFlow<BackendState> = _state

    override val capabilities =
        Capabilities(
            canSeek = true,
            canEditQueue = true,
            // the fake clock's volume is a number it owns; nothing to hand over
            canAttenuate = true,
        )

    private var ticker: Job? = null
    private var stopAfterCurrent = false

    override fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
    ) = apply { TransportRules.setQueue(it, tracks, startIndex) }

    override fun enqueue(tracks: List<Track>) = apply { TransportRules.enqueue(it, tracks) }

    override fun patchTracks(patched: List<Track>) {
        _state.value = _state.value.copy(queue = QueuePatch.apply(_state.value.queue, patched))
    }

    override fun play() = apply(TransportRules::play)

    override fun pause() = apply(TransportRules::pause)

    override fun stop() = apply(TransportRules::stop)

    override fun next() = apply { TransportRules.next(it, random::nextInt) }

    override fun previous() = apply(TransportRules::previous)

    override fun playAt(index: Int) = apply { TransportRules.playAt(it, index) }

    override fun seekTo(positionMs: Long) = apply { TransportRules.seekTo(it, positionMs) }

    override fun setVolume(fraction: Float) {
        _state.value = _state.value.copy(volumeFraction = fraction.coerceIn(0f, 1f))
    }

    override fun setShuffle(enabled: Boolean) {
        _state.value = _state.value.copy(shuffle = enabled)
    }

    override fun setRepeat(enabled: Boolean) {
        _state.value = _state.value.copy(repeat = enabled)
    }

    override fun setStopAfterCurrent(on: Boolean) {
        stopAfterCurrent = on
    }

    override fun release() = stopTicker()

    /**
     * Applies one of the [TransportRules] and then runs or stops the fake clock.
     *
     * The clock is restarted only when the rule changed what it counts, so an enqueue does
     * not set the position back by part of a tick.
     */
    private fun apply(rule: (BackendState) -> BackendState) {
        val was = _state.value
        val now = rule(was)
        _state.value = now
        when {
            now.transport != Transport.Playing -> stopTicker()

            ticker == null || movedTheClock(was, now) -> startTicker()

            // an append, or a queue edit the playing track survived: the clock keeps its phase
            else -> Unit
        }
    }

    /** A start, another track, or a position of its own: the clock counts from there. */
    private fun movedTheClock(
        was: BackendState,
        now: BackendState,
    ) = was.transport != Transport.Playing ||
        was.currentTrack?.id != now.currentTrack?.id ||
        was.positionMs != now.positionMs

    private fun startTicker() {
        ticker?.cancel()
        ticker =
            scope.launch {
                while (true) {
                    delay(tickMs)
                    tick()
                }
            }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun tick() {
        val s = _state.value
        if (s.transport != Transport.Playing) return
        val duration = s.currentTrack?.durationMs ?: return
        val newPos = s.positionMs + tickMs
        if (newPos >= duration) trackEnded() else _state.value = s.copy(positionMs = newPos)
    }

    private fun trackEnded() = apply { TransportRules.trackEnded(it, random::nextInt, stopAfterCurrent) }
}
