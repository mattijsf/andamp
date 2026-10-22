// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * `<gradient>`: a picture Winamp makes from color stops. `points` lists "position=r,g,b,a" pairs
 * (position 0..1, ";" between them); the colors blend between neighbouring stops along the line
 * from (`gradient_x1`, `gradient_y1`) to (`gradient_x2`, `gradient_y2`), given as shares of the
 * object's size; the result takes its `gammagroup`'s tint. One stop fills the object with it.
 */
class Gradient(
    rt: SkinRuntime,
) : GuiObject(rt, "gradient") {
    private var x1 = 0f
    private var y1 = 0f
    private var x2 = 1f
    private var y2 = 0f
    private var stops: List<Pair<Float, Int>> = emptyList()
    private var gammaGroup: String? = null

    /** The picture last made, and the size and tint it was made for; any parameter change drops it. */
    private var made: IntArray? = null
    private var madeFor: Triple<Int, Int, Gamma?>? = null

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        made = null
        when (key.lowercase()) {
            "gradient_x1" -> x1 = value.toFloatOrNull() ?: 0f
            "gradient_y1" -> y1 = value.toFloatOrNull() ?: 0f
            "gradient_x2" -> x2 = value.toFloatOrNull() ?: 0f
            "gradient_y2" -> y2 = value.toFloatOrNull() ?: 0f
            "points" -> stops = parseStops(value)
            "gammagroup" -> gammaGroup = value
            else -> return false
        }
        return true
    }

    private fun parseStops(value: String): List<Pair<Float, Int>> =
        value
            .split(';')
            .mapNotNull { stop ->
                val (pos, color) = stop.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val c = color.split(',').map { it.trim().toIntOrNull() ?: 0 }
                val argb =
                    (c.getOrElse(3) { 255 }.coerceIn(0, 255) shl 24) or (c.getOrElse(0) { 0 }.coerceIn(0, 255) shl 16) or
                        (c.getOrElse(1) { 0 }.coerceIn(0, 255) shl 8) or c.getOrElse(2) { 0 }.coerceIn(0, 255)
                (pos.trim().toFloatOrNull() ?: return@mapNotNull null) to argb
            }.sortedBy { it.first }

    /** The color at [t] along the line: the stops' colors blended channel by channel. */
    private fun colorAt(t: Float): Int {
        val first = stops.first()
        if (t <= first.first || stops.size == 1) return first.second
        val last = stops.last()
        if (t >= last.first) return last.second
        val i = stops.indexOfLast { it.first <= t }
        val (p0, c0) = stops[i]
        val (p1, c1) = stops[i + 1]
        val f = if (p1 > p0) (t - p0) / (p1 - p0) else 0f

        fun ch(s: Int) = (((c0 ushr s) and 0xff) + (((c1 ushr s) and 0xff) - ((c0 ushr s) and 0xff)) * f).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun draw(out: MutableList<DrawCmd>) {
        val w = rect.width
        val h = rect.height
        if (!visible || w <= 0 || h <= 0) return
        if (w.toLong() * h > ImageLimits.MAX_PIXELS) return
        val gamma = rt.gamma(gammaGroup)?.takeIf { !it.isIdentity }
        val key = Triple(w, h, gamma)
        made?.takeIf { madeFor == key }?.let {
            out.add(DrawCmd.Pixels(rect, w, h, it, effectiveAlpha, stable = true))
            return
        }
        // no stops at all: Winamp's magenta
        val argb = IntArray(w * h) { NO_STOPS }
        if (stops.isNotEmpty()) paint(argb, w, h, gamma)
        made = argb
        madeFor = key
        out.add(DrawCmd.Pixels(rect, w, h, argb, effectiveAlpha, stable = true))
    }

    /** Fills [argb] ([w] x [h]) with the stops blended along the line, tinted by [gamma]. */
    private fun paint(
        argb: IntArray,
        w: Int,
        h: Int,
        gamma: Gamma?,
    ) {
        val ax = x1 * w
        val ay = y1 * h
        val dx = x2 * w - ax
        val dy = y2 * h - ay
        val len2 = dx * dx + dy * dy
        for (y in 0 until h) {
            for (x in 0 until w) {
                // where the pixel falls along the line, 0 at its start and 1 at its end
                val t = if (len2 > 0f) ((x - ax) * dx + (y - ay) * dy) / len2 else 0f
                val c = colorAt(t)
                argb[y * w + x] = gamma?.apply(c) ?: c
            }
        }
    }

    override fun hitTest(
        px: Int,
        py: Int,
    ): GuiObject? = null

    private companion object {
        const val NO_STOPS = 0xffff00ff.toInt()
    }
}
