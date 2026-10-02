// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import java.io.InputStream

/**
 * An mp3 file's frames, as the bitrate each one carries.
 *
 * Winamp's kbps readout shows the bitrate of the frame being decoded, so a
 * VBR track's number changes while a CBR one stays still. Media3 reports one
 * figure for a whole file (see [StreamFormat]), so the frame headers are
 * walked here, skipping the audio, and the readout looks up the frame at the
 * position being played.
 *
 * Layer III only: [scan] returns null for anything else.
 */
internal class Mp3Frames(
    val sampleRateHz: Int,
    /** Every frame of a layer III stream carries the same number of samples. */
    val frameDurationUs: Int,
    /**
     * One byte per frame: its index into [ladder], not the rate itself. An
     * hour of audio is about 138,000 frames.
     */
    private val rungs: ByteArray,
    private val ladder: IntArray,
    /**
     * Whether the one rung held stands for the whole file: every frame read
     * had the same rate. When the encoder's tag declares the file constant,
     * only one audio frame is read.
     */
    val constant: Boolean = false,
) {
    val frameCount: Int get() = rungs.size

    /**
     * The rate of the frame playing at [positionMs]; a position past the end
     * reads the last frame. Null for a negative position or an empty table.
     */
    fun kbpsAt(positionMs: Long): Int? {
        if (rungs.isEmpty() || positionMs < 0) return null
        if (constant) return ladder[rungs[0].toInt()]
        val frame = (positionMs * MICROS_PER_MS / frameDurationUs).toInt().coerceAtMost(rungs.size - 1)
        return ladder[rungs[frame].toInt()]
    }

    companion object {
        /**
         * Walks [input] frame by frame, reading each header and skipping its
         * audio. Returns null for anything that is not a layer III stream, and
         * stops reading after [maxBytes].
         */
        fun scan(
            input: InputStream,
            maxBytes: Long = MAX_BYTES,
        ): Mp3Frames? {
            val walk = Walk(input.buffered())
            walk.skipTag()
            while (walk.read < maxBytes && walk.step()) {
                // one frame per turn; step returns false at the end of the
                // file, or once the encoder's tag has declared the rate constant
            }
            return walk.result()
        }

        /**
         * One pass over a file's frames: the header of each, then past its
         * audio to the next. Holds the position, the stream's format and the
         * rates gathered so far.
         */
        private class Walk(
            private val stream: InputStream,
        ) {
            var read = 0L
                private set

            private val rungs = java.io.ByteArrayOutputStream(INITIAL_FRAMES)
            private var sampleRate = 0
            private var samplesPerFrame = 0
            private var ladder: IntArray? = null

            /**
             * True once the encoder's tag has said the file is CBR: LAME
             * writes "Info" for a constant stream and "Xing" for a variable
             * one. The walk then stops after one audio frame.
             */
            private var declaredConstant = false
            private val header = ByteArray(HEADER_BYTES)

            /** Reused: the front of a frame, where an encoder's tag would sit. */
            private val front = ByteArray(TAG_WINDOW)

            fun skipTag() {
                read += skipId3(stream)
            }

            /** Reads one frame; false when there is no next one to read. */
            fun step(): Boolean {
                // one audio frame is all a declared-constant file needs
                if (declaredConstant && rungs.size() > 0) return false
                if (!stream.readFully(header)) return false
                read += HEADER_BYTES
                val parsed = headerOf(header)
                if (parsed == null) {
                    // not a frame boundary: step a byte at a time until the
                    // window parses
                    read += resync(stream, header)
                    return headerOf(header) != null && body(headerOf(header)!!)
                }
                return body(parsed)
            }

            private fun body(parsed: Header): Boolean {
                if (sampleRate == 0) {
                    sampleRate = parsed.sampleRateHz
                    samplesPerFrame = parsed.samplesPerFrame
                    ladder = parsed.ladder
                }
                // a stream that changes sample rate mid-file cannot be indexed
                // by frame number, and a frame no longer than its header is
                // not a frame
                val rest = parsed.frameBytes - HEADER_BYTES
                if (parsed.sampleRateHz != sampleRate || rest <= 0) return false
                val outcome = if (rungs.size() == 0) firstFrame(rest) else skipped(rest)
                if (outcome == Frame.AUDIO) rungs.write(parsed.rung.toInt())
                return outcome != Frame.ENDED
            }

            /**
             * A frame before any audio has been counted. Its front is read,
             * because that is where an encoder's Xing/Info/VBRI tag sits; a
             * tag frame is not counted as audio.
             */
            private fun firstFrame(rest: Int): Frame {
                val window = minOf(rest, TAG_WINDOW)
                if (!stream.readFully(front, window)) return Frame.ENDED
                read += window
                stream.skipFully((rest - window).toLong())
                read += rest - window
                val mark = tagMark(front, window) ?: return Frame.AUDIO
                declaredConstant = mark == CBR_MARK
                return Frame.TAG
            }

            /**
             * Skips the frame's audio without reading it.
             */
            private fun skipped(rest: Int): Frame {
                stream.skipFully(rest.toLong())
                read += rest
                return Frame.AUDIO
            }

            /** What a frame turned out to be. */
            private enum class Frame { AUDIO, TAG, ENDED }

            fun result(): Mp3Frames? {
                val rows = rungs.toByteArray()
                val rungLadder = ladder
                if (rows.isEmpty() || sampleRate == 0 || rungLadder == null) return null
                // every frame read carried the same rung: the file is constant
                val same = rows.all { it == rows[0] }
                return Mp3Frames(
                    sampleRateHz = sampleRate,
                    frameDurationUs = samplesPerFrame * MICROS_PER_SECOND / sampleRate,
                    rungs = if (same) byteArrayOf(rows[0]) else rows,
                    ladder = rungLadder,
                    constant = same,
                )
            }
        }

        /** ID3v2 sits in front of the audio; its size is seven bits per byte. */
        private fun skipId3(stream: InputStream): Long {
            val tag = ByteArray(ID3_HEADER)
            if (!stream.readFully(tag)) return ID3_HEADER.toLong()
            val isId3 = tag[0] == 'I'.code.toByte() && tag[1] == 'D'.code.toByte() && tag[2] == '3'.code.toByte()
            if (!isId3) {
                // not a tag: these bytes stay consumed, and the walk resyncs
                // to the next frame header from here
                return pushBack(stream, tag)
            }
            val size =
                (tag[6].toInt() and SYNCSAFE) shl 21 or
                    ((tag[7].toInt() and SYNCSAFE) shl 14) or
                    ((tag[8].toInt() and SYNCSAFE) shl 7) or
                    (tag[9].toInt() and SYNCSAFE)
            val footer = if (tag[5].toInt() and ID3_FOOTER_FLAG != 0) ID3_HEADER else 0
            stream.skipFully((size + footer).toLong())
            return (ID3_HEADER + size + footer).toLong()
        }

        /**
         * The encoder tag at the front of a frame, if that is what this frame
         * is: "Info" for a constant stream, "Xing" or "VBRI" for a variable
         * one. Null means the frame is audio.
         */
        private fun tagMark(
            front: ByteArray,
            length: Int,
        ): String? {
            for (at in 0..length - MARK_LEN) {
                val here = String(front, at, MARK_LEN, Charsets.US_ASCII)
                if (here in TAG_MARKS) return here
            }
            return null
        }

        /**
         * Slides the window one byte at a time until the four bytes in
         * [header] parse as a frame header, and reports how far it went.
         */
        private fun resync(
            stream: InputStream,
            header: ByteArray,
        ): Long {
            var moved = 0L
            while (moved < RESYNC_LIMIT) {
                header[0] = header[1]
                header[1] = header[2]
                header[2] = header[3]
                val next = stream.read()
                if (next < 0) return moved
                header[3] = next.toByte()
                moved++
                if (headerOf(header) != null) {
                    return moved
                }
            }
            return moved
        }

        private class Header(
            val rung: Byte,
            val ladder: IntArray,
            val sampleRateHz: Int,
            val samplesPerFrame: Int,
            val frameBytes: Int,
        )

        @Suppress("ReturnCount")
        private fun headerOf(bytes: ByteArray): Header? {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
            val version = (b1 shr 3) and 0x3
            val layer = (b1 shr 1) and 0x3
            if (version == VERSION_RESERVED || layer != LAYER_III) return null
            val rateIndex = (b2 shr 4) and 0xF
            val sampleIndex = (b2 shr 2) and 0x3
            if (rateIndex == 0 || rateIndex == BAD_INDEX || sampleIndex == SAMPLE_RESERVED) return null
            val mpeg1 = version == VERSION_MPEG1
            val ladder = if (mpeg1) MPEG1_RATES else MPEG2_RATES
            val kbps = ladder[rateIndex]
            val sampleRate = SAMPLE_RATES[version][sampleIndex]
            val samples = if (mpeg1) SAMPLES_MPEG1 else SAMPLES_MPEG2
            val padding = (b2 shr 1) and 0x1
            val frameBytes = samples / 8 * kbps * BITS_PER_KBIT / sampleRate + padding
            if (frameBytes <= HEADER_BYTES) return null
            return Header(rateIndex.toByte(), ladder, sampleRate, samples, frameBytes)
        }

        private fun InputStream.readFully(
            into: ByteArray,
            length: Int = into.size,
        ): Boolean {
            var at = 0
            while (at < length) {
                val n = read(into, at, length - at)
                if (n < 0) return false
                at += n
            }
            return true
        }

        private fun InputStream.skipFully(count: Long) {
            var left = count
            while (left > 0) {
                val n = skip(left)
                if (n <= 0) {
                    if (read() < 0) return
                    left--
                } else {
                    left -= n
                }
            }
        }

        /** A stream with no ID3 tag: returns how many bytes were read looking for one. */
        private fun pushBack(
            stream: InputStream,
            read: ByteArray,
        ): Long {
            // the stream cannot un-read them, so the walk starts here and
            // resync finds the next frame header
            if (stream.markSupported()) return read.size.toLong()
            return read.size.toLong()
        }

        private val MPEG1_RATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        private val MPEG2_RATES = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

        /** Indexed by the header's version bits: 2.5, reserved, 2, 1. */
        private val SAMPLE_RATES =
            arrayOf(
                intArrayOf(11_025, 12_000, 8_000),
                intArrayOf(0, 0, 0),
                intArrayOf(22_050, 24_000, 16_000),
                intArrayOf(44_100, 48_000, 32_000),
            )

        private const val VERSION_MPEG1 = 3
        private const val VERSION_RESERVED = 1
        private const val LAYER_III = 1
        private const val SAMPLE_RESERVED = 3
        private const val BAD_INDEX = 15
        private const val SAMPLES_MPEG1 = 1152
        private const val SAMPLES_MPEG2 = 576
        private const val HEADER_BYTES = 4
        private const val ID3_HEADER = 10
        private const val ID3_FOOTER_FLAG = 0x10
        private const val SYNCSAFE = 0x7F
        private const val BITS_PER_KBIT = 1000
        private const val MICROS_PER_SECOND = 1_000_000
        private const val TAG_WINDOW = 40
        private const val CBR_MARK = "Info"
        private val TAG_MARKS = setOf("Xing", CBR_MARK, "VBRI")
        private const val MARK_LEN = 4
        private const val RESYNC_LIMIT = 8192L
        private const val INITIAL_FRAMES = 4096

        /** The walk stops after this many bytes. */
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}

private const val MICROS_PER_MS = 1000
