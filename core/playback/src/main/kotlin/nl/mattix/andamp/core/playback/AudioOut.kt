// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

/**
 * Where a backend's own samples are heard, and what happens to them on the way.
 *
 * A backend that decodes its own audio hands over a [PcmProvider] and gets back a [tap].
 * The equalizer, the balance, the effect rack and the visualizer tap are behind this
 * interface, so a backend that renders through it can declare
 * [nl.mattix.andamp.core.model.Capabilities.hasEqualizer] and its neighbors.
 */
interface AudioOut {
    /** What the visualizers read, or null before anything has been rendered. */
    val tap: AudioTap?

    /** Start taking samples from [provider] and playing them. Idempotent. */
    fun start(provider: PcmProvider)

    /** Stop, and let go of the device. Idempotent. */
    fun stop()

    /**
     * Silent now, keeping what is waiting to be heard.
     *
     * Without this, audio that was decoded ahead goes on playing after the decoder has
     * paused. The kept samples are the ones [resume] starts with.
     */
    fun pause() {}

    /** Heard again from where [pause] left it. */
    fun resume() {}

    /**
     * Drops whatever is decoded and not yet heard, for a seek, a restart or another track.
     * Leaves the output playing or paused as it was.
     */
    fun discard() {}

    fun setEqualizer(settings: nl.mattix.andamp.core.model.EqSettings)

    fun setBalance(balance: Float)

    fun setDsp(rack: nl.mattix.andamp.core.model.RackSettings)

    fun setPlugins(sources: List<String>)

    /**
     * 0..1, applied to what leaves here and not to the phone's volume. Kept across [stop]
     * and [start], like the equalizer and the rack.
     */
    fun setVolume(fraction: Float)

    /**
     * Who to tell when something outside the app takes the audio output, or gives it back.
     *
     * An output that renders its own samples is the one that hears about another app taking
     * the audio or the headphones being unplugged, and it reports that here so the backend
     * can pause. Ducking is not reported.
     *
     * Called on the main thread. Null stops the reports. The default does nothing, for an
     * output with no device to share, as in JVM tests.
     */
    fun setInterruptions(listener: ((Interruption) -> Unit)?) {}

    /** What happened to the audio output, as the action a player should take. */
    enum class Interruption {
        /** Another app took the audio for good, or the headphones came out: pause, and stay paused. */
        PAUSE,

        /** A call or a spoken prompt took it for a moment: pause until [RESUME]. */
        PAUSE_FOR_NOW,

        /** That moment is over, and a player that paused for it plays again. */
        RESUME,
    }
}
