// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

// Video Delay and Multi Delay: frames held back and shown later. Transcribed
// from the vis_avs source (`r_videodelay.cpp`, `e_multidelay.cpp`; BSD
// 3-clause, see NOTICE.md).

/**
 * A line of frames: what goes in comes out [length] frames later. A line that
 * changes length or frame size starts again from black.
 */
internal class DelayLine {
    private var frames: Array<IntArray> = emptyArray()
    private var at = 0

    val length get() = frames.size

    fun resize(
        length: Int,
        pixels: Int,
    ) {
        if (frames.size == length && (length == 0 || frames[0].size == pixels)) return
        frames = Array(length) { IntArray(pixels) { AvsFrame.OPAQUE } }
        at = 0
    }

    /** The oldest frame, the one about to be replaced. */
    fun oldest(): IntArray = frames[at]

    /** The slot the newest frame goes in. With [aheadOfOldest] it is the slot before the oldest. */
    fun newest(aheadOfOldest: Boolean): IntArray = frames[if (aheadOfOldest) (at + frames.size - 1) % frames.size else at]

    fun advance() {
        if (frames.isNotEmpty()) at = (at + 1) % frames.size
    }
}

/**
 * Shows the frame from a number of frames ago, or from a number of beats ago.
 *
 * In beats, the delay is the frames between the last two beats times the
 * setting, measured again on every beat and capped at 400 frames.
 */
internal class VideoDelayRenderer(
    private val enabled: Boolean,
    private val inBeats: Boolean,
    private val delay: Int,
) : AvsComponentRenderer {
    private val line = DelayLine()
    private var frameDelay = if (inBeats) DEFAULT_FRAMES else delay
    private var framesSinceBeat = 0
    private var held = IntArray(0)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (inBeats) {
            if (state.beat) {
                frameDelay = (framesSinceBeat * delay).coerceAtMost(MAX_FRAMES)
                framesSinceBeat = 0
            }
            framesSinceBeat++
        }
        if (!enabled || frameDelay <= 0) return
        val pixels = frame.pixels
        line.resize(frameDelay, pixels.size)
        if (held.size != pixels.size) held = IntArray(pixels.size)
        val slot = line.oldest()
        slot.copyInto(held)
        pixels.copyInto(slot)
        held.copyInto(pixels)
        line.advance()
    }

    companion object {
        private const val DEFAULT_FRAMES = 10
        private const val MAX_FRAMES = 400
        private const val MAX_BEATS = 16
        private const val MAX_SETTING = 200

        fun read(body: ByteArray): VideoDelayRenderer {
            val reader = BodyReader(body)
            val enabled = reader.int32Or(1) == 1
            val inBeats = reader.int32Or(0) == 1
            val delay = reader.int32Or(DEFAULT_FRAMES).coerceIn(0, if (inBeats) MAX_BEATS else MAX_SETTING)
            return VideoDelayRenderer(enabled, inBeats, delay)
        }
    }
}

/**
 * The six delay lines every Multi Delay of a preset shares. One Multi Delay
 * stores a frame in a line and another shows what that line held a while ago.
 *
 * The lines move once a frame, whichever Multi Delay runs first in it, and a
 * line counted in beats is as long as the frames between the last two beats.
 */
internal class MultiDelayBank {
    private val lines = Array(LINES) { DelayLine() }
    private val inBeats = BooleanArray(LINES)
    private val delay = IntArray(LINES)
    private var seenFrame = -1L
    private var framesSinceBeat = 0
    private var framesPerBeat = 0

    /** The settings one of the preset's Multi Delays carries for all of them. */
    fun configure(settings: List<Pair<Boolean, Int>>) {
        settings.take(LINES).forEachIndexed { index, (beats, frames) ->
            inBeats[index] = beats
            delay[index] = frames
        }
    }

    /** Line [index], moved on for this frame; null when it holds nothing back. */
    fun line(
        index: Int,
        state: AvsRenderState,
        pixels: Int,
    ): DelayLine? {
        if (state.frameNumber != seenFrame) {
            if (seenFrame >= 0) lines.forEach { it.advance() }
            seenFrame = state.frameNumber
            if (state.beat) {
                framesPerBeat = framesSinceBeat
                framesSinceBeat = 0
            }
            framesSinceBeat++
            for (i in 0 until LINES) {
                val frames = (if (inBeats[i]) framesPerBeat else delay[i]).coerceIn(0, MAX_FRAMES)
                // a line of one frame would hand back the frame just stored
                lines[i].resize(if (frames > 0) frames + 1 else 0, pixels)
            }
        }
        return lines.getOrNull(index)?.takeIf { it.length > 1 }
    }

    companion object {
        const val LINES = 6
        private const val MAX_FRAMES = 200
    }
}

/**
 * Stores the frame in one of the preset's six delay lines, or replaces the
 * frame with what that line held its delay ago.
 */
internal class MultiDelayRenderer(
    private val mode: Mode,
    private val line: Int,
    private val settings: List<Pair<Boolean, Int>>,
) : AvsComponentRenderer {
    internal enum class Mode { OFF, STORE, SHOW }

    private var configured = false

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        val bank = state.multiDelay
        if (!configured) {
            configured = true
            if (settings.isNotEmpty()) bank.configure(settings)
        }
        // every Multi Delay moves the lines on, also one that is switched off
        val delayLine = bank.line(line, state, frame.pixels.size) ?: return
        when (mode) {
            Mode.SHOW -> delayLine.oldest().copyInto(frame.pixels)
            Mode.STORE -> frame.pixels.copyInto(delayLine.newest(aheadOfOldest = true))
            Mode.OFF -> Unit
        }
    }

    companion object {
        fun read(body: ByteArray): MultiDelayRenderer {
            val reader = BodyReader(body)
            val mode =
                when (reader.int32Or(0)) {
                    1 -> Mode.STORE
                    2 -> Mode.SHOW
                    else -> Mode.OFF
                }
            val line = reader.int32Or(0)
            // only the first Multi Delay of a preset carries the six lines' settings
            val settings = mutableListOf<Pair<Boolean, Int>>()
            while (settings.size < MultiDelayBank.LINES && reader.has(2 * INT_SIZE)) {
                settings += (reader.int32() == 1) to reader.int32()
            }
            return MultiDelayRenderer(mode, line, settings)
        }

        private const val INT_SIZE = 4
    }
}
