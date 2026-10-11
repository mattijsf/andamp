// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.cos
import kotlin.math.sin

// Oscilloscope Star and Rotating Stars. Transcribed from the vis_avs source
// (BSD 3-clause, Nullsoft); see NOTICE.md.

/**
 * A five-armed star whose arms are the waveform, turning.
 *
 * Transcribed from vis_avs `r_oscstar.cpp`. Each arm is 64 segments out from
 * the center, the sample pushing the arm sideways, with a push that grows
 * along the arm from 1/1024 to nearly 1/128 of the star's size. The five arms
 * read on through the waveform, 64 samples each. A segment with both ends off
 * the frame is skipped.
 */
internal class OscilloscopeStarRenderer(
    private val position: AvsPosition,
    private val colours: List<Int>,
    private val size: Int,
    private val rotation: Int,
) : AvsComponentRenderer {
    private val cycle = ColourCycle(colours)
    private var turned = 0.0

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (colours.isEmpty()) return
        val colour = cycle.next()
        val w = frame.width
        val h = frame.height
        val reach = minOf((h * (size / SIZE_STEPS)).toInt(), (w * (size / SIZE_STEPS)).toInt())
        val centreX =
            when (position) {
                AvsPosition.LEFT -> w / 4
                AvsPosition.RIGHT -> w / 2 + w / 4
                else -> w / 2
            }
        var sample = 0
        for (arm in 0 until ARMS) {
            val s = sin(turned + arm * (AVS_PI * 2.0 / ARMS))
            val c = cos(turned + arm * (AVS_PI * 2.0 / ARMS))
            var along = 0.0
            var lastX = centreX
            var lastY = h / 2
            val step = reach / SEGMENTS.toDouble()
            var push = PUSH_START
            repeat(SEGMENTS) {
                val aside = (waveByte(audio, sample) - SILENCE) * push * reach
                sample++
                val x = centreX + (c * along).toInt() - (s * aside).toInt()
                val y = h / 2 + (s * along).toInt() + (c * aside).toInt()
                if (frame.holds(x, y) || frame.holds(lastX, lastY)) {
                    AvsDraw.line(frame, x, y, lastX, lastY, colour, state.lineSize, state.renderBlend, state.renderAdjust)
                }
                lastX = x
                lastY = y
                along += step
                push -= (PUSH_START - PUSH_END) / SEGMENTS
            }
        }
        turned += TURN_STEP * rotation
        if (turned >= AVS_PI * 2) turned -= AVS_PI * 2
    }

    companion object {
        private const val ARMS = 5
        private const val SEGMENTS = 64
        private const val SIZE_STEPS = 32.0
        private const val SILENCE = 128
        private const val PUSH_START = 1.0 / 1024.0
        private const val PUSH_END = 1.0 / 128.0
        private const val TURN_STEP = 0.01
        private const val AVS_PI = 3.14159

        /** Waveform, center channel, center of the frame. */
        private const val DEFAULT_EFFECT = (2 shl 2) or (2 shl 4)
        private const val DEFAULT_SIZE = 8
        private const val DEFAULT_ROTATION = 3

        fun read(body: ByteArray): OscilloscopeStarRenderer {
            val reader = BodyReader(body)
            val effect = reader.int32Or(DEFAULT_EFFECT)
            val colours = reader.colourListOr(whenTooMany = 1)
            val size = reader.int32Or(DEFAULT_SIZE)
            val rotation = reader.int32Or(DEFAULT_ROTATION)
            val position =
                when (effect shr 4) {
                    2 -> AvsPosition.CENTER
                    0 -> AvsPosition.LEFT
                    else -> AvsPosition.RIGHT
                }
            return OscilloscopeStarRenderer(position, colours, size, rotation)
        }
    }
}

private fun AvsFrame.holds(
    x: Int,
    y: Int,
) = x in 0 until width && y in 0 until height

/**
 * Two five-pointed stars circling the center opposite each other, each sized
 * by the loudest peak in the low end of the spectrum.
 *
 * Transcribed from vis_avs `r_rotstar.cpp`. A star is one line drawn through
 * every second point of a pentagon. The peak is the largest of bins 3 to 13
 * that stands more than 4 above both its neighbors; AVS reads those bins as
 * signed bytes, so a bin above 127 counts as negative here too.
 */
internal class RotatingStarsRenderer(
    private val colours: List<Int>,
) : AvsComponentRenderer {
    private val cycle = ColourCycle(colours)
    private var angle = 0.0

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (colours.isEmpty()) return
        val colour = cycle.next()
        val w = frame.width
        val h = frame.height
        val orbitX = (cos(angle) * w / 4.0).toInt()
        val orbitY = (sin(angle) * h / 4.0).toInt()
        val peak = peak(audio)
        val reachX = w / 8.0 * (peak + PEAK_FLOOR) / PEAK_SCALE
        val reachY = h / 8.0 * (peak + PEAK_FLOOR) / PEAK_SCALE
        for (star in 0 until 2) {
            val centreX = w / 2 + if (star == 1) -orbitX else orbitX
            val centreY = h / 2 + if (star == 1) -orbitY else orbitY
            var point = -angle
            var lastX = centreX + (cos(point) * reachX).toInt()
            var lastY = centreY + (sin(point) * reachY).toInt()
            point += POINT_STEP
            repeat(POINTS) {
                val x = (cos(point) * reachX + centreX).toInt()
                val y = (sin(point) * reachY + centreY).toInt()
                point += POINT_STEP
                AvsDraw.line(frame, lastX, lastY, x, y, colour, state.lineSize, state.renderBlend, state.renderAdjust)
                lastX = x
                lastY = y
            }
        }
        angle += ORBIT_STEP
    }

    private fun peak(audio: AvsAudioFrame): Int {
        fun bin(index: Int) = specByte(audio, index).toByte().toInt()
        var peak = 0
        for (index in FIRST_BIN..LAST_BIN) {
            val here = bin(index)
            if (here > peak && here > bin(index + 1) + STAND_OUT && here > bin(index - 1) + STAND_OUT) peak = here
        }
        return peak
    }

    companion object {
        private const val POINTS = 5
        private const val POINT_STEP = 3.14159 * 4.0 / 5.0
        private const val ORBIT_STEP = 0.1
        private const val FIRST_BIN = 3
        private const val LAST_BIN = 13
        private const val STAND_OUT = 4
        private const val PEAK_FLOOR = 9
        private const val PEAK_SCALE = 88.0

        fun read(body: ByteArray) = RotatingStarsRenderer(BodyReader(body).colourListOr(whenTooMany = 0))
    }
}
