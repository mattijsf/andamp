// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Dot Fountain and Dot Plane: dots in three dimensions, seen through one
// camera. Transcribed from the vis_avs source (BSD 3-clause, Nullsoft); see
// NOTICE.md.

/**
 * The camera both components look through. Transcribed from vis_avs
 * `matrix.cpp`: row-major 4x4 matrices, a rotation about one axis in degrees,
 * and a product that applies the right-hand matrix after the left.
 */
internal object DotCamera {
    private const val SIZE = 16
    private const val DEGREES = 3.141592653589f / 180.0f

    /** Height of the turntable above the eye, and how far away it stands. */
    private const val DROP = -20.0f
    private const val DISTANCE = 400.0f

    /** The view of a turntable turned [spin] degrees and tilted [tilt] degrees. */
    fun view(
        spin: Float,
        tilt: Float,
    ): FloatArray {
        val matrix = rotate(axis = 2, degrees = spin)
        multiply(matrix, rotate(axis = 1, degrees = tilt))
        multiply(matrix, translate(0.0f, DROP, DISTANCE))
        return matrix
    }

    private fun rotate(
        axis: Int,
        degrees: Float,
    ): FloatArray {
        val matrix = FloatArray(SIZE)
        val angle = degrees * DEGREES
        matrix[((axis - 1) shl 2) + axis - 1] = 1.0f
        matrix[SIZE - 1] = 1.0f
        val first = axis % 3
        val second = (first + 1) % 3
        val c = cos(angle)
        val s = sin(angle)
        matrix[(first shl 2) + first] = c
        matrix[(first shl 2) + second] = s
        matrix[(second shl 2) + second] = c
        matrix[(second shl 2) + first] = -s
        return matrix
    }

    private fun translate(
        x: Float,
        y: Float,
        z: Float,
    ): FloatArray {
        val matrix = FloatArray(SIZE)
        matrix[0] = 1.0f
        matrix[5] = 1.0f
        matrix[10] = 1.0f
        matrix[15] = 1.0f
        matrix[3] = x
        matrix[7] = y
        matrix[11] = z
        return matrix
    }

    private fun multiply(
        into: FloatArray,
        by: FloatArray,
    ) {
        val before = into.copyOf()
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                var sum = 0.0f
                for (k in 0 until 4) sum += by[row * 4 + k] * before[k * 4 + column]
                into[row * 4 + column] = sum
            }
        }
    }

    /** How large the scene is drawn: 440 units across a 640x480 frame, by the tighter side. */
    fun scale(
        width: Int,
        height: Int,
    ): Float = minOf(width * 440.0f / 640.0f, height * 440.0f / 480.0f)

    /**
     * The 64 colors a dot can take, from five the preset names: sixteen steps
     * from each toward the next, each channel walked in 16.16 fixed point.
     */
    fun colourTable(colours: List<Int>): IntArray {
        val table = IntArray(TABLE)
        for (band in 0 until BANDS) {
            val from = colours[band]
            val to = colours[band + 1]
            val channels = intArrayOf(redOf(from) shl FIXED, greenOf(from) shl FIXED, blueOf(from) shl FIXED)
            val steps =
                intArrayOf(
                    ((redOf(to) - redOf(from)) shl FIXED) / STEPS,
                    ((greenOf(to) - greenOf(from)) shl FIXED) / STEPS,
                    ((blueOf(to) - blueOf(from)) shl FIXED) / STEPS,
                )
            for (step in 0 until STEPS) {
                table[band * STEPS + step] = pixelOf(channels[0] shr FIXED, channels[1] shr FIXED, channels[2] shr FIXED)
                for (channel in channels.indices) channels[channel] += steps[channel]
            }
        }
        return table
    }

    /** Rotation speed, the five colors, the tilt and the starting turn, with what AVS starts a new one on. */
    fun read(body: ByteArray): Settings {
        val reader = BodyReader(body)
        val speed = reader.int32Or(DEFAULT_SPEED)
        val colours = DEFAULT_COLOURS.map { reader.int32Or(it) }
        val tilt = reader.int32Or(DEFAULT_TILT)
        val turned = reader.int32Or(0) / TURN_UNITS
        return Settings(speed, colours.map(AvsFrame::fromConfig), tilt, turned)
    }

    class Settings(
        val speed: Int,
        val colours: List<Int>,
        val tilt: Int,
        val turned: Float,
    )

    /** A turn of [speed] fifths of a degree, kept inside one revolution. */
    fun turn(
        angle: Float,
        speed: Int,
    ): Float {
        var next = angle + speed / 5.0f
        if (next >= REVOLUTION) next -= REVOLUTION
        if (next < 0.0f) next += REVOLUTION
        return next
    }

    const val TABLE = 64
    private const val BANDS = 4
    private const val FIXED = 16
    private const val STEPS = 16
    private const val DEFAULT_SPEED = 16
    private const val DEFAULT_TILT = -20
    private const val TURN_UNITS = 32.0f
    private const val REVOLUTION = 360.0f
    private val DEFAULT_COLOURS = listOf(0x1C6B18, 0xFF0A23, 0x2A1D74, 0x9036D9, 0x6B88FF)
}

/**
 * A fountain of dots thrown up from a turning ring, the waveform setting how
 * hard each jet throws.
 *
 * Transcribed from vis_avs `r_dotfnt.cpp`. Thirty jets stand in a ring. Each
 * frame every jet releases a dot, and the dots already in the air move on:
 * they fall faster, and drift outward faster the older they are. A dot lives
 * 256 frames. Its color is fixed when it leaves, from how hard it was thrown.
 */
internal class DotFountainRenderer(
    private val settings: DotCamera.Settings,
) : AvsComponentRenderer {
    private val colourTable = DotCamera.colourTable(settings.colours)
    private var turned = settings.turned

    // one row per age, youngest first
    private val radius = FloatArray(AGES * JETS)
    private val outward = FloatArray(AGES * JETS)
    private val heightOf = FloatArray(AGES * JETS)
    private val falling = FloatArray(AGES * JETS)

    // a dot that has not been thrown yet is black, and is drawn
    private val colour = IntArray(AGES * JETS) { AvsFrame.OPAQUE }
    private val jetX = FloatArray(JETS) { sin(it * AVS_PI * 2.0f / JETS) }
    private val jetZ = FloatArray(JETS) { cos(it * AVS_PI * 2.0f / JETS) }

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        age()
        release(audio, state.beat)
        draw(frame, state)
        turned = DotCamera.turn(turned, settings.speed)
    }

    /** Every dot moves into the next age, having moved one frame. */
    private fun age() {
        for (row in AGES - 2 downTo 0) {
            val spread = SPREAD / (row + SPREAD_FROM)
            for (jet in 0 until JETS) {
                val from = row * JETS + jet
                val to = from + JETS
                radius[to] = radius[from] + outward[from]
                falling[to] = falling[from] + GRAVITY
                outward[to] = outward[from] + spread
                heightOf[to] = heightOf[from] + falling[to]
                colour[to] = colour[from]
            }
        }
    }

    private fun release(
        audio: AvsAudioFrame,
        beat: Boolean,
    ) {
        for (jet in 0 until JETS) {
            var force = waveByte(audio, jet) * 5 / 4 - FORCE_FLOOR
            if (beat) force += BEAT_FORCE
            if (force > MAX_FORCE) force = MAX_FORCE
            radius[jet] = 1.0f
            heightOf[jet] = START_HEIGHT
            falling[jet] = -(abs(force / FORCE_UNIT) + 1.0f) * THROW
            outward[jet] = 0.0f
            colour[jet] = colourTable[(force / 4).coerceIn(0, DotCamera.TABLE - 1)]
        }
    }

    private fun draw(
        frame: AvsFrame,
        state: AvsRenderState,
    ) {
        val view = DotCamera.view(turned, settings.tilt.toFloat())
        val scale = DotCamera.scale(frame.width, frame.height)
        for (at in 0 until AGES * JETS) {
            val jet = at % JETS
            val x = jetX[jet] * radius[at]
            val y = heightOf[at]
            val z = jetZ[jet] * radius[at]
            val depth = scale / (x * view[8] + y * view[9] + z * view[10] + view[11])
            if (depth > NEAREST) {
                val px = ((x * view[0] + y * view[1] + z * view[2] + view[3]) * depth).toInt() + frame.width / 2
                val py = ((x * view[4] + y * view[5] + z * view[6] + view[7]) * depth).toInt() + frame.height / 2
                AvsDraw.dot(frame, px, py, colour[at], state.renderBlend, state.renderAdjust)
            }
        }
    }

    companion object {
        private const val JETS = 30
        private const val AGES = 256
        private const val AVS_PI = 3.14159f
        private const val SPREAD = 1.3f
        private const val SPREAD_FROM = 100
        private const val GRAVITY = 0.05f
        private const val FORCE_FLOOR = 64
        private const val BEAT_FORCE = 128
        private const val MAX_FORCE = 255
        private const val FORCE_UNIT = 200.0f
        private const val THROW = 2.8f
        private const val START_HEIGHT = 250.0f
        private const val NEAREST = 0.0000001f

        fun read(body: ByteArray) = DotFountainRenderer(DotCamera.read(body))
    }
}

/**
 * A square sheet of dots that the spectrum lifts along one edge, the lift
 * rolling across the sheet and settling.
 *
 * Transcribed from vis_avs `r_dotpln.cpp`. The sheet is 64 by 64. Each frame
 * every row takes the heights of the row before it plus that row's speed, and
 * a row's speed is pulled back toward flat in proportion to its height. The
 * first row is set from the loudest of each three spectrum bins. Rows and
 * dots are drawn far to near for the way the sheet is turned.
 */
internal class DotPlaneRenderer(
    private val settings: DotCamera.Settings,
) : AvsComponentRenderer {
    private val colourTable = DotCamera.colourTable(settings.colours)
    private var turned = settings.turned
    private val heights = FloatArray(SIDE * SIDE)
    private val speeds = FloatArray(SIDE * SIDE)
    private val colours = IntArray(SIDE * SIDE) { AvsFrame.OPAQUE }

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        roll(audio)
        draw(frame, state)
        turned = DotCamera.turn(turned, settings.speed)
    }

    private fun roll(audio: AvsAudioFrame) {
        val firstRow = heights.copyOfRange(0, SIDE)
        for (row in SIDE - 2 downTo 0) {
            for (column in 0 until SIDE) {
                val from = row * SIDE + column
                val to = from + SIDE
                val risen = (heights[from] + speeds[from]).coerceAtLeast(0.0f)
                heights[to] = risen
                speeds[to] = speeds[from] - SPRING * (risen / LEVELS)
                colours[to] = colours[from]
            }
        }
        for (column in 0 until SIDE) {
            val bin = column * BINS_EACH
            val level = maxOf(specByte(audio, bin), specByte(audio, bin + 1), specByte(audio, bin + 2))
            heights[column] = level.toFloat()
            colours[column] = colourTable[(level shr 2).coerceAtMost(DotCamera.TABLE - 1)]
            speeds[column] = (level - firstRow[column]) / SETTLE
        }
    }

    private fun draw(
        frame: AvsFrame,
        state: AvsRenderState,
    ) {
        val view = DotCamera.view(turned, settings.tilt.toFloat())
        val scale = DotCamera.scale(frame.width, frame.height)
        val spacing = EXTENT / SIDE
        for (step in 0 until SIDE) {
            val row = if (turned < QUARTER || turned > THREE_QUARTERS) SIDE - step - 1 else step
            val z = (row - SIDE * 0.5f) * spacing
            val mirrored = turned < HALF
            for (dot in 0 until SIDE) {
                val column = if (mirrored) SIDE - 1 - dot else dot
                val x = if (mirrored) SIDE * 0.5f * spacing - spacing * (dot + 1) else -(SIDE * 0.5f) * spacing + spacing * dot
                val y = LIFT - heights[row * SIDE + column]
                val depth = scale / (x * view[8] + y * view[9] + z * view[10] + view[11])
                val px = ((x * view[0] + y * view[1] + z * view[2] + view[3]) * depth).toInt() + frame.width / 2
                val py = ((x * view[4] + y * view[5] + z * view[6] + view[7]) * depth).toInt() + frame.height / 2
                AvsDraw.dot(frame, px, py, colours[row * SIDE + column], state.renderBlend, state.renderAdjust)
            }
        }
    }

    companion object {
        private const val SIDE = 64
        private const val BINS_EACH = 3
        private const val SPRING = 0.15f
        private const val LEVELS = 255.0f
        private const val SETTLE = 90.0f
        private const val EXTENT = 350.0f
        private const val LIFT = 64.0f
        private const val QUARTER = 90.0f
        private const val HALF = 180.0f
        private const val THREE_QUARTERS = 270.0f

        fun read(body: ByteArray) = DotPlaneRenderer(DotCamera.read(body))
    }
}
