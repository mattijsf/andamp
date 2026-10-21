// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * `<ColorThemes:List>`: the skin's color themes, sorted by name, drawn as Winamp 5.666 draws its
 * built-in list: a column header "Theme" (unless `nocolheader`), rows over the skin's list
 * background tile, the theme in use bold on the selection bar and scrolled into view, a vertical
 * scrollbar from the skin's (or the default set's) scrollbar pictures and a horizontal one at the
 * bottom (unless `nohscroll`). Colors and pictures are the `wasabi.list.*` / `wasabi.scrollbar.*`
 * elements, which [SkinRuntime] also finds under their old `studio.*` names and in the default set.
 *
 * Row pitch and inset differ by skin format version (measured on two skins): Winamp Modern
 * (1.35) has 18 px rows 1 px in, MMD3 (0.8) 14 px rows 2 px in; the glyphs are the same size.
 * A tap selects a row, a second tap on it (or the skin's Switch button) switches to it, and a
 * drag scrolls.
 */
class ColorThemeList(
    rt: SkinRuntime,
) : GuiObject(rt, "colorthemes:list") {
    override val className: String get() = "GuiObject"

    /** The highlighted row's theme: the one in use until the user taps another. */
    var selected: String? = null
        private set

    private var header = true
    private var hscroll = true

    /** The first visible row, in pixels from the top of the rows. */
    private var scrollPx = 0
    private var shownFor: String? = null
    private var downY = 0
    private var downScroll = 0
    private var dragged = false

    private val names: List<String> get() = rt.gammaSetNames().sortedWith(String.CASE_INSENSITIVE_ORDER)

    private val old: Boolean get() = rt.walVersion < 1.0
    private val rowH: Int get() = if (old) ROW_OLD else ROW
    private val inset: Int get() = if (old) 2 else 1

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        return when (key.lowercase()) {
            "nocolheader" -> {
                header = value != "1"
                true
            }

            "nohscroll" -> {
                hscroll = value != "1"
                true
            }

            "nocols" -> {
                true
            }

            else -> {
                false
            }
        }
    }

    private val headerH: Int get() = if (header) HEADER else 0
    private val barW: Int get() = rt.bitmapSize(UP)?.first ?: 0
    private val hbarH: Int get() = if (hscroll) rt.bitmapSize(LEFT)?.second ?: 0 else 0

    /** Where the rows show: under the header, left of the vertical bar, above the horizontal one. */
    private val rowsArea: Rect get() =
        Rect(
            rect.left + inset,
            rect.top + headerH,
            rect.width - inset - barW,
            rect.height - headerH - hbarH,
        )

    private fun maxScroll() = maxOf(0, names.size * rowH - rowsArea.height)

    /** Selects the theme in use and scrolls it into the middle, as Winamp does when the list appears. */
    private fun followCurrent() {
        val current = rt.currentGammaSet
        if (current == shownFor) return
        shownFor = current
        selected = current
        val index = names.indexOf(current)
        if (index < 0) return
        // where Winamp 5.666 puts the row (measured on both skins; the horizontal bar does not count)
        val middle = if (header) (rect.height - HEADER - rowH) / 2 - 2 else (rect.height - 6 - rowH) / 2
        scrollPx = (index * rowH - middle).coerceIn(0, maxScroll())
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible || rect.width <= 0 || rect.height <= 0) return
        followCurrent()
        val text = rt.color("wasabi.list.text") ?: Rgb(0xFFFFFF)
        val selText = rt.color("wasabi.list.text.selected") ?: rt.color("wasabi.list.item.selected.fg") ?: text
        val selBack = rt.color("wasabi.list.text.selected.background") ?: rt.color("wasabi.list.item.selected") ?: Rgb(0x6788C9)
        val rows = rowsArea
        // the background tile under the rows only: the scrollbars sit on whatever is behind the list
        // (MMD3's black group background), and the header paints its own color. The tile
        // scrolls with the rows: its origin is where row 0 would be (measured on MMD3's dot pattern)
        rt.bitmap(BACKGROUND)?.let { tile ->
            rt.bitmapSize(BACKGROUND)?.let { (tw, th) ->
                out.add(DrawCmd.Clip(Rect(rect.left, rows.top, rows.right - rect.left, rows.height)))
                val phase = Math.floorMod(scrollPx, th)
                tileOver(out, tile, tw, th, Rect(rect.left, rows.top - phase, rows.right - rect.left, rows.height + phase))
                out.add(DrawCmd.Unclip)
            }
        }
        out.add(DrawCmd.Clip(rows))
        val all = names
        val first = scrollPx / rowH
        for (i in first until all.size) {
            val y = rows.top + i * rowH - scrollPx
            if (y >= rows.bottom) break
            val row = Rect(rows.left, y, rows.width, rowH)
            val name = all[i]
            val isSelected = name.equals(selected, ignoreCase = true)
            if (isSelected) out.add(DrawCmd.Fill(row, selBack, effectiveAlpha))
            out.add(
                DrawCmd.Text(
                    name,
                    null,
                    FONT_SIZE,
                    if (isSelected) selText else text,
                    Rect(rect.left + TEXT_LEFT, row.top, row.right - rect.left - TEXT_LEFT, row.height),
                    Align.LEFT,
                    0,
                    effectiveAlpha,
                    VAlign.CENTER,
                    bold = name.equals(rt.currentGammaSet, ignoreCase = true),
                ),
            )
        }
        out.add(DrawCmd.Unclip)
        if (header) drawHeader(out)
        drawScrollbars(out)
    }

    private fun tileOver(
        out: MutableList<DrawCmd>,
        tile: BitmapDef,
        tw: Int,
        th: Int,
        area: Rect,
    ) {
        if (tw <= 0 || th <= 0) return
        var y = area.top
        while (y < area.bottom) {
            var x = area.left
            while (x < area.right) {
                val w = minOf(tw, area.right - x)
                val h = minOf(th, area.bottom - y)
                val part = if (w == tw && h == th) tile else BitmapDef(tile.id, tile.file, tile.x, tile.y, w, h, tile.gammaGroup)
                out.add(DrawCmd.Image(part, Rect(x, y, w, h), effectiveAlpha))
                x += tw
            }
            y += th
        }
    }

    /** The column header: its background color across the list, the column name in its text color. */
    private fun drawHeader(out: MutableList<DrawCmd>) {
        val bg = rt.color("wasabi.list.column.background") ?: Rgb(0x666666)
        val fg = rt.color("wasabi.list.column.text") ?: Rgb(0xFFFFFF)
        out.add(DrawCmd.Fill(Rect(rect.left, rect.top, rect.width, HEADER), bg, effectiveAlpha))
        out.add(
            DrawCmd.Text(
                "Theme",
                null,
                FONT_SIZE,
                fg,
                Rect(rect.left + TEXT_LEFT, rect.top, rect.width - TEXT_LEFT, HEADER),
                Align.LEFT,
                0,
                effectiveAlpha,
                VAlign.CENTER,
            ),
        )
    }

    /**
     * Winamp's list draws its own scrollbars: arrows at the ends, the thumb along the track by how
     * far the list is scrolled, all tinted like the arrows (a track picture names no gammagroup,
     * and Winamp still tints it "Scrollbar Buttons"). The vertical bar stops above the horizontal
     * one; the square between them is black.
     */
    private fun drawScrollbars(out: MutableList<DrawCmd>) {
        val bw = barW
        val hh = hbarH
        if (bw > 0) {
            val up = rt.bitmap(UP) ?: return
            val down = rt.bitmap(DOWN) ?: return
            val thumb = rt.bitmap(THUMB) ?: return
            val group = up.gammaGroup

            fun BitmapDef.tinted() = if (gammaGroup != null) this else BitmapDef(id, file, x, y, w, h, group)
            val x = rect.right - bw
            val barH = rect.height - hh
            val upH = rt.bitmapSize(UP)?.second ?: 0
            val downH = rt.bitmapSize(DOWN)?.second ?: 0
            val thumbH = rt.bitmapSize(THUMB)?.second ?: 0
            // the track runs under the arrows too, so their rounded corners show it
            rt.bitmap(TRACK)?.let { out.add(DrawCmd.Image(it.tinted(), Rect(x, rect.top, bw, barH), effectiveAlpha)) }
            out.add(DrawCmd.Image(up, Rect(x, rect.top, bw, upH), effectiveAlpha))
            out.add(DrawCmd.Image(down, Rect(x, rect.top + barH - downH, bw, downH), effectiveAlpha))
            val travel = maxOf(0, barH - upH - downH - thumbH)
            val max = maxScroll()
            val thumbTop = rect.top + upH + if (max == 0) 0 else travel * scrollPx / max
            out.add(DrawCmd.Image(thumb, Rect(x, thumbTop, bw, thumbH), effectiveAlpha))
        }
        if (hh > 0) {
            val left = rt.bitmap(LEFT) ?: return
            val right = rt.bitmap(RIGHT) ?: return
            val thumb = rt.bitmap(HTHUMB) ?: return
            val group = left.gammaGroup

            fun BitmapDef.tinted() = if (gammaGroup != null) this else BitmapDef(id, file, x, y, w, h, group)
            val y = rect.bottom - hh
            val barW = rect.width - bw
            val leftW = rt.bitmapSize(LEFT)?.first ?: 0
            val rightW = rt.bitmapSize(RIGHT)?.first ?: 0
            val thumbW = rt.bitmapSize(HTHUMB)?.first ?: 0
            rt.bitmap(HTRACK)?.let { out.add(DrawCmd.Image(it.tinted(), Rect(rect.left, y, barW, hh), effectiveAlpha)) }
            out.add(DrawCmd.Image(left, Rect(rect.left, y, leftW, hh), effectiveAlpha))
            out.add(DrawCmd.Image(right, Rect(rect.left + barW - rightW, y, rightW, hh), effectiveAlpha))
            // nothing scrolls sideways here: the thumb sits at the start
            out.add(DrawCmd.Image(thumb, Rect(rect.left + leftW, y, thumbW, hh), effectiveAlpha))
            if (bw > 0) out.add(DrawCmd.Fill(Rect(rect.right - bw, y, bw, hh), Rgb(0), effectiveAlpha))
        }
    }

    override fun pointerDown(
        px: Int,
        py: Int,
    ) {
        downY = py
        downScroll = scrollPx
        dragged = false
    }

    override fun pointerMove(
        px: Int,
        py: Int,
    ) {
        if (!dragged && kotlin.math.abs(py - downY) < DRAG_SLOP) return
        dragged = true
        scrollPx = (downScroll - (py - downY)).coerceIn(0, maxScroll())
        rt.invalidate()
    }

    override fun pointerUp(
        px: Int,
        py: Int,
        inside: Boolean,
    ) {
        if (dragged || !inside) return
        val rows = rowsArea
        if (py < rows.top || py >= rows.bottom) return
        val name = names.getOrNull((py - rows.top + scrollPx) / rowH) ?: return
        if (name.equals(selected, ignoreCase = true)) switchToSelected() else selected = name
        rt.invalidate()
    }

    fun switchToSelected() {
        selected?.let { rt.selectGammaSet(it) }
        shownFor = rt.currentGammaSet
    }

    /** Previous and next switch straight to the neighboring theme, with wraparound. */
    fun step(delta: Int) {
        val all = names
        if (all.isEmpty()) return
        val index = all.indexOfFirst { it.equals(rt.currentGammaSet, ignoreCase = true) }
        val next = all[Math.floorMod(index + delta, all.size)]
        rt.selectGammaSet(next)
        shownFor = null
    }

    private companion object {
        const val ROW = 18
        const val ROW_OLD = 14
        const val HEADER = 12

        /** Text starts 4 px in from the list's left edge, in rows and header alike. */
        const val TEXT_LEFT = 4

        /** Wasabi lists draw in the skin's default font at size 16 (12 px). */
        const val FONT_SIZE = 16
        const val DRAG_SLOP = 6
        const val BACKGROUND = "wasabi.list.background"
        const val UP = "wasabi.scrollbar.vertical.left"
        const val DOWN = "wasabi.scrollbar.vertical.right"
        const val THUMB = "wasabi.scrollbar.vertical.button"
        const val TRACK = "wasabi.scrollbar.vertical.background.middle"
        const val LEFT = "wasabi.scrollbar.horizontal.left"
        const val RIGHT = "wasabi.scrollbar.horizontal.right"
        const val HTHUMB = "wasabi.scrollbar.horizontal.button"
        const val HTRACK = "wasabi.scrollbar.horizontal.background.middle"
    }
}
