// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.player

import kotlinx.coroutines.flow.StateFlow
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.playback.AudioTap
import nl.mattix.andamp.core.playback.PlaybackBackend

/**
 * The UI's single entry point to playback. The UI never touches a [PlaybackBackend] directly.
 *
 * It holds one backend for its whole life. A playlist with rows from more than one source is
 * played by [MixedQueueBackend], which hands each row to the player that can open it.
 */
class PlayerFacade(
    private val backend: PlaybackBackend,
) {
    val state: StateFlow<BackendState> get() = backend.state

    val capabilities: Capabilities get() = backend.capabilities

    /** The effects the rack can offer; a backend may discover them after it starts. */
    val effects: List<nl.mattix.andamp.core.model.EffectSpec> get() = backend.effects

    /** The same as a flow, for a page that redraws when a plug-in finishes loading. */
    val effectsFlow: StateFlow<List<nl.mattix.andamp.core.model.EffectSpec>> get() = backend.effectsFlow

    val audioTap: AudioTap? get() = backend.audioTap

    fun play() = backend.play()

    fun pause() = backend.pause()

    fun stop() = backend.stop()

    fun next() = backend.next()

    fun previous() = backend.previous()

    fun playAt(index: Int) = backend.playAt(index)

    /**
     * A press from outside the app's own windows. This is the one place a [TransportCommand]
     * becomes a call, so a press from a widget and a press on the player's own button do the
     * same thing.
     */
    fun obey(command: TransportCommand) =
        when (command) {
            TransportCommand.PLAY -> play()
            TransportCommand.PAUSE -> pause()
            TransportCommand.STOP -> stop()
            TransportCommand.NEXT -> next()
            TransportCommand.PREVIOUS -> previous()
        }

    /** Seek by track fraction; ignored when the backend can't seek. */
    fun seekToFraction(fraction: Float) {
        if (!capabilities.canSeek) return
        val track = state.value.currentTrack ?: return
        // a stream is not seekable whatever length it carries; see
        // [nl.mattix.andamp.core.model.Track.isStream]
        if (track.isStream) return
        val duration = track.durationMs
        // an unknown length has no fraction
        if (duration <= 0) return
        backend.seekTo((fraction.coerceIn(0f, 1f) * duration).toLong())
    }

    fun setVolume(fraction: Float) = backend.setVolume(fraction)

    /**
     * Seek to a position in milliseconds, for restoring where the listener was when the
     * track's length may not be known. Ignored when the backend cannot seek.
     */
    fun seekToMs(positionMs: Long) {
        if (capabilities.canSeek) backend.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Replace the queue; ignored when the backend can't edit it. */
    fun setQueue(
        tracks: List<nl.mattix.andamp.core.model.Track>,
        startIndex: Int = 0,
    ) {
        if (capabilities.canEditQueue) backend.setQueue(tracks, startIndex)
    }

    /** Append to the queue; ignored when the backend can't edit it. */
    fun enqueue(tracks: List<nl.mattix.andamp.core.model.Track>) {
        if (tracks.isEmpty()) return
        if (capabilities.canEditQueue) backend.enqueue(tracks)
    }

    /** Whose volume the slider moves; ignored where the backend cannot attenuate. */
    fun setVolumeMode(mode: nl.mattix.andamp.core.model.VolumeMode) {
        if (capabilities.canAttenuate) backend.setVolumeMode(mode)
    }

    /** Metadata catching up with entries already in the queue; see [PlaybackBackend.patchTracks]. */
    fun patchTracks(patched: List<nl.mattix.andamp.core.model.Track>) {
        if (capabilities.canEditQueue && patched.isNotEmpty()) backend.patchTracks(patched)
    }

    /** Replace the queue and start playing at [startIndex], as one call. */
    fun playQueue(
        tracks: List<nl.mattix.andamp.core.model.Track>,
        startIndex: Int = 0,
    ) {
        if (tracks.isEmpty()) return
        if (!capabilities.canEditQueue) return
        backend.setQueue(tracks, startIndex)
        backend.playAt(startIndex.coerceIn(tracks.indices))
    }

    fun setShuffle(enabled: Boolean) = backend.setShuffle(enabled)

    fun setRepeat(enabled: Boolean) = backend.setRepeat(enabled)

    fun setEqualizer(settings: nl.mattix.andamp.core.model.EqSettings) = backend.setEqualizer(settings)

    /** Apply the DSP selection, if this backend runs effects at all. */
    fun setDsp(rack: nl.mattix.andamp.core.model.RackSettings) {
        if (capabilities.hasDsp) backend.setDsp(rack)
    }

    /** The effect ids a plug-in may not use: the built-in effects' and the shipped plug-ins'. */
    val takenEffectIds: List<String>
        get() =
            backend.shippedPluginIds +
                nl.mattix.andamp.core.model.BuiltInEffects.all
                    .map { it.id }

    /** Offer these plug-ins, if this backend runs effects at all. */
    fun setPlugins(sources: List<String>) {
        if (capabilities.hasDsp) backend.setPlugins(sources)
    }

    /** Pan the output, if this backend can. */
    fun setBalance(balance: Float) {
        if (capabilities.hasBalance) backend.setBalance(balance)
    }

    /** Winamp's Stop with fadeout. */
    fun stopWithFadeout() = backend.stopWithFadeout()

    /** Winamp's Stop after current track. */
    fun setStopAfterCurrent(on: Boolean) = backend.setStopAfterCurrent(on)

    /** Keep playing while no window of the app is up; see [PlaybackBackend.keepAlive]. */
    fun keepAlive() = backend.keepAlive()

    /** Winamp's Exit: stop, then let the backend put itself away. */
    fun exit() {
        backend.stop()
        backend.teardown()
    }

    fun release() = backend.release()
}
