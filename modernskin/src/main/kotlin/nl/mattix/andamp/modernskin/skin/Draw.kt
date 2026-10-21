// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/** A rectangle in layout pixels. */
data class Rect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right get() = left + width
    val bottom get() = top + height

    operator fun contains(p: Pair<Int, Int>) = p.first >= left && p.first < right && p.second >= top && p.second < bottom

    companion object {
        val EMPTY = Rect(0, 0, 0, 0)
    }
}

/** A part of a skin image: the file and the source rectangle inside it, or null size for the whole file. */
class BitmapDef(
    val id: String,
    val file: String,
    val x: Int,
    val y: Int,
    val w: Int?,
    val h: Int?,
    val gammaGroup: String? = null,
)

/**
 * A color theme's tint for one gamma group: per-channel factors in 1/4096 steps around 1, an
 * optional grey conversion first (1 = brightest channel, 2 = average). `boost` brightens before
 * the tint (see [apply]), with or without a tint.
 */
data class Gamma(
    val r: Int,
    val g: Int,
    val b: Int,
    val boost: Int,
    val gray: Int,
) {
    // a boost brightens even with no tint: MMD3's "Thinger" group is 0,0,0 with boost 1 in its
    // default theme, and Winamp 5.666 draws the component bucket lifted there
    val isIdentity: Boolean get() = r == 0 && g == 0 && b == 0 && gray == 0 && boost == 0

    fun apply(argb: Int): Int {
        var red = (argb shr 16) and 0xff
        var green = (argb shr 8) and 0xff
        var blue = argb and 0xff
        if (gray != 0) {
            red = if (gray == 2) (red + green + blue) / 3 else maxOf(red, green, blue)
            green = red
            blue = red
        }

        // boost brightens before the tint. 1 squeezes the pixel into the upper half (c / 2 + 128:
        // Winamp 5.666's black menu text under MonoDark and MMD3's green labels under "silver1 |
        // xblue" both fit); 2 adds 32 and quadruples, as Webamp Modern does (only seen with
        // near-black tints, where it matches)
        fun lifted(c: Int): Double =
            when (boost) {
                1 -> c / 2.0 + 128
                2 -> (c + 32) * 4.0
                else -> c.toDouble()
            }

        fun ch(
            c: Int,
            f: Int,
        ) = (lifted(c) * (1.0 + f / 4096.0)).toInt().coerceIn(0, 255)
        return (argb and 0xff000000.toInt()) or (ch(red, r) shl 16) or (ch(green, g) shl 8) or ch(blue, b)
    }
}

class BitmapFontDef(
    val id: String,
    val file: String,
    val charWidth: Int,
    val charHeight: Int,
    val hSpacing: Int,
    val vSpacing: Int,
    /** Where the glyph sheet starts inside [file], when the font names a bitmap element. */
    val ox: Int = 0,
    val oy: Int = 0,
    val gammaGroup: String? = null,
) {
    /** Which cell a character sits in: the rows of a Winamp bitmap font. */
    fun cell(c: Char): Pair<Int, Int>? {
        // a space advances but draws nothing; its cell is not blank in every skin
        if (c == ' ') return null
        val lower = c.lowercaseChar()
        ROWS.forEachIndexed { row, chars ->
            val col = chars.indexOf(lower)
            if (col >= 0) return col to row
        }
        return null
    }

    private companion object {
        val ROWS = listOf("abcdefghijklmnopqrstuvwxyz\"@  ", "0123456789….:()-'!_+\\/[]^&%,=$#\nâöä?*")
    }
}

class FontDef(
    val id: String,
    val file: String,
)

/** RGB color as 0xRRGGBB. */
@JvmInline
value class Rgb(
    val value: Int,
)

enum class Align { LEFT, CENTER, RIGHT }

enum class VAlign { TOP, CENTER, BOTTOM }

/** What the renderer draws, in order, in layout pixels. */
sealed class DrawCmd {
    /** The object that drew this, for tracing a wrong pixel back to the skin's XML. */
    var owner: String? = null

    /** A bitmap, or the part of it [src] names, stretched to [dst]. */
    class Image(
        val bitmap: BitmapDef,
        val dst: Rect,
        val alpha: Int,
    ) : DrawCmd() {
        /** The color theme's tint for this bitmap, filled in when the frame is drawn. */
        var gamma: Gamma? = null

        /** Scale with filtering instead of nearest pixels: Winamp shrinks a list's thumb picture that way. */
        var smooth = false
    }

    class Fill(
        val rect: Rect,
        val color: Rgb,
        val alpha: Int,
    ) : DrawCmd()

    /**
     * Start of a shaped window: what follows until [WindowEnd] shows only inside [region]. Renderers
     * evaluate it with [Region.eval].
     */
    class WindowStart(
        val region: Region,
    ) : DrawCmd()

    object WindowEnd : DrawCmd()

    companion object {
        /**
         * Whether a pixel of a region picture marks a cut: opaque, whatever its color. Winamp
         * Modern's main window marks its corners in black, its shaded playlist in white.
         */
        fun isCutPixel(argb: Int): Boolean = (argb ushr 24) >= 128
    }

    class Text(
        val text: String,
        val font: FontDef?,
        val size: Int,
        val color: Rgb,
        val rect: Rect,
        val align: Align,
        /** Pixels to shift the text left by, for a ticker. */
        val scroll: Int,
        val alpha: Int,
        val valign: VAlign = VAlign.TOP,
        val bold: Boolean = false,
        val shadow: Rgb? = null,
        /** Where the shadow sits from the text. */
        val shadowDx: Int = 1,
        val shadowDy: Int = 1,
    ) : DrawCmd()

    class BitmapText(
        val text: String,
        val font: BitmapFontDef,
        val rect: Rect,
        val align: Align,
        val alpha: Int,
        val valign: VAlign = VAlign.TOP,
        /** The advance of a colon in a time display, when the skin narrows it. */
        val colonWidth: Int? = null,
        /** Pixels to shift the text left by, for a ticker. */
        val scroll: Int = 0,
    ) : DrawCmd() {
        var gamma: Gamma? = null
    }

    /** Clip the commands that follow, until the matching [Unclip]. */
    class Clip(
        val rect: Rect,
    ) : DrawCmd()

    object Unclip : DrawCmd()

    /** A small ARGB picture ([w] x [h], rows top first) stretched over [rect] with nearest pixels: the vis and EQ graph. */
    class Pixels(
        val rect: Rect,
        val w: Int,
        val h: Int,
        val argb: IntArray,
        val alpha: Int,
    ) : DrawCmd()
}

/**
 * A window's shape as Winamp composes it: a picture's opaque pixels, a
 * rectangle, or an object's own region with its children's added, intersected and cut away.
 */
sealed class Region {
    class Pic(
        val image: DrawCmd.Image,
    ) : Region()

    class Box(
        val rect: Rect,
    ) : Region()

    /** [base] (nothing when null), kept only where [keep] is when that is not empty, plus [add], less [cut]. */
    class Op(
        val base: Region?,
        val add: List<Region>,
        val keep: List<Region>,
        val cut: List<Region>,
    ) : Region()

    /** The same region with every picture passed through [f] (dropped when it gives null). */
    fun mapPics(f: (DrawCmd.Image) -> DrawCmd.Image?): Region? =
        when (this) {
            is Pic -> {
                f(image)?.let { Pic(it) }
            }

            is Box -> {
                this
            }

            is Op -> {
                Op(
                    base?.mapPics(f),
                    add.mapNotNull {
                        it.mapPics(f)
                    },
                    keep.mapNotNull { it.mapPics(f) },
                    cut.mapNotNull { it.mapPics(f) },
                )
            }
        }

    /** Every picture in it. */
    fun pics(): List<DrawCmd.Image> =
        when (this) {
            is Pic -> listOf(image)
            is Box -> emptyList()
            is Op -> (listOfNotNull(base) + add + keep + cut).flatMap { it.pics() }
        }

    /** The box around it, or null when it holds nothing. */
    fun bounds(): Rect? =
        when (this) {
            is Pic -> {
                image.dst
            }

            is Box -> {
                rect
            }

            is Op -> {
                (listOfNotNull(base?.bounds()) + add.mapNotNull { it.bounds() }).reduceOrNull { a, b ->
                    val l = minOf(a.left, b.left)
                    val t = minOf(a.top, b.top)
                    Rect(l, t, maxOf(a.right, b.right) - l, maxOf(a.bottom, b.bottom) - t)
                }
            }
        }

    /**
     * Renders the region as a mask through a renderer's [Canvas]: each operand in a layer of its
     * own, composed with the matching blend. A picture that cuts counts every opaque pixel
     * ([DrawCmd.isCutPixel]), one that adds keeps its alpha.
     */
    fun eval(
        c: Canvas,
        hard: Boolean = false,
    ) {
        when (this) {
            is Pic -> {
                c.picture(image, hard)
            }

            is Box -> {
                c.box(rect)
            }

            is Op -> {
                if (keep.isEmpty() && cut.isEmpty()) {
                    // a plain union draws straight into the layer it is in, with no extra layer
                    base?.eval(c, hard)
                    add.forEach { it.eval(c, hard) }
                    return
                }
                base?.eval(c, hard)
                if (keep.isNotEmpty()) c.layer(Blend.KEEP) { keep.forEach { it.eval(c, true) } }
                add.forEach { a ->
                    if (a is Op &&
                        (a.keep.isNotEmpty() || a.cut.isNotEmpty())
                    ) {
                        c.layer(Blend.ADD) { a.eval(c, hard) }
                    } else {
                        a.eval(c, hard)
                    }
                }
                // the cuts together in one layer
                if (cut.isNotEmpty()) c.layer(Blend.CUT) { cut.forEach { it.eval(c, true) } }
            }
        }
    }

    enum class Blend { ADD, KEEP, CUT }

    /** What a renderer gives [eval]: draw a picture or a black box, and run [body] in a layer merged with a blend. */
    interface Canvas {
        fun picture(
            image: DrawCmd.Image,
            hard: Boolean,
        )

        fun box(rect: Rect)

        fun layer(
            blend: Blend,
            body: () -> Unit,
        )
    }
}
