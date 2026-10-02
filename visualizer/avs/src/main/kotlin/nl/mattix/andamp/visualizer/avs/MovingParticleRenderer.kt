// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One filled dot, chasing where the last beat sent it.
 *
 * Transcribed from vis_avs `e_movingparticle.cpp` (BSD; see NOTICE.md). The
 * motion is a lightly damped spring: every beat picks a new attractor within
 * a third of a unit of the center, and each frame the velocity leans toward it
 * (`v -= 0.004 * (p - c)`), moves the particle, and loses one part in ~110
 * (`v *= 0.991`), so the particle orbits and overshoots its target. Screen
 * position is `p` times `min(h/2, 3w/8) * distance/32`. Size is the disc's
 * diameter in pixels (1..128, a size of one is a single pixel); a beat snaps
 * it to the beat size and it halves its way back toward the resting size, one
 * frame at a time.
 */
internal class MovingParticleRenderer(
    private val enabled: Boolean,
    private val growOnBeat: Boolean,
    private val colour: Int,
    private val distance: Int,
    private val size: Int,
    private val beatSize: Int,
    private val blend: AvsBlendMode?,
) : AvsComponentRenderer {
    // the spring's state, with the original's own starting values
    private val target = doubleArrayOf(0.0, 0.0)
    private val velocity = doubleArrayOf(-0.01551, 0.0)
    private val position = doubleArrayOf(-0.6, 0.3)
    private var curSize = size
    private val random = Random(SEED)

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        val c = target
        val v = velocity
        val p = position
        val ss = min(frame.height / 2, frame.width * 3 / 8)

        if (state.beat) {
            c[0] = (random.nextInt(33) - 16) / 48.0
            c[1] = (random.nextInt(33) - 16) / 48.0
        }
        v[0] -= 0.004 * (p[0] - c[0])
        v[1] -= 0.004 * (p[1] - c[1])
        p[0] += v[0]
        p[1] += v[1]
        v[0] *= 0.991
        v[1] *= 0.991

        val xp = (p[0] * ss * (distance / 32.0)).toInt() + frame.width / 2
        var yp = (p[1] * ss * (distance / 32.0)).toInt() + frame.height / 2

        if (state.beat && growOnBeat) curSize = beatSize
        var drawn = curSize
        curSize = (curSize + size) / 2
        val mode = blend ?: state.renderBlend

        if (drawn <= 1) {
            AvsDraw.dot(frame, xp, yp, colour, mode, state.renderAdjust)
            return
        }
        if (drawn > 128) drawn = 128

        val md = drawn * drawn * 0.25
        yp -= drawn / 2
        for (y in 0 until drawn) {
            if (yp + y < 0 || yp + y >= frame.height) continue
            val yd = y - drawn * 0.5
            val half = sqrt(md - yd * yd)
            var xs = (half + 0.99).toInt()
            if (xs < 1) xs = 1
            val xe = min(xp + xs, frame.width)
            var x = (xp - xs).coerceAtLeast(0)
            while (x < xe) {
                AvsDraw.dot(frame, x, yp + y, colour, mode, state.renderAdjust)
                x++
            }
        }
    }

    companion object {
        private const val ENABLED_BIT = 0x01
        private const val ON_BEAT_BIT = 0x02
        private const val SEED = 0x70617274L

        fun read(body: ByteArray): MovingParticleRenderer? {
            val reader = BodyReader(body)
            val flags = reader.int32()
            val colour = AvsFrame.fromConfig(reader.int32())
            val distance = reader.int32()
            val size = reader.int32()
            val beatSize = reader.int32()
            // the same four-value list Dot Grid uses: 3 means the render mode
            val blend = shortBlendOrDefault(reader.int32())
            return if (reader.ok) {
                MovingParticleRenderer(
                    enabled = flags and ENABLED_BIT != 0,
                    growOnBeat = flags and ON_BEAT_BIT != 0,
                    colour = colour,
                    distance = distance,
                    size = size,
                    beatSize = beatSize,
                    blend = blend,
                )
            } else {
                null
            }
        }
    }
}
