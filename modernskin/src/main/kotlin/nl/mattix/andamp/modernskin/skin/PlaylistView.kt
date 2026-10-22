// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * The playlist editor's list: `<component param="guid:{45F3F7C1-...}">`, which Winamp fills with
 * its own playlist. Rows of 15 px in the skin's list colors as Winamp 5.666 draws them: number and
 * title on the left, length on the right, the current entry in the "current" color, a narrow
 * scrollbar at the right edge. A tap selects a row, a second tap plays it, a drag scrolls.
 */
class PlaylistView(
    rt: SkinRuntime,
) : GuiObject(rt, "component") {
    override val className: String get() = "GuiObject"

    private var selected = -1
    private var scrollPx = 0
    private var downY = 0
    private var downScroll = 0
    private var dragged = false

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        return when (key.lowercase()) {
            "param", "noshowcmdbar", "autoopen", "autoclose" -> true
            else -> false
        }
    }

    /** The frame around the rows and the scrollbar: the skin's window background, lines in its sunken border color. */
    private val border: Rgb get() = rt.color("wasabi.window.background") ?: BORDER
    private val light: Rgb get() = rt.color("wasabi.border.sunken") ?: LIGHT

    /**
     * The rows' area, as Winamp 5.666 frames it: 2 px of border on the left, and on the
     * right 5 px of border, a light line, the 6 px scrollbar channel, a light line and 2 px of border.
     */
    private val listRect: Rect get() = Rect(rect.left + LEFT_BORDER, rect.top, rect.width - LEFT_BORDER - BAR, rect.height)

    private val rowsTop: Int get() = rect.top + TOP

    private fun maxScroll() = maxOf(0, TOP + rt.player.tracks.size * ROW - listRect.height)

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible || rect.width <= BAR || rect.height <= 0) return
        val tracks = rt.player.tracks
        // a skin without list colors (Winamp3) gets black behind the rows, the current one in gray
        val back = rt.color("wasabi.list.background") ?: Rgb(0)
        val text = rt.color("wasabi.list.text") ?: Rgb(0xFFFFFF)
        val current = rt.color("wasabi.list.text.current") ?: Rgb(0x7F7F7F)
        val selText = rt.color("wasabi.list.text.selected") ?: text
        val selBack = rt.color("wasabi.list.text.selected.background") ?: Rgb(0x6788C9)
        val list = listRect
        out.add(DrawCmd.Fill(rect, border, effectiveAlpha))
        out.add(DrawCmd.Fill(list, back, effectiveAlpha))
        out.add(DrawCmd.Clip(list))
        val first = scrollPx / ROW
        for (i in first until tracks.size) {
            val y = rowsTop + i * ROW - scrollPx
            // only rows that fit whole are drawn (WinampModern-pllong)
            if (y + ROW > list.bottom) break
            val row = Rect(list.left, y, list.width, ROW)
            val color =
                when (i) {
                    selected -> selText
                    rt.player.trackIndex -> current
                    else -> text
                }
            if (i == selected) out.add(DrawCmd.Fill(row, selBack, effectiveAlpha))
            val t = tracks[i]
            // text from 1 px inside the component's left edge to 18 px inside its right edge
            val inner = Rect(rect.left + TEXT_LEFT, row.top, rect.width - TEXT_LEFT - TEXT_RIGHT, row.height)
            val length = time(t.lengthMs)
            // a title that does not fit before the time ends in "..." a few px short of it
            val room = inner.width - rt.host.textWidth(length, null, FONT_SIZE) - TITLE_GAP
            // the numbers padded to the longest one's digits, with spaces (" 1." in a list of 20)
            val number = (i + 1).toString().padStart(tracks.size.toString().length, ' ')
            out.add(
                DrawCmd.Text(
                    fitted("$number. ${t.title}", room),
                    null,
                    FONT_SIZE,
                    color,
                    inner,
                    Align.LEFT,
                    0,
                    effectiveAlpha,
                    VAlign.CENTER,
                ),
            )
            out.add(DrawCmd.Text(length, null, FONT_SIZE, color, inner, Align.RIGHT, 0, effectiveAlpha, VAlign.CENTER))
        }
        out.add(DrawCmd.Unclip)
        drawScrollbar(out)
    }

    /**
     * Winamp's list scrollbar, as 5.666 draws it: 5 px of the frame's gray, a light line,
     * a 6 px channel in the skin's `wasabi.scrollbar.background` color, a light line and 2 px of
     * border; no arrows. The skin's thumb picture is shrunk, with filtering, to 8 px over the
     * channel and its two light lines, 17 px tall when nothing scrolls.
     */
    private fun drawScrollbar(out: MutableList<DrawCmd>) {
        val x = rect.right - BAR
        // without a skin color the channel is dark gray (Winamp3)
        val channel = rt.color("wasabi.scrollbar.background") ?: Rgb(0x333333)
        out.add(DrawCmd.Fill(Rect(x, rect.top, CHANNEL_X - 1, rect.height), border, effectiveAlpha))
        out.add(DrawCmd.Fill(Rect(x + CHANNEL_X - 1, rect.top, 1, rect.height), light, effectiveAlpha))
        out.add(DrawCmd.Fill(Rect(x + CHANNEL_X, rect.top, CHANNEL_W, rect.height), channel, effectiveAlpha))
        out.add(DrawCmd.Fill(Rect(x + CHANNEL_X + CHANNEL_W, rect.top, 1, rect.height), light, effectiveAlpha))
        // the thumb keeps its size however long the list (Wasabi SDK scrollbar; WinampModern-pllong)
        val thumbH = THUMB_MIN
        val travel = maxOf(0, rect.height - 2 - thumbH)
        val max = maxScroll()
        val top = rect.top + 1 + if (max == 0) 0 else travel * scrollPx / max
        val thumb = rt.bitmap(THUMB)
        if (thumb != null) {
            out.add(
                DrawCmd.Image(thumb, Rect(x + CHANNEL_X - 1, top, CHANNEL_W + 2, thumbH), effectiveAlpha).also {
                    it.smooth =
                        true
                },
            )
            // the skin's grip picture at its own size, centered on the thumb
            rt.bitmap(GRIP)?.let { g ->
                val (gw, gh) = rt.bitmapSize(GRIP) ?: (0 to 0)
                val gx = x + CHANNEL_X - 1 + (CHANNEL_W + 2 - gw) / 2
                val gy = top + (thumbH - gh) / 2
                out.add(DrawCmd.Image(g, Rect(gx, gy, gw, gh), effectiveAlpha))
            }
        } else {
            out.add(DrawCmd.Fill(Rect(x + CHANNEL_X, top, CHANNEL_W, thumbH), light, effectiveAlpha))
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
        val index = (py - rowsTop + scrollPx) / ROW
        if (index !in rt.player.tracks.indices) return
        if (index == selected) rt.player.playTrack(index) else selected = index
        rt.playlistSelectedMs = rt.player.tracks
            .getOrNull(selected)
            ?.lengthMs ?: 0
        rt.invalidate()
    }

    /** [text] as it fits in [width] px: whole, or cut with "..." after it. */
    private fun fitted(
        text: String,
        width: Int,
    ): String {
        if (rt.host.textWidth(text, null, FONT_SIZE) <= width) return text
        var end = text.length
        while (end > 0 && rt.host.textWidth(text.substring(0, end).trimEnd() + "...", null, FONT_SIZE) > width) end--
        return text.substring(0, end).trimEnd() + "..."
    }

    private fun time(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private companion object {
        const val ROW = 15
        const val FONT_SIZE = 16
        const val TOP = 3
        const val TEXT_RIGHT = 18
        const val TEXT_LEFT = 2

        /** Space kept between a cut title and its time. */
        const val TITLE_GAP = 1
        const val LEFT_BORDER = 2

        /** Right of the rows: 5 px gray, a light line, the 6 px channel, a light line, 2 px of border. */
        const val BAR = 15

        /** Where the channel starts inside the bar. */
        const val CHANNEL_X = 6
        const val CHANNEL_W = 6
        const val THUMB_MIN = 17
        const val THUMB = "wasabi.scrollbar.vertical.button"
        const val GRIP = "wasabi.scrollbar.vertical.grip"
        val BORDER = Rgb(0x7F8795)
        val LIGHT = Rgb(0xCCD0D9)
        const val DRAG_SLOP = 6
    }
}
