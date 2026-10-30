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

    /** Whether this rectangle and [r] share at least one pixel. */
    fun overlaps(r: Rect) = r.left < right && left < r.right && r.top < bottom && top < r.bottom

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
) {
    /**
     * This part cut to what its file holds (see [SkinRuntime.draw]), worked out once: [FIT_UNKNOWN]
     * until then, [FIT_WHOLE] when the file holds all of it at the size it names, [FIT_CUT] with the
     * part the file holds in [fitted] and the size asked for in [askedW] x [askedH], [FIT_NONE] when
     * nothing of it lies on the file.
     */
    internal var fit = FIT_UNKNOWN
    internal var fitted: BitmapDef? = null
    internal var askedW = 0
    internal var askedH = 0

    internal companion object {
        const val FIT_UNKNOWN = 0
        const val FIT_WHOLE = 1
        const val FIT_CUT = 2
        const val FIT_NONE = 3
    }
}

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
    /** The object that drew this; its name is made only when [owner] is read. */
    internal open var ownedBy: Any? = null

    /** The object that drew this, for tracing a wrong pixel back to the skin's XML. */
    var owner: String?
        get() =
            ownedBy?.let {
                ownerNamesBuilt++
                it.toString()
            }
        set(value) {
            ownedBy = value
        }

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

    object WindowEnd : DrawCmd() {
        // one object shared by every window of every skin: it keeps no owner, which would keep
        // that object's skin in memory for as long as the app runs
        override var ownedBy: Any?
            get() = null
            set(value) = Unit
    }

    /**
     * Whether [other] draws exactly what this draws: the same kind of command with the same values,
     * the same part of the same picture in the same tint, the same font, and pixels with the same
     * content. A renderer may then keep what it drew for one and show it for the other. A window's
     * shape is the same when it is made of the same pictures, boxes and operations, whether or not
     * the runtime worked it out again.
     */
    internal fun drawsSameAs(other: DrawCmd): Boolean =
        when (this) {
            is Image -> other is Image && sameAs(other)
            is Fill -> other is Fill && rect == other.rect && color == other.color && alpha == other.alpha
            is WindowStart -> other is WindowStart && region.sameShapeAs(other.region)
            WindowEnd -> other === WindowEnd
            is Text -> other is Text && sameAs(other)
            is BitmapText -> other is BitmapText && sameAs(other)
            is Clip -> other is Clip && rect == other.rect
            Unclip -> other === Unclip
            is Pixels -> other is Pixels && sameAs(other)
        }

    companion object {
        /** Owner names made so far, by reading [owner]. */
        @Volatile
        var ownerNamesBuilt = 0L
            internal set

        /**
         * Whether a pixel of a region picture marks a cut: opaque, whatever its color. Winamp
         * Modern's main window marks its corners in black, its shaded playlist in white.
         */
        fun isCutPixel(argb: Int): Boolean = (argb ushr 24) >= 128
    }

    @Suppress("LongParameterList") // a run of text: its words, font, color, box, ticker offset and shadow
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
    ) : DrawCmd() {
        /** The face it is drawn with ([FontFace.of]). */
        val face: FontFace get() = FontFace.of(font, bold)
    }

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

    /** The end of the last [Clip]; like [WindowEnd], one shared object that keeps no owner. */
    object Unclip : DrawCmd() {
        override var ownedBy: Any?
            get() = null
            set(value) = Unit
    }

    /**
     * A small ARGB picture ([w] x [h], rows top first) stretched over [rect] with nearest pixels: the
     * vis, the EQ graph and gradients. [stable] means the same [argb] array comes back unchanged in
     * later frames until the picture changes, so a host may keep what it made from it.
     */
    class Pixels(
        val rect: Rect,
        val w: Int,
        val h: Int,
        val argb: IntArray,
        val alpha: Int,
        val stable: Boolean = false,
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

private fun DrawCmd.Image.sameAs(o: DrawCmd.Image) =
    dst == o.dst && alpha == o.alpha && smooth == o.smooth && gamma == o.gamma && bitmap.sameAs(o.bitmap)

private fun DrawCmd.Text.sameAs(o: DrawCmd.Text) =
    text == o.text && size == o.size && color == o.color && rect == o.rect && align == o.align && scroll == o.scroll &&
        alpha == o.alpha && valign == o.valign && bold == o.bold && shadow == o.shadow && shadowDx == o.shadowDx &&
        shadowDy == o.shadowDy && font.sameAs(o.font)

private fun DrawCmd.BitmapText.sameAs(o: DrawCmd.BitmapText) =
    text == o.text && rect == o.rect && align == o.align && alpha == o.alpha && valign == o.valign &&
        colonWidth == o.colonWidth && scroll == o.scroll && gamma == o.gamma && font.sameAs(o.font)

private fun DrawCmd.Pixels.sameAs(o: DrawCmd.Pixels) =
    rect == o.rect && w == o.w && h == o.h && alpha == o.alpha && stable == o.stable &&
        (argb === o.argb || argb.contentEquals(o.argb))

/** The same part of the same file: a part made again each frame (an animation's frame) draws the same. */
private fun BitmapDef.sameAs(o: BitmapDef) =
    this === o || (x == o.x && y == o.y && w == o.w && h == o.h && id == o.id && file == o.file && gammaGroup == o.gammaGroup)

/** The same font: a system font a text names is made again each frame. */
private fun FontDef?.sameAs(o: FontDef?) = this === o || (this != null && o != null && id == o.id && file == o.file)

private fun BitmapFontDef.sameAs(o: BitmapFontDef) =
    this === o ||
        (
            charWidth == o.charWidth && charHeight == o.charHeight && hSpacing == o.hSpacing && vSpacing == o.vSpacing &&
                ox == o.ox && oy == o.oy && id == o.id && file == o.file && gammaGroup == o.gammaGroup
        )

/** The same shape: the same pictures in the same places, the same boxes, combined the same way. */
private fun Region.sameShapeAs(o: Region): Boolean =
    this === o ||
        when (this) {
            is Region.Pic -> o is Region.Pic && image.drawsSameAs(o.image)
            is Region.Box -> o is Region.Box && rect == o.rect
            is Region.Op -> o is Region.Op && sameShapeAs(o)
        }

private fun Region.Op.sameShapeAs(o: Region.Op): Boolean {
    val b = base
    val ob = o.base
    val sameBase = if (b == null || ob == null) b === ob else b.sameShapeAs(ob)
    return sameBase && add.sameShapesAs(o.add) && keep.sameShapesAs(o.keep) && cut.sameShapesAs(o.cut)
}

private fun List<Region>.sameShapesAs(o: List<Region>) = size == o.size && indices.all { this[it].sameShapeAs(o[it]) }
