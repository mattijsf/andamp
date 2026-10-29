// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.modernskin.skin

import nl.mattix.andamp.modernskin.maki.Value
import kotlin.math.ceil

/**
 * `<componentbucket>`: a strip of the groups whose groupdef has the `windowtype` this bucket's
 * `wndtype` names, in the order the skin defined them, one after another (`vertical="1"` stacked)
 * with `spacing` between them and `leftmargin`/`rightmargin` at the ends. What does not fit is
 * scrolled to, by a script's setScroll or by buttons with the `cb_prevpage`/`cb_nextpage` actions.
 * Winamp Modern's options menu in the config drawer is one.
 */
class ComponentBucket(
    rt: SkinRuntime,
) : Group(rt, "componentbucket") {
    override val className: String get() = "ComponentBucket"
    var windowType: String? = null
        private set
    private var vertical = false
    private var spacing = 0
    private var leftMargin = 0
    private var rightMargin = 0

    /** How far the entries are scrolled, in pixels along the strip. */
    var scroll = 0
        private set

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "wndtype" -> windowType = value.trim().lowercase()
            "vertical" -> vertical = value.toBool()
            "spacing" -> spacing = value.toIntLenient() ?: 0
            "leftmargin" -> leftMargin = value.toIntLenient() ?: 0
            "rightmargin" -> rightMargin = value.toIntLenient() ?: 0
            else -> return false
        }
        return true
    }

    /** Puts [entries] in the strip, from its start. */
    fun fill(entries: List<Group>) {
        for (e in entries) addChild(e)
        place()
    }

    private fun length(c: GuiObject): Int {
        val natural = c.intrinsicSize()
        return (if (vertical) c.h ?: natural?.second else c.w ?: natural?.first) ?: 0
    }

    private fun contentLength(): Int =
        leftMargin + children.sumOf { length(it) } + spacing * (children.size - 1).coerceAtLeast(0) + rightMargin

    private fun viewLength(): Int = laidOut().let { if (vertical) it.height else it.width }

    private fun maxScroll(): Int = (contentLength() - viewLength()).coerceAtLeast(0)

    private fun place() {
        var at = leftMargin - scroll
        for (c in children) {
            if (vertical) {
                c.x = 0
                c.y = at
            } else {
                c.x = at
                c.y = 0
            }
            at += length(c) + spacing
        }
        rt.relayout()
    }

    fun scrollTo(to: Int) {
        val s = to.coerceIn(0, maxScroll())
        if (s == scroll) return
        scroll = s
        place()
    }

    /** One page on ([direction] 1) or back (-1): as many whole entries as the strip shows. */
    fun page(direction: Int) {
        val one = children.firstOrNull()?.let { length(it) + spacing }?.takeIf { it > 0 } ?: return
        val shown = ceil(viewLength().toDouble() / one).toInt().coerceAtLeast(1)
        scrollTo(scroll + direction * shown * one)
    }

    // entries scrolled out of the strip are not drawn there, and take no touch
    override fun hitTest(
        px: Int,
        py: Int,
    ): GuiObject? = if (visible && (px to py) in rect) super.hitTest(px, py) else null

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "setscroll" -> Value.Int(scroll.also { scrollTo(args[0].asInt) })
            "getscroll" -> Value.Int(scroll)
            "getmaxwidth" -> Value.Int(if (vertical) laidOut().width else contentLength())
            "getmaxheight" -> Value.Int(if (vertical) contentLength() else laidOut().height)
            else -> super.makiCall(name, args)
        }
}

/**
 * `<groupxfade>`: shows the group its `groupid` names, filling it, and fades from the group it
 * showed before over `speed` seconds (Wasabi SDK xuigroupxfade). Winamp Modern's options pages
 * are shown in one, a script setting `groupid` when an entry of the options menu is tapped.
 * A page made once is kept for when it is shown again.
 */
class GroupXFade(
    rt: SkinRuntime,
) : Group(rt, "groupxfade") {
    private var speed = 0.25
    private val pages = HashMap<String, Group>()
    private var current: Group? = null

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        when (key.lowercase()) {
            "groupid", "group" -> show(value.trim())
            "speed" -> speed = value.toDoubleOrNull() ?: speed
            else -> return super.setParam(key, value)
        }
        attrs[key.lowercase()] = value
        return true
    }

    private fun show(id: String) {
        val key = id.lowercase()
        val before = current
        if (before != null && pages[key] === before) return
        val page =
            if (key.isEmpty()) {
                null
            } else {
                pages[key] ?: rt.instantiateGroup(id)?.also {
                    it.setParams(FILL)
                    pages[key] = it
                }
            }
        // only the page going and the page coming are drawn
        for (p in pages.values) if (p !== before && p !== page) p.visible = false
        before?.let { fade(it, 0) }
        if (page != null) {
            page.alpha = 0
            page.visible = true
            // on top of the page going
            addChild(page)
            fade(page, OPAQUE)
        }
        current = page
        rt.relayout()
    }

    private fun fade(
        g: Group,
        to: Int,
    ) {
        g.makiCall("settargeta", listOf(Value.Int(to)))
        g.makiCall("settargetspeed", listOf(Value.Num(speed)))
        g.makiCall("gototarget", emptyList())
    }

    // only the page shown takes a touch, not the one fading out
    override fun hitTest(
        px: Int,
        py: Int,
    ): GuiObject? = if (visible) current?.hitTest(px, py) else null

    private companion object {
        const val OPAQUE = 255
        val FILL = mapOf("x" to "0", "y" to "0", "w" to "0", "h" to "0", "relatw" to "1", "relath" to "1")
    }
}
