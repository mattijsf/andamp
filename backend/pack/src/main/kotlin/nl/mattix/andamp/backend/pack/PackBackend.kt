// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.VolumeMode
import nl.mattix.andamp.core.packapi.IMusicSourcePack
import nl.mattix.andamp.core.packapi.IPackListener
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackState
import nl.mattix.andamp.core.packapi.toPack
import nl.mattix.andamp.core.packapi.toState
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.AudioTap
import nl.mattix.andamp.core.playback.PlaybackBackend

/**
 * A pack's player, as the rest of the app sees one.
 *
 * The transport lives in the pack: the queue, the cursor, shuffle, repeat, stop-after-current
 * and Winamp's rules run there, over the rows this hands it. The pack reports on [IPackListener]
 * and this class publishes that as [state].
 *
 * The audio is rendered here. The pack decodes and writes frames into a pipe ([PackAudio]), and
 * this player renders them through the chain a local file goes through, which holds the
 * equalizer, the balance, the effect rack and the visualizer tap. [out] therefore decides the
 * capability flags and what [audioTap] answers.
 *
 * Verbs cross on a channel read by one coroutine, so they reach the pack in the order they were
 * pressed. The channel is unbounded, so sending never blocks the caller.
 *
 * When the pack cannot be reached, verbs are dropped and [state] keeps the last value the pack
 * sent. [PackClient.reach] is what tells the listener why.
 */
@Suppress("TooManyFunctions") // PlaybackBackend's own members, each a one-line translation
class PackBackend internal constructor(
    private val client: PackClient,
    tracks: List<Track>,
    startIndex: Int,
    private val scope: CoroutineScope,
    /**
     * Where the pack's samples are rendered. With null the player shows what the pack reports
     * and renders nothing, which is how the JVM tests run it.
     */
    private val out: AudioOut? = null,
) : PlaybackBackend {
    private val _state =
        MutableStateFlow(
            BackendState(
                queue = tracks,
                currentIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)),
            ),
        )

    /**
     * What the pack says about playback. The opening value is the queue this was built with, so
     * the playlist window has rows to draw before the pack has answered.
     */
    override val state: StateFlow<BackendState> = _state.asStateFlow()

    /**
     * The pack's audio, read a buffer at a time; only where there is an [out].
     *
     * A pipe ends when the pack has dropped what it decoded, for a seek for example. The
     * chain's tails and the device's buffer then hold audio from before the drop, so they are
     * discarded too.
     */
    private val audio = out?.let { device -> PackAudio(client, dropped = device::discard) }

    /**
     * What the player can do: what the pack says, plus what this side renders.
     *
     * A getter because the descriptor arrives with the binding. Until then the answer is a
     * player that can do nothing. The equalizer, balance and DSP flags are set when there is
     * an [out], because its chain provides them.
     */
    override val capabilities: Capabilities
        get() {
            val said = client.known?.playback ?: Capabilities(canSeek = false)
            return if (out == null) said else said.copy(hasEqualizer = true, hasBalance = true, hasDsp = true)
        }

    /**
     * The chain's tap, which the visualizers read. Null until something has been rendered, and
     * always null without an [out].
     */
    override val audioTap: AudioTap? get() = out?.tap

    /**
     * What the output is doing, as the last state the pack sent left it. Guarded by this
     * object's lock: [render] and [ended] are synchronized, because the pack's state arrives
     * on binder threads.
     */
    private var rendering: Transport? = null

    /**
     * Whether the output is paused under a Playing that is reconnecting; see [render]. Under
     * the same lock as [rendering].
     */
    private var holding = false

    /** The pending stop of the output after a queue finished; see [stopping]. */
    @Volatile
    private var tail: Job? = null

    /** A stop the listener pressed, waiting for the state that confirms it; see [stopping]. */
    @Volatile
    private var pressedStop = false

    /** Whose volume the slider is; see [setVolumeMode]. */
    private var volumeMode = VolumeMode.DEVICE

    /** The slider's level, kept here because the gain is applied before the pack echoes it. */
    private var volume = _state.value.volumeFraction

    /** Paused by an interruption that may end, so its end resumes playback; see [interrupted]. */
    private var pausedForNow = false

    private val listener =
        object : IPackListener.Stub() {
            override fun onState(state: PackState?) {
                val said = state?.toState() ?: return
                _state.value = said
                render(said.transport, holding = said.transport == Transport.Playing && said.connecting)
            }

            /** Somebody signed in or out on the pack's own screen; handed to the client. */
            override fun onAccount(account: PackAccount?) {
                account?.let(client::heard)
            }
        }

    /** Verbs waiting to be sent. Unbounded, so a press never blocks the calling thread. */
    private val verbs = Channel<(IMusicSourcePack) -> Unit>(Channel.UNLIMITED)

    init {
        // the output reports calls, prompts, other apps and unplugged headphones; the
        // transport is the pack's, so [interrupted] passes them on
        out?.setInterruptions(::interrupted)
        // the client keeps the listener too and registers it again on every later binding;
        // see PackClient.remember
        client.remember(listener)
        send { it.listen(listener) }
        send { it.setQueue(tracks.map { track -> track.toPack() }, startIndex) }
        scope.launch {
            for (verb in verbs) client.tell(verb)
        }
    }

    override fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
    ) = send { it.setQueue(tracks.map { track -> track.toPack() }, startIndex) }

    override fun enqueue(tracks: List<Track>) = send { it.enqueue(tracks.map { track -> track.toPack() }) }

    override fun patchTracks(patched: List<Track>) = send { it.patchTracks(patched.map { track -> track.toPack() }) }

    /**
     * Whose volume the slider moves, and so whether this player attenuates.
     *
     * In [VolumeMode.DEVICE] the slider is the phone's media volume, so this renders at full
     * gain; attenuating here as well would apply the level twice. In [VolumeMode.APP] the
     * slider is this player's own gain.
     */
    override fun setVolumeMode(mode: VolumeMode) {
        volumeMode = mode
        applyGain()
        send { it.setVolumeMode(mode.ordinal) }
    }

    override fun play() = pressed { it.play() }

    override fun pause() = pressed { it.pause() }

    /**
     * The flag is set when the stop is pressed and not inside the verb, which runs later on the
     * verb coroutine.
     */
    override fun stop() {
        pressedStop = true
        pressed { it.stop() }
    }

    override fun next() = pressed { it.next() }

    override fun previous() = pressed { it.previous() }

    override fun playAt(index: Int) = pressed { it.playAt(index) }

    override fun seekTo(positionMs: Long) = send { it.seekTo(positionMs) }

    /**
     * The level goes to the chain as a gain, because this side mixes, and to the pack, whose
     * state carries the number the sliders and the notification draw.
     */
    override fun setVolume(fraction: Float) {
        volume = fraction.coerceIn(0f, 1f)
        applyGain()
        send { it.setVolume(fraction) }
    }

    override fun setShuffle(enabled: Boolean) = send { it.setShuffle(enabled) }

    override fun setRepeat(enabled: Boolean) = send { it.setRepeat(enabled) }

    override fun setStopAfterCurrent(on: Boolean) = send { it.setStopAfterCurrent(on) }

    override fun stopWithFadeout() {
        pressedStop = true
        pressed { it.stopWithFadeout() }
    }

    override fun setEqualizer(settings: EqSettings) {
        out?.setEqualizer(settings)
    }

    override fun setBalance(balance: Float) {
        out?.setBalance(balance)
    }

    override fun setDsp(rack: RackSettings) {
        out?.setDsp(rack)
    }

    override fun setPlugins(sources: List<String>) {
        out?.setPlugins(sources)
    }

    /**
     * Nothing to do: the pack holds no foreground service of its own. This player's media
     * notification keeps both processes running, because the binding lends the pack this app's
     * capabilities; see [PackClient].
     */
    override fun keepAlive() = Unit

    override fun teardown() = send { it.teardown() }

    /**
     * Done with the pack as a player: the pack is told, the listener is removed, and the device
     * and the pipe are let go of.
     *
     * The binding is [PackClient]'s and the library shares it, so it stays;
     * [PackClient.release] drops it.
     */
    override fun release() {
        // before the verb, so that a binding made meanwhile does not register this listener
        client.forget(listener)
        send { it.stopListening(listener) }
        send { it.release() }
        verbs.close()
        tail?.cancel()
        out?.setInterruptions(null)
        out?.stop()
        // after the device, so that a read waiting in the pipe is woken
        audio?.close()
    }

    /**
     * Makes the output do what the pack says it is doing.
     *
     * Driven by the pack's state and not by the press, so that a press the pack refused, a
     * track that ended and a stop made elsewhere are all handled the same way.
     *
     * Synchronized because it runs on whichever binder thread brought the state.
     *
     * A pack whose song broke off keeps reporting Playing with `connecting` set while it waits
     * to pick it up again. The device is paused for that time, so that it holds no wake lock
     * or Wi-Fi lock to render silence, and resumed once `connecting` clears. The render thread
     * reads from the pipe before it waits on the device, so the first samples still cross
     * while the device is paused.
     */
    @Synchronized
    private fun render(
        transport: Transport,
        holding: Boolean = false,
    ) {
        val out = out ?: return
        val samples = audio ?: return
        if (transport == rendering) {
            if (transport == Transport.Playing && holding != this.holding) {
                this.holding = holding
                if (holding) out.pause() else out.resume()
            }
            return
        }
        this.holding = holding && transport == Transport.Playing
        // Only a transport other than Stopped clears a pressed stop. A repeat of the same
        // transport returns above and leaves it set.
        if (transport != Transport.Stopped) pressedStop = false
        rendering = transport
        tail?.cancel()
        tail = null
        when (transport) {
            Transport.Playing -> {
                // start takes the samples; resume lifts a pause that was already in effect
                out.start(samples)
                if (this.holding) out.pause() else out.resume()
            }

            Transport.Paused -> {
                out.pause()
            }

            Transport.Stopped -> {
                stopping(out)
            }
        }
    }

    /**
     * Stops the device at once after a stop the listener pressed, and after [TAIL_MS]
     * otherwise.
     *
     * The pack reports a finished queue when it has decoded the last of it. The end of that
     * audio is then still in the pipe, the chain and the device's buffer, and is given time to
     * play out.
     */
    private fun stopping(out: AudioOut) {
        if (pressedStop) {
            pressedStop = false
            letGo(out)
            return
        }
        tail =
            scope.launch {
                delay(TAIL_MS)
                ended()
            }
    }

    /**
     * Stops the device after a finished queue, unless something started playing during the
     * wait.
     *
     * Synchronized with [render]: cancelling [tail] does not stop this once the delay has
     * passed, so the transport is checked under the lock.
     */
    @Synchronized
    private fun ended() {
        val device = out ?: return
        if (rendering != Transport.Stopped) return
        letGo(device)
    }

    /**
     * Stops the device and drops the pipe.
     *
     * A render thread waiting in a read on the pipe is woken only by the pipe closing. The
     * pack treats the read end closing as the end of that stretch, and the next play asks for
     * a new pipe.
     */
    private fun letGo(out: AudioOut) {
        out.stop()
        audio?.discard()
    }

    /**
     * An interruption reported by the output. Only [AudioOut.Interruption.PAUSE_FOR_NOW] is
     * undone by a later [AudioOut.Interruption.RESUME].
     *
     * The device is paused here at once and the pack is told to pause as well, because the
     * transport is the pack's: otherwise it would go on decoding into a pipe nobody drains.
     */
    private fun interrupted(interruption: AudioOut.Interruption) {
        val playing = _state.value.transport == Transport.Playing
        when (interruption) {
            AudioOut.Interruption.PAUSE -> {
                // permanent: an earlier PAUSE_FOR_NOW is not resumed either
                pausedForNow = false
                if (playing) silence()
            }

            AudioOut.Interruption.PAUSE_FOR_NOW -> {
                if (playing) {
                    silence()
                    pausedForNow = true
                }
            }

            AudioOut.Interruption.RESUME -> {
                val ours = pausedForNow && _state.value.transport == Transport.Paused
                pausedForNow = false
                if (ours) play()
            }
        }
    }

    /** Pauses the device at once and tells the pack to pause; see [interrupted]. */
    private fun silence() {
        out?.pause()
        pause()
    }

    /** The slider's level in [VolumeMode.APP], full gain in [VolumeMode.DEVICE]. */
    private fun applyGain() {
        out?.setVolume(if (volumeMode == VolumeMode.APP) volume else 1f)
    }

    /**
     * A transport press of the listener's own. It clears [pausedForNow], so that the end of an
     * interruption does not undo what they pressed during it. Queue edits and settings go
     * straight to [send].
     */
    private fun pressed(verb: (IMusicSourcePack) -> Unit) {
        pausedForNow = false
        send(verb)
    }

    /** Queues a verb. Never throws or waits; on a released player it does nothing. */
    private fun send(verb: (IMusicSourcePack) -> Unit) {
        verbs.trySend(verb)
    }

    private companion object {
        /**
         * How long the end of a finished queue is given to play out, in milliseconds. What
         * remains is what the pack wrote into the pipe, one read in the chain and the
         * device's buffer.
         */
        const val TAIL_MS = 2_000L
    }
}
