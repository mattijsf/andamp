// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A line-for-line port of `skin/andamp/tonal.py`.
 *
 * It must stay a port and must not be swapped for HCT: the Python side bakes M3's state layers and
 * the playlist selection band with this arithmetic, and the template's test is that binding it here
 * reproduces the shipped `.wsz` byte for byte.
 *
 * CIELAB is used, as in the Python: L* tracks M3's tone closely, and it needs no dependency.
 */
object Tonal {
    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883

    private fun srgbToLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun linearToSrgb(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055

    fun toLab(c: Int): DoubleArray {
        val r = srgbToLinear(red(c) / 255.0)
        val g = srgbToLinear(green(c) / 255.0)
        val b = srgbToLinear(blue(c) / 255.0)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / YN
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN

        fun f(t: Double) = if (t > 216.0 / 24389.0) t.pow(1.0 / 3.0) else (24389.0 / 27.0 * t + 16.0) / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun labToRgbFloat(
        l: Double,
        a: Double,
        bb: Double,
    ): DoubleArray {
        val fy = (l + 16) / 116
        val fx = fy + a / 500
        val fz = fy - bb / 200

        fun finv(t: Double): Double {
            val t3 = t * t * t
            return if (t3 > 216.0 / 24389.0) t3 else (116 * t - 16) * 27 / 24389.0
        }
        val x = finv(fx) * XN
        val y = finv(fy) * YN
        val z = finv(fz) * ZN
        return doubleArrayOf(
            linearToSrgb(3.2404542 * x - 1.5371385 * y - 0.4985314 * z),
            linearToSrgb(-0.9692660 * x + 1.8760108 * y + 0.0415560 * z),
            linearToSrgb(0.0556434 * x - 0.2040259 * y + 1.0572252 * z),
        )
    }

    private fun inGamut(c: DoubleArray) = c.all { it >= -0.0005 && it <= 1.0005 }

    /**
     * Lab to sRGB, reducing chroma until it fits the gamut. The binary search runs a fixed 32
     * iterations, not to a tolerance, so the result matches the Python output bit for bit.
     */
    fun fromLch(
        l: Double,
        c: Double,
        hDeg: Double,
    ): Int {
        val h = Math.toRadians(hDeg)
        var lo = 0.0
        var hi = c
        if (inGamut(labToRgbFloat(l, c * cos(h), c * sin(h)))) {
            lo = c
        } else {
            repeat(32) {
                val mid = (lo + hi) / 2
                if (inGamut(labToRgbFloat(l, mid * cos(h), mid * sin(h)))) lo = mid else hi = mid
            }
        }
        val f = labToRgbFloat(l, lo * cos(h), lo * sin(h))
        return rgb(
            (f[0] * 255).roundToInt().coerceIn(0, 255),
            (f[1] * 255).roundToInt().coerceIn(0, 255),
            (f[2] * 255).roundToInt().coerceIn(0, 255),
        )
    }

    /** The color's hue and chroma, restated at lightness [t]. */
    fun tone(
        c: Int,
        t: Double,
    ): Int {
        val lab = toLab(c)
        return fromLch(t, hypot(lab[1], lab[2]), Math.toDegrees(atan2(lab[2], lab[1])))
    }

    /** Composite [b] over [a] at opacity [t], in linear light. */
    fun blend(
        a: Int,
        b: Int,
        t: Double,
    ): Int {
        fun ch(
            ca: Int,
            cb: Int,
        ): Int {
            val la = srgbToLinear(ca / 255.0)
            val lb = srgbToLinear(cb / 255.0)
            return (linearToSrgb(la * (1 - t) + lb * t) * 255).roundToInt().coerceIn(0, 255)
        }
        return rgb(ch(red(a), red(b)), ch(green(a), green(b)), ch(blue(a), blue(b)))
    }

    fun luminance(c: Int): Double {
        val r = srgbToLinear(red(c) / 255.0)
        val g = srgbToLinear(green(c) / 255.0)
        val b = srgbToLinear(blue(c) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG 2.x relative contrast ratio. */
    fun contrast(
        a: Int,
        b: Int,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return if (la > lb) (la + 0.05) / (lb + 0.05) else (lb + 0.05) / (la + 0.05)
    }
}
