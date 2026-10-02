// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.PcmProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A backend's own samples, through this module's audio chain and out of the
 * audio device.
 *
 * The chain is [PcmChain]: the processors a local file goes through, in the
 * same order. This class is the loop and the device: read, process, write.
 * The write blocks, so the `AudioTrack` paces the loop by the audio, and that
 * back-pressure reaches the provider of the samples.
 *
 * Given a [context], it also holds the audio focus, watches the headphones
 * and stays awake while it sounds (see [AudioManners]), and reports through
 * [setInterruptions] when to pause. Without one, as in a JVM test, it does
 * none of that.
 *
 * It renders directly and is not a second ExoPlayer; the transport stays with
 * the backend that owns it.
 */
class PcmAudioOut(
    context: Context? = null,
    private val chain: PcmChain = PcmChain(),
) : AudioOut {
    override val tap get() = if (started) chain.tap else null

    /** Guards starting and stopping, which the render thread takes part in; see [pump]. */
    private val lock = Any()

    @Volatile private var started = false

    /** A render thread is alive, and checks [started] before each read. Guarded by [lock]. */
    private var pumping = false

    @Volatile private var track: AudioTrack? = null

    @Volatile private var provider: PcmProvider? = null

    /** Held silent by [pause]; a [discard] does not start it again. */
    @Volatile private var held = false

    /**
     * The listener's level. Kept here and applied to each device as it opens,
     * so a level set before anything plays is used.
     */
    @Volatile private var volume = 1f

    /** Incremented by every [discard], so the render thread can tell output made before one. */
    private val discards = AtomicInteger()

    /** Set when the chain must drop its tails before it next runs; see [PcmChain.flush]. */
    private val flushChain = AtomicBoolean(false)

    private val manners = context?.let { AudioManners(it, ::applyGain) }

    /** Opens a device: the platform's, unless a test replaces it. */
    internal var openDevice: () -> AudioTrack = ::openTrack

    override fun start(provider: PcmProvider) {
        synchronized(lock) {
            if (started) return
            // the device first and the state after: a device that does not
            // open or play leaves this stopped, so a later start can try again
            val device = openDevice()
            try {
                device.setVolume(gain())
                device.play()
            } catch (refused: IllegalStateException) {
                device.release()
                throw refused
            }
            held = false
            this.provider = provider
            track = device
            started = true
            // a new run starts without the last one's tail
            flushChain.set(true)
            // a render thread still waiting in a read from before a stop
            // serves this run; another is started only when there is none
            if (!pumping) startPump()
            manners?.sounding()
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            provider = null
            val device = track
            track = null
            // stopping the device also wakes a write the render thread is
            // blocked in, so nothing here waits for that thread
            device?.let {
                runCatching { it.stop() }
                it.release()
            }
            manners?.done()
        }
    }

    // pauses the device, not the loop: the loop blocks in write() while the
    // track is paused, as it does when the buffer is full
    override fun pause() {
        synchronized(lock) {
            held = true
            track?.let { runCatching { it.pause() } }
            manners?.silent()
        }
    }

    override fun resume() {
        synchronized(lock) {
            held = false
            val device = track ?: return
            runCatching { device.play() }
            manners?.sounding()
        }
    }

    /**
     * Discards what waits upstream, then what the device holds, then the
     * chain's tails.
     *
     * A flush only works on a track that is not playing, so the track is
     * paused for the flush and played again unless it is held. The chain
     * belongs to the render thread, which flushes it before it next runs and
     * drops whatever output it was writing when this was called.
     */
    override fun discard() {
        provider?.discard()
        flushChain.set(true)
        discards.incrementAndGet()
        val device = track ?: return
        runCatching {
            device.pause()
            device.flush()
            if (!held) device.play()
        }
    }

    override fun setEqualizer(settings: EqSettings) = chain.setEqualizer(settings)

    override fun setBalance(balance: Float) = chain.setBalance(balance)

    override fun setDsp(rack: RackSettings) = chain.setDsp(rack)

    override fun setPlugins(sources: List<String>) = chain.setPlugins(sources)

    override fun setVolume(fraction: Float) {
        volume = fraction.coerceIn(0f, 1f)
        applyGain()
    }

    override fun setInterruptions(listener: ((AudioOut.Interruption) -> Unit)?) {
        manners?.listener = listener
    }

    /** The device's gain: the listener's level, scaled down during a duck. */
    private fun gain() = volume * (manners?.scale ?: 1f)

    private fun applyGain() {
        track?.setVolume(gain())
    }

    /** Starts a render thread. Called under [lock], and only when none is alive. */
    private fun startPump() {
        pumping = true
        Thread({ pump() }, "andamp-pcm-out").apply {
            // highest priority, so the loop refills the device in time
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    /**
     * The render loop: read, process, write, for as long as this is started.
     *
     * There is one thread at a time, and it may outlive a [stop]: a read can
     * block waiting for samples, and nothing interrupts it. A [start] in that
     * window is served by the waiting thread, on the new device. A second
     * thread would share the chain, which is not safe to share.
     *
     * When the loop fails, or the provider has no more to give, the run it was
     * serving is stopped, so a later [start] works.
     */
    private fun pump() {
        val buffer = ByteArray(READ_BYTES)
        var serving: AudioTrack? = null
        while (true) {
            val source =
                synchronized(lock) {
                    // stopped: this thread ends, and says so under the lock
                    if (!started) {
                        pumping = false
                        return
                    }
                    serving = track
                    provider
                }
            if (source == null || !renderSafely(source, buffer)) break
        }
        synchronized(lock) {
            pumping = false
            when {
                !started -> Unit

                track === serving -> stop()

                // a newer run began while this one was ending, and has no
                // render thread
                else -> startPump()
            }
        }
    }

    /**
     * [renderOnce], with a failure logged and treated as the end of the loop,
     * so an exception on this thread does not end the app.
     */
    private fun renderSafely(
        source: PcmProvider,
        buffer: ByteArray,
    ): Boolean =
        runCatching { renderOnce(source, buffer) }
            .onFailure { Log.w(TAG, "the render loop failed", it) }
            .getOrDefault(false)

    /** One read and what follows from it; returns whether there is more to come. */
    private fun renderOnce(
        source: PcmProvider,
        buffer: ByteArray,
    ): Boolean {
        val read = source.read(buffer)
        // -1 means the provider has no more; 0 means none arrived in time, and
        // the loop reads again
        if (read <= 0) return read == 0
        val mark = discards.get()
        if (flushChain.getAndSet(false)) chain.flush()
        deliver(chain.process(buffer, read), mark)
        return true
    }

    /**
     * Writes [size] bytes of the chain's output to the device.
     *
     * A write that returns short was woken by a pause or a flush, and the rest
     * is written again; during a pause this is where the loop waits. It stops
     * early when the device has gone, or when a discard came after the bytes
     * were produced.
     */
    private fun deliver(
        size: Int,
        mark: Int,
    ) {
        var from = 0
        while (from < size && discards.get() == mark) {
            val device = track ?: return
            val wrote = device.write(chain.output, from, size - from, AudioTrack.WRITE_BLOCKING)
            if (wrote < 0) return
            from += wrote
        }
    }

    private fun openTrack(): AudioTrack {
        val least =
            AudioTrack.getMinBufferSize(
                PcmProvider.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        return AudioTrack
            .Builder()
            // the same attributes the focus is asked for with
            .setAudioAttributes(musicAttributes())
            .setAudioFormat(
                AudioFormat
                    .Builder()
                    .setSampleRate(PcmProvider.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            // several minimum buffers, so a short stall in the loop is not
            // heard as a gap
            .setBufferSizeInBytes(least * BUFFERS)
            .build()
    }

    private companion object {
        const val TAG = "AndAmpPcmOut"

        /** About 93 ms at 44.1 kHz stereo. */
        const val READ_BYTES = 16_384
        const val BUFFERS = 4
    }
}
