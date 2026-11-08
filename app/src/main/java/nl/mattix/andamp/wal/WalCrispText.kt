// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import nl.mattix.walkit.DrawCmd
import nl.mattix.walkit.FontDef
import nl.mattix.walkit.FontFace
import nl.mattix.walkit.HintedFont
import kotlin.math.roundToInt

/**
 * A classic skin's text as Winamp's Windows draws it ([HintedFont]): hinted to the pixel grid,
 * one pixel to the skin pixel, each pixel ink or clear, so the renderer enlarges it unfiltered as
 * it does the skin's pictures. It is measured the same way, so text set against the right edge
 * ends where the engine put it. Text the font cannot spell (a script it has no letters for) is
 * left to the smooth drawing: [of] and [width] give null for it.
 *
 * Each run is drawn once into a picture of its box and kept while it is drawn again: a playlist
 * draws the same rows frame after frame. The engine measures on its thread and the screen draws
 * on another, so every call holds the lock.
 */
internal class WalCrispText(
    /** The bytes of the font a run is drawn in, bold or not. */
    private val fontBytes: (FontDef?, Boolean) -> ByteArray?,
) {
    /** What a run's picture is drawn from: its words, font and color, and where in its box it starts. */
    private data class Key(
        val text: String,
        val font: String?,
        val size: Int,
        val bold: Boolean,
        val color: Int,
        val shadow: Int?,
        val shadowDx: Int,
        val shadowDy: Int,
        val w: Int,
        val h: Int,
        val x: Int,
        val y: Int,
    )

    private val fonts = HashMap<Pair<String?, Boolean>, HintedFont?>()

    private val kept =
        object : LinkedHashMap<Key, ImageBitmap>(KEPT, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ImageBitmap>) = size > KEPT
        }

    /** The hinted font for [font], [bold] or not; null when there is none to read. */
    private fun fontFor(
        font: FontDef?,
        bold: Boolean,
    ): HintedFont? = fonts.getOrPut(font?.id to bold) { fontBytes(font, bold)?.let(HintedFont::of) }

    /** How wide [text] draws in [font] at the skin's [size]; null when the font cannot spell it. */
    @Synchronized
    fun width(
        text: String,
        font: FontDef?,
        size: Int,
        bold: Boolean,
    ): Int? = fontFor(font, bold)?.takeIf { it.covers(text) }?.width(text, FontFace.emSize(size))

    /** [cmd] drawn into a picture the size of its box, in layout pixels; null for an empty box or text the font cannot spell. */
    @Synchronized
    fun of(cmd: DrawCmd.Text): ImageBitmap? {
        val r = cmd.rect
        if (r.width <= 0 || r.height <= 0) return null
        val hinted = fontFor(cmd.font, cmd.bold)?.takeIf { it.covers(cmd.text) } ?: return null
        val key =
            Key(
                cmd.text,
                cmd.font?.id,
                cmd.size,
                cmd.bold,
                (cmd.color.value or OPAQUE) and alphaOf(cmd.alpha),
                cmd.shadow?.let { (it.value or OPAQUE) and alphaOf(cmd.alpha) },
                cmd.shadowDx,
                cmd.shadowDy,
                r.width,
                r.height,
                cmd.x.roundToInt() - r.left,
                cmd.baseline.roundToInt() - r.top,
            )
        return kept.getOrPut(key) { draw(hinted, key) }
    }

    private fun draw(
        font: HintedFont,
        k: Key,
    ): ImageBitmap {
        val em = FontFace.emSize(k.size)
        val px = font.draw(k.text, em, k.color, k.w, k.h, k.x, k.y)
        if (k.shadow != null) {
            val shade = font.draw(k.text, em, k.shadow, k.w, k.h, k.x + k.shadowDx, k.y + k.shadowDy)
            for (i in px.indices) if (px[i] == 0) px[i] = shade[i]
        }
        return Bitmap.createBitmap(px, k.w, k.h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private companion object {
        /** How many runs are kept: a tall playlist's rows twice over, and the titles. */
        const val KEPT = 256
        const val LOAD = 0.75f
        const val OPAQUE = 0xFF000000.toInt()

        /** A mask keeping a color's RGB under the alpha [alpha], 0..255. */
        fun alphaOf(alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or 0xFFFFFF
    }
}
