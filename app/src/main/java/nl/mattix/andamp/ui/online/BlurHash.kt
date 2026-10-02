// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.withSign

/**
 * BlurHash: a picture's low frequencies as a short string.
 *
 * A museum screenshot arrives over the network, and until it does its tile shows a hash:
 * twenty-eight characters holding a handful of cosine coefficients, enough to say where a picture
 * is light and dark and what color it is there. The app ships these hashes and not the screenshots.
 *
 * The algorithm is Wolt's (github.com/woltapp/blurhash, MIT), implemented here from its
 * description. Decoding only: the hashes were encoded by `tools/blurhash.py`; see [MuseumTiles].
 */
internal object BlurHash {
    /** The base 83 alphabet, in digit order. */
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    /** A hash carries at least the size flag, the quantized maximum and the DC color. */
    private const val HEADER = 6

    /** Each AC coefficient is two characters. */
    private const val PER_COMPONENT = 2

    private const val MAX_COMPONENTS = 9
    private const val QUANT_RANGE = 19
    private const val QUANT_MID = 9f
    private const val AC_SCALE = 166f

    /**
     * [hash] as a bitmap [width] by [height], or null when the string is not a valid hash.
     *
     * Decode small. The result is stretched over the tile by whatever draws it, and a hash holds
     * only a small grid of coefficients, so more than a few dozen pixels a side adds no detail.
     */
    fun decode(
        hash: String,
        width: Int,
        height: Int,
    ): ImageBitmap? {
        if (width <= 0 || height <= 0) return null
        // every digit is checked here, so [value] can assume them
        if (hash.length < HEADER || hash.any { it !in ALPHABET }) return null
        val size = value(hash, 0, 1)
        val grid = IntSize(size % MAX_COMPONENTS + 1, size / MAX_COMPONENTS + 1)
        val colours = coefficients(hash, grid) ?: return null
        return android.graphics.Bitmap
            .createBitmap(pixels(colours, grid, width, height), width, height, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }

    /**
     * The hash's coefficients in linear light, three floats per cell of the
     * [grid], or null when the string is not a hash of that shape.
     */
    private fun coefficients(
        hash: String,
        grid: IntSize,
    ): FloatArray? {
        val cells = grid.width * grid.height
        if (hash.length != HEADER + PER_COMPONENT * (cells - 1)) return null
        val maximum = (value(hash, 1, 2) + 1) / AC_SCALE
        val dc = value(hash, 2, HEADER)
        val colours = FloatArray(cells * 3)
        colours[0] = linear(dc shr 16 and 0xFF)
        colours[1] = linear(dc shr 8 and 0xFF)
        colours[2] = linear(dc and 0xFF)
        for (i in 1 until cells) {
            val at = HEADER + (i - 1) * PER_COMPONENT
            val packed = value(hash, at, at + PER_COMPONENT)
            colours[i * 3] = ac(packed / (QUANT_RANGE * QUANT_RANGE), maximum)
            colours[i * 3 + 1] = ac(packed / QUANT_RANGE % QUANT_RANGE, maximum)
            colours[i * 3 + 2] = ac(packed % QUANT_RANGE, maximum)
        }
        return colours
    }

    /** The coefficients summed over their cosine basis, as ARGB. */
    private fun pixels(
        colours: FloatArray,
        grid: IntSize,
        width: Int,
        height: Int,
    ): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = colourAt(colours, grid, x, y, width, height)
            }
        }
        return out
    }

    /** One pixel: every coefficient weighted by its own cosine at this point. */
    @Suppress("LongParameterList") // a point, the picture it is in, and the coefficients
    private fun colourAt(
        colours: FloatArray,
        grid: IntSize,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Int {
        var r = 0f
        var g = 0f
        var b = 0f
        for (j in 0 until grid.height) {
            for (i in 0 until grid.width) {
                val basis = cos(Math.PI * x * i / width).toFloat() * cos(Math.PI * y * j / height).toFloat()
                val at = (j * grid.width + i) * 3
                r += colours[at] * basis
                g += colours[at + 1] * basis
                b += colours[at + 2] * basis
            }
        }
        return (0xFF shl 24) or (srgb(r) shl 16) or (srgb(g) shl 8) or srgb(b)
    }

    /**
     * The digits of [hash] between [from] and [to], read as base 83. [decode] has already checked
     * that every character is a digit and the range is inside the string.
     */
    private fun value(
        hash: String,
        from: Int,
        to: Int,
    ): Int {
        var out = 0
        for (i in from until to) {
            out = out * ALPHABET.length + ALPHABET.indexOf(hash[i])
        }
        return out
    }

    /** One quantized AC coefficient, back to its signed linear value. */
    private fun ac(
        quantised: Int,
        maximum: Float,
    ): Float {
        val normalised = (quantised - QUANT_MID) / QUANT_MID
        return (normalised.absoluteValue * normalised.absoluteValue * maximum).withSign(normalised.sign)
    }

    /** sRGB byte to linear light, the curve the coefficients were measured in. */
    private fun linear(byte: Int): Float {
        val v = byte / 255f
        return if (v <= LINEAR_KNEE) v / LINEAR_SLOPE else ((v + GAMMA_OFFSET) / GAMMA_SCALE).pow(GAMMA)
    }

    /** Linear light back to an sRGB byte, clamped. */
    private fun srgb(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val s = if (v <= SRGB_KNEE) v * LINEAR_SLOPE else GAMMA_SCALE * v.pow(1 / GAMMA) - GAMMA_OFFSET
        return (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun Float.pow(exponent: Float): Float = Math.pow(toDouble(), exponent.toDouble()).toFloat()

    private const val LINEAR_KNEE = 0.04045f
    private const val SRGB_KNEE = 0.0031308f
    private const val LINEAR_SLOPE = 12.92f
    private const val GAMMA_OFFSET = 0.055f
    private const val GAMMA_SCALE = 1.055f
    private const val GAMMA = 2.4f
}
