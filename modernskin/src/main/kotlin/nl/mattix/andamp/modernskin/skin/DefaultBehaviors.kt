// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

import nl.mattix.andamp.modernskin.xml.XmlNode

/**
 * What a group of the default element set does where Winamp's default set runs a script. A
 * groupdef asks for one with `behavior="..."`; it hears the group's xui parameters and runs after
 * every layout pass.
 */
internal abstract class GroupBehavior(
    protected val rt: SkinRuntime,
    protected val group: Group,
) {
    abstract fun onXuiParam(
        key: String,
        value: String,
    )

    open fun afterLayout() = Unit

    companion object {
        fun of(
            rt: SkinRuntime,
            group: Group,
            def: XmlNode,
        ): GroupBehavior? =
            when (def["behavior"]?.lowercase()) {
                "standardframe" -> StandardFrame(rt, group, def["contentrect"])
                "titlebar" -> TitleBar(rt, group)
                else -> null
            }
    }
}

/**
 * A standard frame: `content` names the group placed in its client area (x, y, w, h from
 * `contentrect`, sized with the frame); `padtitleleft` / `padtitleright` go to its title bar;
 * `shade` is the layout a double tap on the title bar switches to.
 */
internal class StandardFrame(
    rt: SkinRuntime,
    group: Group,
    rect: String?,
) : GroupBehavior(rt, group) {
    private val area = rect?.split(',')?.map { it.trim().toIntOrNull() ?: 0 }?.takeIf { it.size == 4 } ?: listOf(0, 0, 0, 0)

    private val titleBar: Group? get() = group.findObject("wasabi.titlebar") as? Group

    override fun onXuiParam(
        key: String,
        value: String,
    ) {
        when (key) {
            "content" -> {
                val content = rt.instantiateGroup(value) ?: return rt.host.log("standard frame: no group $value")
                content.setParam("x", area[0].toString())
                content.setParam("y", area[1].toString())
                content.setParam("w", area[2].toString())
                content.setParam("h", area[3].toString())
                content.setParam("relatw", "1")
                content.setParam("relath", "1")
                content.init(group)
            }

            "padtitleleft", "padtitleright" -> {
                titleBar?.setXmlParam(key, value)
            }

            "shade" -> {
                titleBar?.findObject("mousetrap")?.setParam("dblclickaction", "switch;$value")
            }
        }
    }
}

/**
 * A title bar: the window's name centered on the whole window by its auto width, a streak from
 * the left padding up to it, and one from 1 px after it to the bar's end less the right padding
 * and 1 px. Paddings add up as they are set.
 */
internal class TitleBar(
    rt: SkinRuntime,
    group: Group,
) : GroupBehavior(rt, group) {
    private var padLeft = 0
    private var padRight = 0

    override fun onXuiParam(
        key: String,
        value: String,
    ) {
        when (key) {
            "padtitleleft" -> padLeft += value.toIntLenient() ?: 0
            "padtitleright" -> padRight += value.toIntLenient() ?: 0
            else -> return
        }
        rt.relayout()
    }

    override fun afterLayout() {
        val layout = group.layoutObject ?: return
        val title = group.embedded ?: return
        val textW = title.intrinsicSize()?.first ?: return
        // centered on the window, then counted from the bar's left edge
        val x = (layout.rect.width - textW) / 2 + layout.rect.left - group.rect.left
        place(title, x, textW, relative = false)
        (group.findObject("wasabi.titlebar.streak.left"))?.let { place(it, padLeft, x - padLeft, relative = false) }
        (group.findObject("wasabi.titlebar.streak.right"))?.let {
            place(it, x + textW + 1, -(x + textW + 1 + padRight + 1), relative = true)
        }
    }

    /** Sets x and w (relative to the bar's width when [relative]) only when they change: each change lays out again. */
    private fun place(
        o: GuiObject,
        x: Int,
        w: Int,
        relative: Boolean,
    ) {
        val rel = if (relative) 1 else 0
        val unchanged = o.x == x && o.w == w && o.relatX == 0 && o.relatW == rel
        if (unchanged) return
        o.setParam("x", x.toString())
        o.setParam("relatx", "0")
        o.setParam("w", w.toString())
        o.setParam("relatw", rel.toString())
    }
}
