// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * `<LayoutStatus>`: the status bar of a window with a status frame (Winamp3's playlist editor and
 * Thinger). Winamp fills it with the commands of the components in the window, as buttons, and a
 * black field for status text between them. The playlist editor's are Add, Rem, Sel and Misc on
 * the left and List on the right; a window without commands shows the field alone (measured
 * against Winamp 5.666).
 */
class LayoutStatus(
    rt: SkinRuntime,
) : GuiObject(rt, "layoutstatus") {
    override val className: String get() = "LayoutStatus"

    /** `includeonly` / `exclude`: which components' commands show ("{system}" or a GUID); `bg="0"`: no field. */
    private var includeOnly: Set<String>? = null
    private var exclude: Set<String> = emptySet()
    private var background = true

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true

        fun ids(v: String) =
            v
                .split(';', ',')
                .map { it.trim().trim('{', '}').lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        when (key.lowercase()) {
            "includeonly" -> includeOnly = ids(value)
            "exclude" -> exclude = ids(value)
            "bg" -> background = value.toBool()
            else -> return false
        }
        return true
    }

    private fun shows(component: String) = (includeOnly?.contains(component) ?: true) && component !in exclude

    /** The commands of the components in this window: left-hand ones, right-hand ones. */
    private fun commands(): Pair<List<String>, List<String>> {
        val window = layoutObject ?: return emptyList<String>() to emptyList()

        fun hasPlaylist(o: GuiObject): Boolean = o is PlaylistView || (o is Group && o.children.any { hasPlaylist(it) })
        return if (hasPlaylist(window) && shows(PLAYLIST)) PLAYLIST_LEFT to PLAYLIST_RIGHT else emptyList<String>() to emptyList()
    }

    /** A command button's cell: its name between two "j"s, 11/10 of that, at least 8 (Wasabi SDK buttwnd). */
    private fun buttonWidth(text: String) = maxOf(rt.host.textWidth("j${text}j", FONT, FONT_SIZE) * 11 / 10, MIN_W)

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible || rect.width <= 0 || rect.height <= 0) return
        val (left, right) = commands()
        val top = rect.top + 1
        val h = BUTTON_H
        // the cells sit edge to edge; each button leaves the last 2 px of its cell to the bar (Wasabi
        // SDK buttbar: no spacer)
        var x = rect.left + 1
        for (c in left) {
            val w = buttonWidth(c)
            button(out, c, Rect(x, top, w - CELL_MARGIN, h))
            x += w
        }
        var r = rect.right - 1
        val rightRects =
            right
                .reversed()
                .map { c ->
                    val w = buttonWidth(c)
                    r -= w
                    Rect(r, top, w - CELL_MARGIN, h)
                }.reversed()
        // the status text field between the two groups of buttons
        if (background) out.add(DrawCmd.Fill(Rect(x, top, maxOf(0, r - x), h), Rgb(0), effectiveAlpha))
        right.zip(rightRects).forEach { (c, rr) -> button(out, c, rr) }
    }

    private fun button(
        out: MutableList<DrawCmd>,
        text: String,
        r: Rect,
    ) {
        drawFramedButton(rt, out, r, effectiveAlpha, pressed = false)
        out.add(
            DrawCmd.Text(
                text,
                FONT,
                FONT_SIZE,
                Rgb(0),
                Rect(r.left + 1, r.top + 1, r.width - 2, r.height - 2),
                Align.CENTER,
                0,
                effectiveAlpha,
                VAlign.CENTER,
            ),
        )
    }

    override fun hitTest(
        px: Int,
        py: Int,
    ): GuiObject? = null

    private companion object {
        const val PLAYLIST = "45f3f7c1-a6f3-4ee6-a15e-125e92fc3f8d"
        val PLAYLIST_LEFT = listOf("Add", "Rem", "Sel", "Misc")
        val PLAYLIST_RIGHT = listOf("List")

        /** Winamp's own buttons: Arial at text size 14, which draws at 11 px (Wasabi SDK buttwnd). */
        val FONT = FontDef("arial", "")
        const val FONT_SIZE = 14

        /** Buttons 13 px tall, 1 px into the bar; a cell at least 8 wide, its last 2 px the bar's. */
        const val BUTTON_H = 13
        const val MIN_W = 8
        const val CELL_MARGIN = 2
    }
}

/**
 * Winamp's own button frame, as it draws the buttons it makes itself and `borders="1"` buttons:
 * a black outline round the default set's `wasabi.button` picture (or its pressed one), stretched
 * from 2 px corners.
 */
internal fun drawFramedButton(
    rt: SkinRuntime,
    out: MutableList<DrawCmd>,
    r: Rect,
    alpha: Int,
    pressed: Boolean,
) {
    out.add(DrawCmd.Fill(r, Rgb(0), alpha))
    val base = if (pressed) "wasabi.button.pressed" else "wasabi.button"
    val corner = 2
    val l = r.left + 1
    val t = r.top + 1
    val mw = r.width - 2 - 2 * corner
    val mh = r.height - 2 - 2 * corner

    fun put(
        part: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        if (w <= 0 || h <= 0) return
        (
            rt.bitmap(
                "$base.$part",
            ) ?: rt.bitmap("wasabi.button.$part")
        )?.let { out.add(DrawCmd.Image(it, Rect(x, y, w, h), alpha)) }
    }
    put("top.left", l, t, corner, corner)
    put("top", l + corner, t, mw, corner)
    put("top.right", l + corner + mw, t, corner, corner)
    put("left", l, t + corner, corner, mh)
    put("middle", l + corner, t + corner, mw, mh)
    put("right", l + corner + mw, t + corner, corner, mh)
    put("bottom.left", l, t + corner + mh, corner, corner)
    put("bottom", l + corner, t + corner + mh, mw, corner)
    put("bottom.right", l + corner + mw, t + corner + mh, corner, corner)
}
