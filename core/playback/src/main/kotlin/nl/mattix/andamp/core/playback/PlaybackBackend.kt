// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

import kotlinx.coroutines.flow.StateFlow
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.Track

// shared by the backends whose effect list is a constant
private val FIXED_EFFECTS =
    kotlinx.coroutines.flow.MutableStateFlow(nl.mattix.andamp.core.model.BuiltInEffects.all)

/**
 * The contract every playback backend implements.
 *
 * - Backend SDK types never escape the implementing module; [BackendState] and [Track] are
 *   the only currency.
 * - The backend owns queue advance, shuffle order and repeat. When the player holds one
 *   queue over several backends, the player owns the advance between the runs it hands each
 *   backend, and each backend still advances inside its run.
 * - Methods are fire-and-forget; results arrive via [state]. Implementations launch
 *   internally when their SDK is async.
 * - `PlaybackBackendContractTest`, in this module's test fixtures, is the definition of the
 *   contract. A backend should subclass it and pass it.
 */
@Suppress("TooManyFunctions") // Winamp's transport is a wide API by nature
interface PlaybackBackend {
    val state: StateFlow<BackendState>
    val capabilities: Capabilities

    /**
     * PCM tap for visualizers; null when the backend cannot expose audio data. This
     * nullability is the capability: [Capabilities] has no flag for it.
     */
    val audioTap: AudioTap? get() = null

    /**
     * Replace the play queue. If the currently playing track (by id) survives
     * the edit, playback continues uninterrupted at its new index; otherwise
     * the backend stops at [startIndex]. Backends without
     * [Capabilities.canEditQueue] ignore this.
     */
    fun setQueue(
        tracks: List<Track>,
        startIndex: Int = 0,
    )

    /**
     * Append to the play queue without touching the current track, transport, position or
     * cursor. Backends without [Capabilities.canEditQueue] ignore this.
     */
    fun enqueue(tracks: List<Track>) {}

    /**
     * Rewrite queue entries in place, matched by [Track.id], without touching the transport,
     * the position or the cursor.
     *
     * This is for metadata arriving for entries already in the queue, such as tags read
     * after a folder was added. The patch's [Track.uri] is ignored, and ids that are not in
     * the queue are dropped; see [QueuePatch].
     */
    fun patchTracks(patched: List<Track>) {}

    /**
     * Whose volume [setVolume] moves from now on; see
     * [nl.mattix.andamp.core.model.VolumeMode]. A backend without
     * [Capabilities.canAttenuate] ignores this and keeps moving the device's volume.
     */
    fun setVolumeMode(mode: nl.mattix.andamp.core.model.VolumeMode) {}

    fun play()

    fun pause()

    fun stop()

    fun next()

    fun previous()

    fun playAt(index: Int)

    /**
     * Move inside the current track, clamped to its length.
     *
     * A seek while stopped sets where the next [play] starts, which is how the player
     * restores a position after a relaunch. [stop] resets the position to zero.
     */
    fun seekTo(positionMs: Long)

    /**
     * Move the volume 0..1, and report it back through [state].
     *
     * Every backend answers this. A backend with no volume of its own moves the device's
     * media volume, so the slider and the phone's volume keys are one control.
     */
    fun setVolume(fraction: Float)

    fun setShuffle(enabled: Boolean)

    fun setRepeat(enabled: Boolean)

    /** Apply equalizer settings; default no-op for backends without [Capabilities.hasEqualizer]. */
    fun setEqualizer(settings: nl.mattix.andamp.core.model.EqSettings) {}

    /**
     * Pan the output: -1 hard left, 0 center, +1 hard right. Default no-op for
     * backends without [Capabilities.hasBalance].
     */
    fun setBalance(balance: Float) {}

    /** Apply the effect rack, in signal order; default no-op without [Capabilities.hasDsp]. */
    fun setDsp(rack: nl.mattix.andamp.core.model.RackSettings) {}

    /**
     * The source text of the plug-ins the listener installed. A backend that runs no
     * effects ignores this; one that does updates [effects].
     */
    fun setPlugins(sources: List<String>) {}

    /**
     * The effects this backend can run, in the order a fresh rack shows them.
     *
     * A backend may find its effects at run time, so the list can change; see
     * [effectsFlow]. The default is the built-in list.
     */
    val effects: List<nl.mattix.andamp.core.model.EffectSpec>
        get() = nl.mattix.andamp.core.model.BuiltInEffects.all

    /**
     * [effects] as a flow, for a caller that has to notice it changing. Plug-ins load
     * asynchronously, so the list can change after a caller first read it.
     */
    val effectsFlow: StateFlow<List<nl.mattix.andamp.core.model.EffectSpec>>
        get() = FIXED_EFFECTS

    /**
     * The ids of the plug-ins this backend ships, available before anything has played.
     *
     * [effects] may not list them until a track's format is known. The player reads this
     * when a plug-in file is installed, to see whether the file claims the id of a plug-in
     * that is already shipped.
     */
    val shippedPluginIds: List<String>
        get() = emptyList()

    /**
     * Winamp's Stop with fadeout: [stop], after fading the output down.
     *
     * The default is a plain [stop], for a backend that has no gain of its own to ramp. An
     * implementation must not fade by moving the volume the listener controls.
     */
    fun stopWithFadeout() {
        stop()
    }

    /**
     * Winamp's Stop after current track: play this one out, then stop.
     *
     * The backend is told because it owns the advance. It ends stopped on the track that
     * was played out, and the setting stays on until it is turned off.
     */
    fun setStopAfterCurrent(on: Boolean) {}

    /**
     * Keep this backend able to play while no window of the app is on screen, when the
     * process could otherwise be cached and frozen. The default does nothing, for a backend
     * that needs nothing.
     */
    fun keepAlive() {}

    /**
     * Winamp's Exit: nothing of this backend keeps running, its notification included. The
     * player stops the music first, separately.
     *
     * Unlike [release] this is not final: a backend may be asked to play again afterwards
     * without being rebuilt.
     */
    fun teardown() {}

    fun release()
}
