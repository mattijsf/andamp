// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import nl.mattix.andamp.core.playback.PcmProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One track's audio, fetched over HTTP from a music server and decoded into a
 * [PcmProvider].
 *
 * It uses [MediaExtractor] and [MediaCodec] directly, in synchronous mode on a thread of
 * its own. Back-pressure is a blocking write into a bounded pipe ([PcmPipe]), and a seek is
 * the extractor's seek, a codec flush and a discard on that one thread.
 *
 * A track opened to follow the one playing does its network work at once (the request, the
 * container probe, the first packet) and then waits on its [DecoderTurn] before it creates
 * a codec. The track before it gives the turn when it has decoded its last frame, so a
 * backend holds one decoder at a time. The codec is a software one where the phone has one
 * for the format; see [DecoderChoice].
 *
 * Nothing leaves this class as an exception. Every failure ends the provider with -1 and
 * is logged once, and [ending] says whether the track finished ([Ending.Finished]), cannot
 * be played ([Ending.Broke]) or lost its connection ([Ending.Dropped]). The extractor
 * throws the same exception for the last two when a stream does not open, so the server is
 * asked which it was; see [ServerAnswer]. A stream the extractor stops reading half way
 * arrives as [Ending.Finished]; the backend, which knows the track's length, sees that it
 * came too soon.
 *
 * On API 26 the platform's extractor does not read FLAC over http reliably, so a pack
 * whose server can transcode asks it for another format there. This class plays whatever
 * the URL turns out to be.
 *
 * @param url where the audio is, including the credential when the server takes one in the
 *   query string
 * @param headers sent with every request for [url]: a credential for a server that takes
 *   one in a header, or anything a reverse proxy in front of it needs
 * @param startMs where to begin, for a track that is resumed; 0 for a stream the server
 *   already starts at the right place. Audio decoded from the sync point before this
 *   position is dropped, so the start is exact to the sample
 * @param turn shared by every track the same backend opens, so only one of them has a
 *   codec at a time
 * @param seekable whether the server lets [url] be sought in; see [StreamAudio.seekable]
 */
internal class StreamPcm(
    private val url: String,
    private val turn: DecoderTurn,
    private val headers: Map<String, String> = emptyMap(),
    startMs: Long = 0L,
    override val seekable: Boolean = true,
) : StreamAudio {
    /** Where the samples wait for the reader; a full pipe is what paces the decoding. */
    private val pipe = PcmPipe()

    private val shaper = PcmShaper()

    /** The position the decode thread should jump to before its next packet, or [NO_SEEK]. */
    private val wanted = AtomicLong(if (startMs > 0) startMs * MICROS_PER_MILLI else NO_SEEK)

    /**
     * The opening position, before which decoded audio is dropped, or [NO_SEEK] once it
     * has been reached. Used only on the decode thread; see [lead].
     */
    private var trimUntilUs = if (startMs > 0) startMs * MICROS_PER_MILLI else NO_SEEK

    private val begun = AtomicBoolean(false)

    @Volatile
    private var released = false

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var extractor: MediaExtractor? = null

    @Volatile
    private var codec: MediaCodec? = null

    /**
     * Why there will be no more audio, or null while there still might be. It is set just
     * before the pipe is ended, so a caller that has read -1 finds it set.
     */
    @Volatile
    override var ending: Ending? = null
        private set

    /**
     * Opens the URL and starts decoding, on its own thread. Idempotent, and returns this.
     *
     * Separate from construction because opening an http URL is a network call.
     */
    override fun start(): StreamPcm {
        if (begun.compareAndSet(false, true)) {
            worker =
                Thread({ work() }, THREAD_NAME).apply {
                    // the player's render loop cannot make up for samples that are decoded late
                    priority = Thread.MAX_PRIORITY
                    isDaemon = true
                    start()
                }
        }
        return this
    }

    override fun read(into: ByteArray): Int = pipe.read(into)

    override fun discard() = pipe.discard()

    /**
     * Start decoding again from [positionMs].
     *
     * What is already buffered is dropped at once, on the caller's thread, which also
     * wakes the decode thread if it was waiting on a full pipe. Only the newest position
     * counts: a second seek made before the first was applied replaces it.
     */
    override fun seekTo(positionMs: Long) {
        wanted.set(positionMs.coerceAtLeast(0) * MICROS_PER_MILLI)
        pipe.discard()
    }

    /**
     * Stops decoding, releases the codec and the extractor, and unblocks anybody waiting
     * in [read] or for the [turn].
     *
     * It waits up to [JOIN_MS] for the decode thread to finish, so that the next track
     * finds the codec released. A release called from the decode thread itself does not
     * wait.
     *
     * A thread still alive after the wait is stuck inside the codec, and the turn is given
     * on its behalf. Two codecs can then exist at once; otherwise every later track would
     * wait for the turn forever.
     */
    override fun release() {
        released = true
        pipe.close()
        turn.wake()
        val thread = worker
        worker = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) turn.give(this)
        }
    }

    /**
     * The decode thread, start to finish.
     *
     * Every failure becomes an [Ending]. An `IOException` (a codec that cannot be created),
     * an `IllegalStateException` (a codec that failed mid-track; `MediaCodec.CodecException`
     * is one), an `IllegalArgumentException` (a format the codec would not accept), any
     * other `RuntimeException` and an `InterruptedException` are all [Ending.Broke]. A
     * stream that does not open is answered in [decode], because that failure may be the
     * network.
     */
    private fun work() {
        val ended =
            try {
                decode()
            } catch (e: IOException) {
                broke(e)
            } catch (e: IllegalStateException) {
                broke(e)
            } catch (e: IllegalArgumentException) {
                broke(e)
            } catch (
                // any other exception is still this one track's; uncaught on this thread
                // it would end the pack's process
                @Suppress("TooGenericExceptionCaught")
                e: RuntimeException,
            ) {
                Log.w(TAG, "stream audio failed in a way nobody expected", e)
                broke(e)
            } catch (e: InterruptedException) {
                broke(e)
            }
        letGo()
        finish(ended)
    }

    /**
     * Opens, waits its turn, configures and runs; answers how it ended, or null when it
     * was released.
     *
     * Everything before [DecoderTurn.take] is network work and needs no codec, so a track
     * opened early does it early.
     */
    private fun decode(): Ending? {
        val source = MediaExtractor().also { extractor = it }
        val refused = open(source)
        if (refused != null) return refused.takeUnless { released }
        val format = chooseTrack(source) ?: return Ending.Broke(NO_AUDIO)
        return decode(source, format)
    }

    /** The half of [decode] after the stream has opened and its audio track is chosen. */
    private fun decode(
        source: MediaExtractor,
        format: MediaFormat,
    ): Ending? {
        prime(source)
        if (!turn.take(this) { released }) return null
        reshape(format)
        val decoder = decoderFor(format.getString(MediaFormat.KEY_MIME).orEmpty())
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()
        return pump(source, decoder)
    }

    /**
     * Opens [url] in [source]; null when it opened, and the reason when it did not.
     *
     * This is the one failure that may be the network, and the extractor does not say
     * which; see [ServerAnswer]. When the provider was released while opening, the caller
     * drops the answer.
     */
    private fun open(source: MediaExtractor): Ending? =
        try {
            source.setDataSource(url, headers)
            null
        } catch (e: IOException) {
            if (released) Ending.Broke(RELEASED) else ServerAnswer.about(url, headers, e)
        }

    /**
     * Moves to where the track starts and pulls its first packet from the server, before
     * there is a codec.
     *
     * Asking the extractor for the sample time makes it read the next packet, which it
     * keeps for the first `readSampleData`. The seek goes to the extractor alone, because
     * there is no codec to flush yet; a seek asked for after this is applied by
     * [reposition].
     */
    private fun prime(source: MediaExtractor) {
        val target = wanted.getAndSet(NO_SEEK)
        if (target != NO_SEEK) source.seekTo(target, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        source.sampleTime
    }

    /**
     * The decoder [DecoderChoice] picks for [mime], or the platform's own pick when it
     * picks none or the one it picked cannot be created.
     */
    private fun decoderFor(mime: String): MediaCodec {
        val chosen = DecoderChoice.pick(DecoderChoice.installed(mime)) ?: return MediaCodec.createDecoderByType(mime)
        return try {
            MediaCodec.createByCodecName(chosen)
        } catch (e: IOException) {
            Log.w(TAG, "decoder $chosen would not be created, leaving the choice to the platform: ${e.message}")
            MediaCodec.createDecoderByType(mime)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "decoder $chosen would not be created, leaving the choice to the platform: ${e.message}")
            MediaCodec.createDecoderByType(mime)
        }
    }

    /**
     * Selects the first audio track and answers its format, or null when the URL holds no
     * audio. A server's stream response is a music file with one audio track.
     */
    private fun chooseTrack(source: MediaExtractor): MediaFormat? {
        for (index in 0 until source.trackCount) {
            val format = source.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith(AUDIO) == true) {
                source.selectTrack(index)
                return format
            }
        }
        return null
    }

    /**
     * Feed, drain, repeat, until the file ends or the provider is released.
     *
     * There is no sleep and no poll: with nothing ready the dequeue calls wait on the
     * codec for [TIMEOUT_US], and with a full pipe the write inside [carry] waits.
     */
    private fun pump(
        source: MediaExtractor,
        decoder: MediaCodec,
    ): Ending? {
        val info = MediaCodec.BufferInfo()
        var fed = false
        while (!released) {
            if (reposition(source, decoder)) fed = false
            if (!fed) fed = feed(source, decoder)
            when (drain(decoder, info)) {
                Drained.More -> Unit
                Drained.Ended -> return Ending.Finished
                Drained.Gone -> return null
            }
        }
        return null
    }

    /**
     * Applies a pending seek, if there is one; answers whether it moved.
     *
     * The extractor moves, the codec is flushed, the pipe is emptied and the resampler's
     * history is reset, so nothing decoded for the old position is left. The extractor
     * seeks to the nearest sync point, because a compressed frame cannot be decoded from
     * its middle.
     */
    private fun reposition(
        source: MediaExtractor,
        decoder: MediaCodec,
    ): Boolean {
        val target = wanted.getAndSet(NO_SEEK)
        if (target == NO_SEEK) return false
        // only the opening position is trimmed to the sample; a seek within the track is not
        trimUntilUs = NO_SEEK
        source.seekTo(target, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        decoder.flush()
        pipe.discard()
        shaper.reset()
        return true
    }

    /**
     * Offers the codec one packet from the file; true once the end of the stream has been
     * queued. With no free input buffer the answer is false, and the packet is offered
     * again on the next round.
     */
    private fun feed(
        source: MediaExtractor,
        decoder: MediaCodec,
    ): Boolean {
        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        val buffer = decoder.getInputBuffer(index) ?: return false
        val size = source.readSampleData(buffer, 0)
        return if (size < 0) {
            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            true
        } else {
            decoder.queueInputBuffer(index, 0, size, source.sampleTime, 0)
            source.advance()
            false
        }
    }

    /** Takes one output buffer, if there is one, and says what it meant for the loop. */
    private fun drain(
        decoder: MediaCodec,
        info: MediaCodec.BufferInfo,
    ): Drained {
        val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // the codec's output format describes the bytes about to arrive, and can
            // differ from the track's format
            reshape(decoder.outputFormat)
            return Drained.More
        }
        if (index < 0) return Drained.More
        val carried = carry(decoder, index, info)
        decoder.releaseOutputBuffer(index, false)
        return when {
            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 -> Drained.Ended

            // a write dropped by a seek carries on; one dropped because the pipe closed
            // does not
            carried || !pipe.isFinished -> Drained.More

            else -> Drained.Gone
        }
    }

    /**
     * Shapes one output buffer to the provider's format and writes it to the pipe, waiting
     * for room; false when the bytes were dropped instead.
     *
     * A full pipe means the reader has not drained it yet, and the decode thread waits
     * here for that.
     */
    private fun carry(
        decoder: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ): Boolean {
        if (info.size <= 0) return true
        val buffer = decoder.getOutputBuffer(index) ?: return true
        val lead = lead(info)
        if (lead >= info.size) return true
        buffer.position(info.offset + lead)
        buffer.limit(info.offset + info.size)
        val bytes = shaper.shape(buffer, info.size - lead)
        return bytes <= 0 || pipe.write(shaper.shaped, 0, bytes)
    }

    /**
     * How many bytes of [info]'s buffer come before the opening position and are not to be
     * heard.
     *
     * An extractor told to start part way starts at the sync point before it. For a track
     * resumed after its connection dropped, that audio has been heard already, so it is
     * cut, for the opening position only.
     *
     * A buffer stamped more than [MAX_LEAD_US] before the target is not cut, because its
     * timestamps cannot be trusted.
     */
    private fun lead(info: MediaCodec.BufferInfo): Int {
        val until = trimUntilUs
        if (until == NO_SEEK) return 0
        if (until - info.presentationTimeUs > MAX_LEAD_US) {
            trimUntilUs = NO_SEEK
            return 0
        }
        val lead = shaper.bytesBefore(info.presentationTimeUs, until, info.size)
        if (lead < info.size) trimUntilUs = NO_SEEK
        return lead
    }

    /** Reads a `MediaFormat` into [PcmShaper.reshape]. */
    private fun reshape(format: MediaFormat) {
        shaper.reshape(
            sampleRate = integer(format, MediaFormat.KEY_SAMPLE_RATE, PcmProvider.SAMPLE_RATE_HZ),
            channelCount = integer(format, MediaFormat.KEY_CHANNEL_COUNT, PcmProvider.CHANNELS),
            pcmEncoding = integer(format, MediaFormat.KEY_PCM_ENCODING, PcmShape.ENCODING_PCM_16BIT),
        )
    }

    /**
     * [key] from [format], or [fallback] when it is absent.
     *
     * `MediaFormat.getInteger` with a default needs API 29 and the packs run from API 26,
     * so the key is checked first. A missing `KEY_PCM_ENCODING` is common and means 16-bit.
     */
    private fun integer(
        format: MediaFormat,
        key: String,
        fallback: Int,
    ): Int = if (format.containsKey(key)) format.getInteger(key) else fallback

    /**
     * Releases the codec, gives the turn and releases the extractor, in that order,
     * without throwing.
     *
     * The turn is given before [finish] ends the pipe, so the next track can start its
     * codec while this track's tail is still in the pipe.
     */
    private fun letGo() {
        codec?.let { decoder ->
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
        codec = null
        turn.give(this)
        extractor?.let { source -> runCatching { source.release() } }
        extractor = null
    }

    /**
     * Publishes how it ended and ends the pipe.
     *
     * [PcmPipe.end] and not [PcmPipe.close], so that what is decoded and not yet read
     * still plays. For a provider that was released the pipe is already closed. This is
     * the only place an ending is logged, so a stream that breaks is logged once.
     */
    private fun finish(ended: Ending?) {
        ending = ended
        if (ended is Ending.Broke) Log.w(TAG, "stream audio stopped: ${ended.why}")
        if (ended is Ending.Dropped) Log.i(TAG, "stream audio lost its connection: ${ended.why}")
        pipe.end()
    }

    private fun broke(cause: Exception): Ending = Ending.Broke(cause.message ?: cause.javaClass.simpleName)

    /** What one turn of [drain] meant. */
    private enum class Drained {
        /** Something happened, or nothing did: go round again. */
        More,

        /** The codec has produced the last of the file. */
        Ended,

        /** Nobody is reading this pipe any more. */
        Gone,
    }

    private companion object {
        const val TAG = "StreamPcm"
        const val THREAD_NAME = "andamp-stream-decode"
        const val AUDIO = "audio/"
        const val NO_SEEK = -1L
        const val MICROS_PER_MILLI = 1_000L

        /**
         * How long a dequeue waits on the codec: short enough that [release] and a seek
         * are acted on promptly, long enough that the loop does not spin.
         */
        const val TIMEOUT_US = 10_000L

        /** A buffer stamped further than this before the opening position is not trimmed; see [lead]. */
        const val MAX_LEAD_US = 2_000_000L

        /** How long [release] waits for the decode thread to finish. */
        const val JOIN_MS = 1_000L

        const val RELEASED = "let go of while it was opening"

        const val NO_AUDIO = "what the server sent has no audio track this phone can read"
    }
}
