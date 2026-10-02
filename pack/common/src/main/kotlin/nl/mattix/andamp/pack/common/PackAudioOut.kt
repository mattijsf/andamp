// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common

import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.PcmProvider
import java.io.OutputStream

/**
 * An [AudioOut] that writes the pack's decoded audio into a pipe the player reads. The
 * player renders it, which gives the source the player's equalizer, effect rack and
 * visualizer.
 *
 * Closing the pipe is how a discard crosses. There is no marker in the stream: a seek,
 * another track or a stop closes this end, the player reads the end of the stream, drops
 * what it was holding and asks for a pipe that starts where the music now is.
 *
 * A write blocks when the player is not draining. That is the back-pressure that keeps the
 * engine from decoding far ahead of what is heard.
 */
class PackAudioOut : AudioOut {
    /** Guards both ends and the carrier thread's flag. Never held around a read. */
    private val lock = Any()

    /** The engine's samples, from [start] until [stop]. Under [lock]. */
    private var provider: PcmProvider? = null

    /** The write end of the pipe the player is reading, or null between pipes. Under [lock]. */
    private var pipe: OutputStream? = null

    /** A carrier thread is alive and will read these fields again. Under [lock]. */
    private var pumping = false

    /** Null: the visualizer reads the tap of the player's chain, which is the one that renders. */
    override val tap = null

    /**
     * Takes the write end of a pipe the player has just asked for, and fills it from here
     * on.
     *
     * Called on the binder thread `openAudio` arrives on; the samples travel on the carrier
     * thread. A pipe that is still open is closed, since the player let go of it by asking
     * again.
     */
    fun handOver(end: OutputStream) {
        val replaced =
            synchronized(lock) {
                val letGo = pipe
                pipe = end
                if (provider != null && !pumping) startPump()
                letGo
            }
        replaced?.let { runCatching(it::close) }
    }

    /**
     * The backend is rendering: [provider]'s samples cross from now on.
     *
     * The pipe and the provider arrive in either order, and whichever is second starts the
     * carrier thread.
     */
    override fun start(provider: PcmProvider) {
        synchronized(lock) {
            this.provider = provider
            if (pipe != null && !pumping) startPump()
        }
    }

    /** Nothing more is coming: the pipe is closed, which the player reads as the end. */
    override fun stop() {
        synchronized(lock) { provider = null }
        end()
    }

    /**
     * Does nothing here. The player pauses its own output; this end then stops being
     * drained, the write blocks, and the engine stops decoding.
     */
    override fun pause() = Unit

    override fun resume() = Unit

    /**
     * A seek, a restart or another track: drops what the engine decoded ahead and closes
     * the pipe, which makes the player drop what has already crossed and ask again.
     *
     * Both happen under the lock, so that the player's next pipe cannot be handed over
     * before the engine's old samples are dropped.
     */
    override fun discard() {
        val ending =
            synchronized(lock) {
                provider?.discard()
                pipe.also { pipe = null }
            }
        ending?.let { runCatching(it::close) }
    }

    /**
     * Ignored: the samples cross raw, and the player's chain applies the equalizer, the
     * balance, the rack and the plug-ins. Applying them here as well would apply them twice.
     */
    override fun setEqualizer(settings: EqSettings) = Unit

    override fun setBalance(balance: Float) = Unit

    override fun setDsp(rack: RackSettings) = Unit

    override fun setPlugins(sources: List<String>) = Unit

    /** Ignored for the same reason: the player's chain applies the gain. */
    override fun setVolume(fraction: Float) = Unit

    /**
     * Ignored: the player's output holds the audio focus, and the player pauses the pack
     * over the wire when it loses it.
     */
    override fun setInterruptions(listener: ((AudioOut.Interruption) -> Unit)?) = Unit

    /** Closes the pipe, which is how the far end learns that this stretch is over. */
    private fun end() {
        val ending = synchronized(lock) { pipe.also { pipe = null } }
        ending?.let { runCatching(it::close) }
    }

    /** Begins a carrier thread. Under [lock], and only where there is none alive. */
    private fun startPump() {
        pumping = true
        Thread({ pump() }, "andamp-pack-audio").apply {
            // the player's render loop cannot make up for samples that leave here late
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    /**
     * Reads the engine and writes the pipe, for as long as there is both.
     *
     * There is one carrier thread at a time, and it outlives the pipe it began on: a read
     * can wait seconds, and a discard in that time closes one pipe while the player opens
     * the next. The waiting thread takes the new pipe when it wakes, so the engine never
     * has two readers.
     */
    private fun pump() {
        val buffer = ByteArray(READ_BYTES)
        while (true) {
            val stretch = stretch() ?: return
            when (carry(stretch, buffer)) {
                Carried.More -> {
                    Unit
                }

                // the player closed its end, or this end was closed: that pipe is done
                Carried.PipeGone -> {
                    shut(stretch)
                }

                // the engine will give no more: the pipe is closed so the player is not left
                // waiting, and nothing is carried until the backend calls start again
                Carried.NoMore -> {
                    synchronized(lock) { if (provider === stretch.source) provider = null }
                    shut(stretch)
                }
            }
        }
    }

    /** Both ends as they are now, or null when either is missing, which ends this thread. */
    private fun stretch(): Stretch? =
        synchronized(lock) {
            val source = provider
            val end = pipe
            if (source == null || end == null) {
                pumping = false
                null
            } else {
                Stretch(source, end)
            }
        }

    /** One read from the engine and the write that carries it across. */
    private fun carry(
        stretch: Stretch,
        buffer: ByteArray,
    ): Carried {
        // An engine that throws is treated as one with nothing more to give: an exception
        // escaping this thread would end the process. The pipe closes, which is how the
        // player hears every ending.
        val read = runCatching { stretch.source.read(buffer) }.getOrDefault(-1)
        if (read < 0) return Carried.NoMore
        // Zero means nothing arrived in time. A read may also have waited through a discard
        // that closed the pipe these samples were decoded for; they are from before the
        // jump and are dropped.
        if (read == 0 || !stillOurs(stretch)) return Carried.More
        val wrote = runCatching { stretch.pipe.write(buffer, 0, read) }
        return if (wrote.isSuccess) Carried.More else Carried.PipeGone
    }

    private fun stillOurs(stretch: Stretch): Boolean = synchronized(lock) { pipe === stretch.pipe }

    /** Ends [stretch]'s pipe, unless a newer one has already taken its place. */
    private fun shut(stretch: Stretch) {
        val ours = synchronized(lock) { (pipe === stretch.pipe).also { if (it) pipe = null } }
        if (ours) runCatching(stretch.pipe::close)
    }

    /** The two ends of one stretch of music. */
    private class Stretch(
        val source: PcmProvider,
        val pipe: OutputStream,
    )

    /** What one read and the write after it amounted to; see [pump]. */
    private enum class Carried {
        /** Carried, or nothing to carry yet: read again. */
        More,

        /** That pipe cannot be written any more. */
        PipeGone,

        /** The engine says there will be no more samples at all. */
        NoMore,
    }

    private companion object {
        /**
         * About 93 ms of audio at 44.1 kHz stereo. A pipe holds 64 KiB by default, which is
         * four of these, so the engine is a fraction of a second ahead of what has crossed.
         */
        const val READ_BYTES = 16_384
    }
}
