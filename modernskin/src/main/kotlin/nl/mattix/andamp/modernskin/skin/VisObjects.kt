// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

import nl.mattix.andamp.modernskin.maki.Value
import kotlin.math.abs
import kotlin.math.floor

/**
 * `<vis>`: Winamp's analyzer. It always draws a 72 x 16 picture (18 bars of 3 columns and a gap,
 * or 72 one-column bars when `bandwidth="thin"`) and stretches it over the object, so a wider vis
 * gets wider bars, never more. Rows have their own colors (colorband1 the bottom row, colorband16
 * the top), the classic Winamp table by default. A bar is at most 15 rows high; its peak dot sits
 * on the row above it, so a still bar leaves the dot on the bottom row. Bars fall back gradually
 * and peaks hang, then drop faster and faster, one step every 25 ms. In mode 2 the same picture
 * holds an oscilloscope of the waveform being heard (see [paintScope]).
 */
class Vis(
    rt: SkinRuntime,
) : GuiObject(rt, "vis") {
    override val className: String get() = "Vis"

    /** How the oscilloscope draws its points. */
    private enum class ScopeStyle { DOTS, LINE, FILLED }

    /** Row colors, top row first. */
    private val rows = Array(ROWS) { Rgb(CLASSIC_BANDS[it]) }
    private var peakColor = Rgb(0x959596)

    /** Oscilloscope colors, colorosc1 (the middle rows) to colorosc5 (the outer rows). */
    private val scopeColors = Array(SCOPE_COLORS) { Rgb(CLASSIC_SCOPE[it]) }
    private var gammaGroup: String? = null

    /** 1 spectrum, 2 oscilloscope; any other value shows nothing (MMD3 sets 3 to hide it). */
    private var mode = 1

    /**
     * `oscstyle` as Winamp 5.666 reads it: "dots", "solid" joins the points into a line, and
     * "lines" fills from each point to the middle. A line until a skin says otherwise.
     */
    private var scopeStyle = ScopeStyle.LINE
    private var peaks = true
    private var thin = false
    private var flipV = false
    private var flipH = false
    private var falloff = 2
    private var peakFalloff = 1

    // per data column: bar height in rows, peak height in 1/256 rows, peak speed
    private val bars = FloatArray(COLUMNS)
    private val peak = FloatArray(COLUMNS)
    private val speed = FloatArray(COLUMNS) { PEAK_START_SPEED }
    private var lastFrameAt = -1L

    @Suppress("CyclomaticComplexMethod") // one flat branch per vis attribute a skin can set
    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        val k = key.lowercase()
        when {
            k == "colorallbands" -> {
                parseRgb(value)?.let { c -> rows.fill(c) }
            }

            k.startsWith("colorband") && k != "colorbandpeak" -> {
                k.removePrefix("colorband").toIntOrNull()?.takeIf { it in 1..ROWS }?.let { n ->
                    parseRgb(value)?.let {
                        rows[
                            ROWS -
                                n,
                        ] =
                            it
                    }
                }
            }

            k == "colorbandpeak" -> {
                parseRgb(value)?.let { peakColor = it }
            }

            k == "colorallosc" -> {
                parseRgb(value)?.let { c -> scopeColors.fill(c) }
            }

            k.startsWith("colorosc") -> {
                k.removePrefix("colorosc").toIntOrNull()?.takeIf { it in 1..SCOPE_COLORS }?.let { n ->
                    parseRgb(value)?.let { scopeColors[n - 1] = it }
                }
            }

            k == "oscstyle" -> {
                when (value.trim().lowercase()) {
                    "dots" -> scopeStyle = ScopeStyle.DOTS
                    "solid" -> scopeStyle = ScopeStyle.LINE
                    "lines" -> scopeStyle = ScopeStyle.FILLED
                }
            }

            k == "gammagroup" -> {
                gammaGroup = value
            }

            k == "mode" -> {
                mode = value.toIntLenient()?.takeIf { it in 0..2 } ?: 0
            }

            k == "peaks" -> {
                peaks = value.toBool()
            }

            k == "bandwidth" -> {
                thin = value.equals("thin", ignoreCase = true)
            }

            k == "flipv" -> {
                flipV = value.toBool()
            }

            k == "fliph" -> {
                flipH = value.toBool()
            }

            k == "falloff" -> {
                falloff = value.toIntLenient()?.coerceIn(0, 4) ?: 2
            }

            k == "peakfalloff" -> {
                peakFalloff = value.toIntLenient()?.coerceIn(0, 4) ?: 1
            }

            k.startsWith("color") || k in setOf("coloring", "fps") -> {
                Unit
            }

            else -> {
                return false
            }
        }
        return true
    }

    private fun tint(c: Rgb) = rt.gamma(gammaGroup)?.let { Rgb(it.apply(c.value or OPAQUE) and 0xffffff) } ?: c

    /** Advances bars and peaks by the 25 ms frames since the last draw. */
    private fun step(levels: FloatArray) {
        val now = rt.now()
        val frames = if (lastFrameAt < 0) 1 else ((now - lastFrameAt) / FRAME_MS).toInt().coerceIn(0, MAX_FRAMES)
        if (frames == 0) return
        lastFrameAt = now
        val fall = FALLOFF_STEPS[falloff] / 16f
        val accel = PEAK_ACCEL[peakFalloff]
        repeat(frames) {
            for (i in 0 until COLUMNS) {
                val target =
                    if (levels.isEmpty()) {
                        0f
                    } else {
                        (levels[i * levels.size / COLUMNS] * ROWS).coerceAtMost(
                            MAX_BAR.toFloat(),
                        )
                    }
                bars[i] = if (target >= bars[i]) target else maxOf(target, bars[i] - fall)
                val barPeak = bars[i] * 256
                if (peak[i] <= barPeak) {
                    peak[i] = barPeak
                    speed[i] = PEAK_START_SPEED
                } else {
                    peak[i] = maxOf(barPeak, peak[i] - speed[i].toInt())
                    speed[i] *= accel
                }
            }
        }
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible || rect.width <= 0 || rect.height <= 0) return
        if (mode !in 1..2) return
        val px = IntArray(WIDTH * ROWS)
        if (mode == 2) paintScope(px) else paintSpectrum(px)
        if (rect.width.toLong() * rect.height > ImageLimits.MAX_PIXELS) return
        val scaled = stretch(flip(px, WIDTH, ROWS, flipH, flipV), WIDTH, ROWS, rect.width, rect.height)
        out.add(DrawCmd.Pixels(rect, rect.width, rect.height, scaled, effectiveAlpha))
    }

    /** Steps the bars and peaks on and paints them into [px] ([WIDTH] x [ROWS]). */
    private fun paintSpectrum(px: IntArray) {
        step(rt.player.spectrum())
        val colors = IntArray(ROWS) { tint(rows[it]).value or OPAQUE }
        val dot = tint(peakColor).value or OPAQUE

        fun column(
            x: Int,
            h: Int,
            p: Int,
        ) {
            for (r in 0 until h) px[(ROWS - 1 - r) * WIDTH + x] = colors[ROWS - 1 - r]
            if (peaks) px[(MAX_BAR - p.coerceIn(0, MAX_BAR)) * WIDTH + x] = dot
        }
        if (thin) {
            for (x in 0 until WIDTH) column(x, bars[x].toInt(), (peak[x] / 256).toInt())
        } else {
            for (b in 0 until WIDTH / 4) {
                // a bar is the mean of its four data columns; the fourth column stays empty
                val h = ((0 until 4).sumOf { bars[b * 4 + it].toDouble() } / 4).toInt()
                val p = ((0 until 4).sumOf { peak[b * 4 + it].toDouble() } / 4 / 256).toInt()
                for (x in b * 4 until b * 4 + 3) column(x, h, p)
            }
        }
    }

    /**
     * Paints the oscilloscope into [px] ([WIDTH] x [ROWS]): a point per column from every fourth of
     * the 576 samples Winamp hands a visualizer (a waveform of another length is read at the same
     * share of its length). A point is the sample as an 8-bit value over 8, counted down from
     * [SCOPE_MIDDLE], so silence sits on that row and a positive sample draws below it; with no
     * waveform every point is silence, the flat line of a player at rest. A column takes the color
     * of its point's row: colorosc1 on the middle row and the one below it, the next color every
     * two rows further out.
     */
    private fun paintScope(px: IntArray) {
        val wave = rt.player.waveform()
        val colors = IntArray(SCOPE_COLORS) { tint(scopeColors[it]).value or OPAQUE }
        var last = -1
        for (x in 0 until WIDTH) {
            val sample = if (wave.isEmpty()) 0f else wave[(x.toLong() * wave.size / SCOPE_POINTS).toInt()]
            val row = (floor(sample.coerceIn(-1f, 1f) * SCOPE_ROWS_PER_UNIT).toInt() + SCOPE_MIDDLE).coerceIn(0, ROWS - 1)
            var from = row
            var to = row
            when (scopeStyle) {
                ScopeStyle.DOTS -> {
                    Unit
                }

                // from the last point down to this one; going up, from this one down to the row above the last
                ScopeStyle.LINE -> {
                    if (last in 0 until row) {
                        from = last
                    } else if (row < last) {
                        to = last - 1
                    }
                }

                ScopeStyle.FILLED -> {
                    if (row < SCOPE_MIDDLE) to = SCOPE_MIDDLE - 1 else from = SCOPE_MIDDLE
                }
            }
            val c = colors[abs(row / 2 - SCOPE_MIDDLE / 2)]
            for (r in from..to) px[r * WIDTH + x] = c
            last = row
        }
    }

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "setmode" -> {
                Value.Null.also {
                    mode = args[0].asInt.takeIf { it in 0..2 } ?: 0
                    rt.invalidate()
                }
            }

            "getmode" -> {
                Value.Int(mode)
            }

            "nextmode" -> {
                Value.Null.also { mode = if (mode >= 2) 0 else mode + 1 }
            }

            else -> {
                super.makiCall(name, args)
            }
        }

    private companion object {
        const val WIDTH = 72
        const val ROWS = 16
        const val COLUMNS = 75
        const val MAX_BAR = 15
        const val FRAME_MS = 25L
        const val MAX_FRAMES = 200
        const val PEAK_START_SPEED = 3f
        const val OPAQUE = 0xff000000.toInt()
        val FALLOFF_STEPS = intArrayOf(3, 6, 12, 16, 32)
        val PEAK_ACCEL = floatArrayOf(1.05f, 1.1f, 1.2f, 1.4f, 1.6f)

        /** The oscilloscope's row for silence. */
        const val SCOPE_MIDDLE = 8

        /** Rows per unit of sample: an 8-bit sample (128 to the unit) over 8. */
        const val SCOPE_ROWS_PER_UNIT = 16f

        /** Points across the 576 samples at one per four; the picture shows the first [WIDTH]. */
        const val SCOPE_POINTS = 144
        const val SCOPE_COLORS = 5

        /** Winamp's classic oscilloscope colors, colorosc1 (white) to colorosc5. */
        val CLASSIC_SCOPE = intArrayOf(0xFFFFFF, 0xD6D6DE, 0xB5BDBD, 0xA0AAAF, 0x949CA5)

        /** Winamp's classic analyzer colors, top row (red) to bottom row (green). */
        val CLASSIC_BANDS =
            intArrayOf(
                0xEF3110,
                0xCE2910,
                0xD65A00,
                0xD66600,
                0xD67300,
                0xC67B08,
                0xDEA518,
                0xD6B521,
                0xBDDE29,
                0x94DE21,
                0x29CE10,
                0x32BE10,
                0x39B510,
                0x319C08,
                0x299400,
                0x188408,
            )
    }
}

/**
 * [px] ([w] x [h]) stretched to [dw] x [dh] as Winamp stretches the vis picture: target pixel x
 * takes source column floor(x * w / dw), so the last source row gets a single target row when
 * stretching 16 rows to 25 (Winamp Modern's stopped vis shows 1 px dots).
 */
internal fun stretch(
    px: IntArray,
    w: Int,
    h: Int,
    dw: Int,
    dh: Int,
): IntArray {
    if (dw == w && dh == h) return px
    val out = IntArray(dw * dh)
    for (y in 0 until dh) {
        val sy = (y.toLong() * h / dh).toInt()
        for (x in 0 until dw) out[y * dw + x] = px[sy * w + (x.toLong() * w / dw).toInt()]
    }
    return out
}

/** [px] mirrored left to right and/or top to bottom. */
internal fun flip(
    px: IntArray,
    w: Int,
    h: Int,
    horizontal: Boolean,
    vertical: Boolean,
): IntArray {
    if (!horizontal && !vertical) return px
    val out = IntArray(px.size)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val sx = if (horizontal) w - 1 - x else x
            val sy = if (vertical) h - 1 - y else y
            out[y * w + x] = px[sy * w + sx]
        }
    }
    return out
}

/**
 * `<eqvis>`: the EQ curve. A Kochanek-Bartels spline (tension 0, continuity 0, bias 0.1) through
 * the ten bands, the first and last doubled, drawn column by column as a line from the previous
 * column's height; each row in its own color, from `colortop` to `colormiddle` in the upper half
 * and on to `colorbottom` below. The preamp first, as a full row in `colorpreamp`. A boost goes up.
 */
class EqVis(
    rt: SkinRuntime,
) : GuiObject(rt, "eqvis") {
    override val className: String get() = "EqVis"
    private var top = Rgb(0xffffff)
    private var middle = Rgb(0xffffff)
    private var bottom = Rgb(0xffffff)
    private var preamp = Rgb(0x888888)
    private var gammaGroup: String? = null

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "colortop" -> parseRgb(value)?.let { top = it }

            "colormiddle" -> parseRgb(value)?.let { middle = it }

            "colorbottom" -> parseRgb(value)?.let { bottom = it }

            "colorpreamp" -> parseRgb(value)?.let { preamp = it }

            // on an eqvis, "gamma" is the curve's opacity (Winamp3: gamma="192")
            "gamma" -> alpha = value.toIntLenient()?.coerceIn(0, 255) ?: 255

            "gammagroup" -> gammaGroup = value

            else -> return key.lowercase().startsWith("color")
        }
        return true
    }

    private fun tint(c: Rgb) = rt.gamma(gammaGroup)?.let { Rgb(it.apply(c.value or OPAQUE) and 0xffffff) } ?: c

    private fun mix(
        a: Rgb,
        b: Rgb,
        t: Float,
    ): Int {
        fun ch(
            v: Int,
            s: Int,
        ) = (v shr s) and 0xff

        fun lerp(s: Int) = (ch(a.value, s) + (ch(b.value, s) - ch(a.value, s)) * t).toInt().coerceIn(0, 255)
        return OPAQUE or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible || rect.width <= 0 || rect.height <= 0) return
        if (rect.width.toLong() * rect.height > ImageLimits.MAX_PIXELS) return
        val w = rect.width
        val h = rect.height
        val half = maxOf(1, h / 2)
        val t0 = tint(top)
        val m0 = tint(middle)
        val b0 = tint(bottom)
        val rowColor =
            IntArray(h) { r ->
                if (r <
                    half
                ) {
                    mix(t0, m0, r.toFloat() / half)
                } else {
                    mix(m0, b0, (r - half).toFloat() / maxOf(1, h - half))
                }
            }
        val px = IntArray(w * h)
        val p = rt.player
        val pre = (p.eqPreamp - EQ_FLAT).coerceIn(-127, 127)
        val preRow = ((127 - pre) * h / 256).coerceIn(0, h - 1)
        val pc = tint(preamp).value or OPAQUE
        for (x in 0 until w) px[preRow * w + x] = pc
        // the bands as heights (a boost is up), the first and last doubled for the spline's ends
        val ys =
            DoubleArray(12) { i ->
                val band = (p.eqBands.getOrElse((i - 1).coerceIn(0, 9)) { EQ_FLAT } - EQ_FLAT).coerceIn(-127, 127)
                (127 - band) * h / 256.0
            }
        var prev = -1
        for (x in 0 until w) {
            val t = x / (0.11 * w) + 1
            val y = spline(ys, t).toInt().coerceIn(0, h - 1)
            val from = if (prev < 0) y else prev
            for (r in minOf(from, y)..maxOf(from, y)) px[r * w + x] = rowColor[r]
            prev = y
        }
        out.add(DrawCmd.Pixels(rect, w, h, px, effectiveAlpha))
    }

    /** Kochanek-Bartels (tension 0, continuity 0, bias [BIAS]) through [p] at parameter [t]. */
    private fun spline(
        p: DoubleArray,
        t: Double,
    ): Double {
        val i = floor(t).toInt().coerceIn(1, p.size - 3)
        val u = (t - i).coerceIn(0.0, 1.0)

        fun tangent(k: Int): Double {
            val before = p[k] - p[(k - 1).coerceAtLeast(0)]
            val after = p[(k + 1).coerceAtMost(p.size - 1)] - p[k]
            return (1 + BIAS) / 2 * before + (1 - BIAS) / 2 * after
        }
        val m0 = tangent(i)
        val m1 = tangent(i + 1)
        val u2 = u * u
        val u3 = u2 * u
        return (2 * u3 - 3 * u2 + 1) * p[i] + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * p[i + 1] + (u3 - u2) * m1
    }

    private companion object {
        const val OPAQUE = 0xff000000.toInt()
        const val EQ_FLAT = 128
        const val BIAS = 0.1
    }
}
