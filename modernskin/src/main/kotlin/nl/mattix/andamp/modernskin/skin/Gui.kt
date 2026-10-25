// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

import nl.mattix.andamp.modernskin.maki.MakiObject
import nl.mattix.andamp.modernskin.maki.StdApi
import nl.mattix.andamp.modernskin.maki.Value
import kotlin.math.ceil

internal fun String.toBool() = this == "1" || equals("true", ignoreCase = true)

internal fun String.toIntLenient(): Int? = trim().toIntOrNull() ?: trim().toDoubleOrNull()?.toInt()

/** A color as Winamp writes it: "r,g,b", "#rrggbb", or "white" / "black". */
internal fun parseRgb(v: String): Rgb? {
    val name = v.trim().lowercase()
    val parts = v.split(',').map { it.trim().toIntOrNull() }
    return when {
        name == "white" -> {
            Rgb(0xffffff)
        }

        name == "black" -> {
            Rgb(0)
        }

        parts.size >= 3 && parts.all { it != null } -> {
            Rgb((parts[0]!! shl 16) or (parts[1]!! shl 8) or parts[2]!!)
        }

        else -> {
            v
                .trim()
                .removePrefix("#")
                .toIntOrNull(16)
                ?.let { Rgb(it and 0xffffff) }
        }
    }
}

/**
 * Something drawn in a layout: the base of every XML GUI object. Sizes come from the XML; a size
 * left out is the image's. Positions with `relatx="1"` and the like count from the parent's far
 * edge, so a negative value hugs the right or bottom.
 */
@Suppress("TooManyFunctions") // the methods every Wasabi GUI object answers to
open class GuiObject(
    val rt: SkinRuntime,
    val tag: String,
) : MakiObject {
    open val className: String get() = "GuiObject"
    override val makiGuid: String get() = StdApi.guidOf(className) ?: ""

    var id: String = ""
    var parent: Group? = null
    var x: Int? = null
    var y: Int? = null
    var w: Int? = null
    var h: Int? = null

    /** `relat*`: 0 the value itself, 1 the value plus the parent's size, 2 a percentage of the parent's size. */
    var relatX = 0
    var relatY = 0
    var relatW = 0
    var relatH = 0
    var visible = true

    /** `alpha` sets the alpha with and without focus together. */
    var alpha: Int
        get() = activeAlpha
        set(v) {
            activeAlpha = v
            inactiveAlpha = v
        }
    var ghost = false
    var enabled = true
    var sysregion = 0

    /** Whether the XML wrote sysregion: a group that did not takes one from its children. */
    var sysregionSet = false
    var tooltip: String? = null
    var dblClickAction: String? = null

    /** `move`: whether a drag that starts on it moves the window; null when not written (see [SkinRuntime.movesWindow]). */
    var move: Boolean? = null

    /** A title bar's insets from the frame's `padtitleleft`/`padtitleright`. */
    var padLeft = 0
    var padRight = 0
    val attrs = LinkedHashMap<String, String>()

    /** Where it was last laid out, in layout pixels. */
    var rect: Rect = Rect.EMPTY
        protected set

    /** The alpha the ancestors leave, 0..255. */
    var effectiveAlpha = 255
        protected set

    /** `rectrgn="1"`: the object's region is its rectangle, for the window shape and for taps. */
    var rectRgn = false

    /** `activealpha` / `inactivealpha`: opacity while the window has focus, and while it has not. */
    var activeAlpha = 255
    var inactiveAlpha = 255

    /**
     * The alpha it paints with: the one for the window's focus, times the parent's, 254 shown as 255
     * and 1 as 0, and a disabled object at 60 %.
     */
    protected fun paintAlpha(parentAlpha: Int): Int {
        var a = if (rt.hasFocus(layoutObject)) activeAlpha else inactiveAlpha
        a = parentAlpha * a / 255
        if (a == 254) {
            a = 255
        } else if (a == 1) {
            a = 0
        }
        return if (enabled) a else a * 6 / 10
    }

    /**
     * Works out the alpha it and every object inside it paint with again, under a parent painting
     * with [parentAlpha], as a layout pass does but moving nothing: for a change of the focus.
     */
    internal open fun refreshAlpha(parentAlpha: Int) {
        effectiveAlpha = paintAlpha(parentAlpha)
    }

    /** What [paintAlpha] reads of the object itself: its two alphas and whether it is enabled. */
    protected fun paintKey(): Int = (activeAlpha shl 9) or (inactiveAlpha shl 1) or (if (enabled) 1 else 0)

    /**
     * After its alphas or enabled changed with nothing moved: it and what it holds paint with the
     * new alpha from the next frame, with no layout pass. An alpha to or from 0 ([wasZero] before)
     * also changes its window's shape, which a faded out object is no part of.
     */
    protected fun alphaChanged(wasZero: Boolean) {
        if (this is Layout) refreshAlphas() else refreshAlpha(parent?.effectiveAlpha ?: 255)
        if (wasZero != (alpha == 0)) rt.invalidate() else rt.repaint()
    }

    /** `relat*` as written: "%..." is a percentage. */
    private fun relat(value: String): Int = if (value.trimStart().startsWith("%")) 2 else value.toIntLenient() ?: 0

    fun setParams(map: Map<String, String>) {
        for ((k, v) in map) if (!setParam(k, v)) rt.unhandledParams.merge("$tag ${k.lowercase()}", 1, Int::plus)
    }

    @Suppress("CyclomaticComplexMethod") // one arm per XML attribute
    open fun setParam(
        key: String,
        value: String,
    ): Boolean {
        val k = key.lowercase()
        attrs[k] = value
        val placedBefore = if (k in GEOMETRY) placement() else null
        when (k) {
            "id" -> {
                id = value.lowercase()
            }

            "x" -> {
                x = value.toIntLenient()
            }

            "y" -> {
                y = value.toIntLenient()
            }

            "w" -> {
                w = value.toIntLenient()
            }

            "h" -> {
                h = value.toIntLenient()
            }

            // only a fallback for a size not written
            "default_w" -> {
                defaultW = value.toIntLenient()
            }

            "default_h" -> {
                defaultH = value.toIntLenient()
            }

            "default_x", "default_y" -> {
                Unit
            }

            "relatx" -> {
                relatX = relat(value)
            }

            "relaty" -> {
                relatY = relat(value)
            }

            "relatw" -> {
                relatW = relat(value)
            }

            "relath" -> {
                relatH = relat(value)
            }

            // fill the parent, a negative value inset by that much on every side
            "fitparent" -> {
                value.toIntLenient()?.takeIf { it != 0 }?.let { v ->
                    relatW = 1
                    relatH = 1
                    x = if (v < 0) -v else 0
                    y = x
                    w = if (v < 0) 2 * v else 0
                    h = w
                }
            }

            "visible" -> {
                visible = value.toBool()
            }

            "alpha" -> {
                alpha = value.toIntLenient()?.coerceIn(0, 255) ?: 255
            }

            // the look while its window has focus, and while another one has; set after alpha, they win
            "activealpha" -> {
                activeAlpha = value.toIntLenient()?.coerceIn(0, 255) ?: 255
            }

            "inactivealpha" -> {
                inactiveAlpha = value.toIntLenient()?.coerceIn(0, 255) ?: 255
            }

            "ghost" -> {
                ghost = value.toBool()
            }

            // the object's region is its whole rectangle (Wasabi SDK guiobjwnd, basewnd)
            "rectrgn" -> {
                rectRgn = value.toBool()
            }

            // regionop is sysregion's other name (Wasabi SDK guiobjwnd)
            "sysregion", "regionop" -> {
                sysregion = value.toIntLenient() ?: 0
                sysregionSet = true
            }

            "tooltip" -> {
                tooltip = value
            }

            "dblclickaction" -> {
                dblClickAction = value
            }

            "enabled" -> {
                enabled = value.toBool()
            }

            "padtitleleft" -> {
                padLeft = value.toIntLenient() ?: 0
            }

            "padtitleright" -> {
                padRight = value.toIntLenient() ?: 0
            }

            "move" -> {
                move = value.toBool()
            }

            "name", "renderbasetexture", "cursor", "dropdownlist", "droptarget", "notify", "focusonclick" -> {
                Unit
            }

            else -> {
                return false
            }
        }
        // a new position or size needs a new layout (a script setting the same one again does not),
        // anything else only a redraw
        if (placedBefore == null) {
            rt.invalidate()
        } else if (placement() != placedBefore) {
            rt.relayout()
        }
        return true
    }

    /** What the geometry parameters set, to tell whether one of them changed anything. */
    private fun placement(): List<Any?> =
        listOf(x, y, w, h, defaultW, defaultH, relatX, relatY, relatW, relatH, padLeft, padRight)

    /** `default_w` / `default_h`: the size of a group that writes none. */
    var defaultW: Int? = null
    var defaultH: Int? = null

    /** The natural size when the XML gives none: an image's size, mostly. */
    open fun intrinsicSize(): Pair<Int, Int>? = null

    open fun layout(
        parentRect: Rect,
        parentAlpha: Int,
    ) {
        val pw = parentRect.width
        val ph = parentRect.height

        // 1 adds the parent's size, 2 takes a percentage of it
        fun place(
            v: Int,
            relat: Int,
            size: Int,
        ) = when (relat) {
            1 -> v + size
            2 -> (size * (v / 100.0)).toInt()
            else -> v
        }
        val left = place(x ?: 0, relatX, pw)
        val top = place(y ?: 0, relatY, ph)
        val intrinsic by lazy { intrinsicSize() }
        // a size not written is the natural one, whatever relat* says; a written 0 is 0
        val width = w?.let { place(it, relatW, pw) } ?: intrinsic?.first ?: 0
        val height = h?.let { place(it, relatH, ph) } ?: intrinsic?.second ?: 0
        rect = Rect(parentRect.left + left + padLeft, parentRect.top + top, maxOf(0, width - padLeft - padRight), height)
        effectiveAlpha = paintAlpha(parentAlpha)
    }

    open fun draw(out: MutableList<DrawCmd>) = Unit

    /** How its region joins its parent's: 1 added, 2 intersected, -1 cut, -2 cut from the whole window. */
    open fun regionOp(): Int = sysregion

    /** Its own region before its children's: its rectangle (Wasabi SDK basewnd, rectrgn on by default). */
    open fun ownRegion(): Region? = if (rect.width > 0 && rect.height > 0) Region.Box(rect) else null

    /**
     * Its region with its children's joined in by their [regionOp]; children's whole-window cuts go
     * to [windowCuts]. Null when it is empty.
     */
    open fun composedRegion(windowCuts: MutableList<Region>): Region? = ownRegion()

    /** The topmost object under a point, or null. Ghost objects let touches through. */
    open fun hitTest(
        px: Int,
        py: Int,
    ): GuiObject? = if (visible && takesTouchAt(px, py)) this else null

    /** Whether a touch at the point lands on it: not a ghost, enabled, and inside its non-empty rectangle. */
    protected fun takesTouchAt(
        px: Int,
        py: Int,
    ): Boolean = !ghost && enabled && rect.width > 0 && rect.height > 0 && (px to py) in rect

    // ---- targets: a script moves, resizes or fades an object over time --------------------

    /** Target x, y, w, h and alpha set by `setTarget*`; null where none was set. */
    private val target = arrayOfNulls<Int>(5)
    private var targetSeconds = 0.0
    private var moveFrom: IntArray? = null
    private var moveTo: Array<Int?>? = null
    private var moveStart: Long? = null
    private var moveMillis = 0L

    private fun current() = intArrayOf(x ?: 0, y ?: 0, w ?: rect.width, h ?: rect.height, alpha)

    private fun goToTarget() {
        moveFrom = current()
        moveTo = target.copyOf()
        target.fill(null)
        moveMillis = (targetSeconds * 1000).toLong()
        moveStart = rt.now()
        rt.moving.add(this)
        stepTarget(rt.now())
    }

    private fun cancelTarget() {
        moveStart = null
        rt.moving.remove(this)
    }

    /** Moves one frame toward the target; at the end the object sits there and hears `onTargetReached`. */
    fun stepTarget(now: Long) {
        val start = moveStart ?: return
        val from = moveFrom ?: return
        val to = moveTo ?: return
        val f = if (moveMillis <= 0) 1.0 else ((now - start).toDouble() / moveMillis).coerceIn(0.0, 1.0)
        // slow start, slow end: Winamp 5.666's config drawer follows (1 - cos(pi * t)) / 2 (recorded at 60 fps)
        val eased = (1 - kotlin.math.cos(Math.PI * f)) / 2

        fun lerp(i: Int) = to[i]?.let { Math.round(from[i] + (it - from[i]) * eased).toInt() }
        val place = listOf(x, y, w, h)
        val wasZero = alpha == 0
        val paint = paintKey()
        lerp(0)?.let { x = it }
        lerp(1)?.let { y = it }
        lerp(2)?.let { w = it }
        lerp(3)?.let { h = it }
        lerp(4)?.let { alpha = it.coerceIn(0, 255) }
        // a fade only repaints; the windows are laid out again only when something moved or resized
        if (listOf(x, y, w, h) != place) {
            rt.relayout()
        } else if (paintKey() != paint) {
            alphaChanged(wasZero)
        }
        if (f >= 1.0) {
            moveStart = null
            rt.moving.remove(this)
            dispatch("ontargetreached")
        }
    }

    /** The rectangle after any pending layout, for scripts that read it right after a change. */
    fun laidOut(): Rect {
        rt.ensureLayout()
        return rect
    }

    val layoutObject: Layout?
        get() {
            var o: GuiObject? = this
            while (o != null && o !is Layout) o = o.parent
            return o as? Layout
        }

    fun dispatch(
        event: String,
        vararg args: Value,
    ): Int = rt.dispatch(this, event, args.toList())

    open fun pointerDown(
        px: Int,
        py: Int,
    ) {
        dispatch("onleftbuttondown", Value.Int(px), Value.Int(py))
    }

    open fun pointerMove(
        px: Int,
        py: Int,
    ) {
        dispatch("onmousemove", Value.Int(px), Value.Int(py))
    }

    open fun pointerUp(
        px: Int,
        py: Int,
        inside: Boolean,
    ) {
        dispatch("onleftbuttonup", Value.Int(px), Value.Int(py))
    }

    /**
     * The press ends without a release (a menu or the host took the touch): the object lets go
     * with no click or final position, and scripts hear nothing; a Slider's hear the button go up.
     */
    open fun pointerCancel() = Unit

    /** A right click at a point of its window: the right button goes down and up. */
    open fun rightClick(
        px: Int,
        py: Int,
    ) {
        dispatch("onrightbuttondown", Value.Int(px), Value.Int(py))
        dispatch("onrightbuttonup", Value.Int(px), Value.Int(py))
    }

    open fun doubleTap(
        px: Int,
        py: Int,
    ) {
        dispatch("onleftbuttondblclk", Value.Int(px), Value.Int(py))
        dblClickAction?.let { runAction(it) }
    }

    /** `action;param;target` as a button or a double click does it. */
    fun runAction(spec: String) {
        val parts = spec.split(';')
        rt.action(this, parts[0].trim(), parts.getOrNull(1)?.trim(), parts.getOrNull(2)?.trim())
    }

    /** Puts this object into [group], as a script does with `obj.init(group)`. */
    fun init(group: Group) {
        group.addChild(this)
        rt.relayout()
    }

    fun show() {
        if (!visible) {
            visible = true
            shownChanged()
            if (parentShown()) announceVisible(true)
        }
    }

    fun hide() {
        if (visible) {
            visible = false
            shownChanged()
            if (parentShown()) announceVisible(false)
        }
    }

    /** Whether every group above is shown: only then does a change of this object's own flag show (Wasabi SDK basewnd). */
    private fun parentShown(): Boolean {
        var p = parent
        while (p != null) {
            if (!p.visible) return false
            p = p.parent
        }
        return true
    }

    /** onSetVisible for this object and, down the tree, every child whose own flag is set. */
    fun announceVisible(shown: Boolean) {
        dispatch("onsetvisible", Value.Int(if (shown) 1 else 0))
        if (this is Group) for (c in children.toList()) if (c.visible) c.announceVisible(shown)
    }

    // a frame gives a hidden pane's room to the other one
    private fun shownChanged() = if (parent is FrameSplitter) rt.relayout() else rt.invalidate()

    /**
     * `findObject(id)` as Wasabi's windows do it: this object's children, then their subtrees,
     * then up one parent at a time, so any object in the window is found from anywhere in it.
     */
    fun findObjectChain(id: String): GuiObject? {
        (this as? Group)?.findObject(id)?.let { return it }
        var p = parent
        while (p != null) {
            p.findObject(id)?.let { return it }
            p = p.parent
        }
        return null
    }

    /** `setXmlParam(key, value)` from a script; a group keeps what it cannot use for its scripts. */
    open fun setXmlParam(
        key: String,
        value: String,
    ) {
        val before = attrs[key.lowercase()]
        val paint = paintKey()
        val wasZero = alpha == 0
        setParam(key, value)
        // what an object keeps for itself (a layer's image) shows on the next frame too
        if (before != value) rt.invalidate()
        if (paintKey() != paint) alphaChanged(wasZero)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // one arm per script method of GuiObject
    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> {
                Value.Str(className)
            }

            "getid" -> {
                Value.Str(id)
            }

            "show" -> {
                Value.Null.also { show() }
            }

            "hide" -> {
                Value.Null.also { hide() }
            }

            "isvisible" -> {
                Value.Bool(visible)
            }

            "getleft" -> {
                Value.Int(laidOut().left)
            }

            "gettop" -> {
                Value.Int(laidOut().top)
            }

            "getwidth" -> {
                Value.Int(laidOut().width)
            }

            "getheight" -> {
                Value.Int(laidOut().height)
            }

            "getguix" -> {
                Value.Int(x ?: 0)
            }

            "getguiy" -> {
                Value.Int(y ?: 0)
            }

            "getguiw" -> {
                Value.Int(w ?: 0)
            }

            "getguih" -> {
                Value.Int(h ?: 0)
            }

            "getguirelatx" -> {
                Value.Int(relatX)
            }

            "getguirelaty" -> {
                Value.Int(relatY)
            }

            "getguirelatw" -> {
                Value.Int(relatW)
            }

            "getguirelath" -> {
                Value.Int(relatH)
            }

            "getautowidth" -> {
                Value.Int(intrinsicSize()?.first ?: rect.width)
            }

            "getautoheight" -> {
                Value.Int(intrinsicSize()?.second ?: rect.height)
            }

            "resize" -> {
                x = args[0].asInt
                y = args[1].asInt
                w = args[2].asInt
                h = args[3].asInt
                relatX = 0
                relatY = 0
                relatW = 0
                relatH = 0
                rt.relayout()
                Value.Null
            }

            "setxmlparam" -> {
                Value.Null.also { setXmlParam(args[0].asString.lowercase(), args[1].asString) }
            }

            "getxmlparam" -> {
                Value.Str(attrs[args[0].asString.lowercase()] ?: "")
            }

            "setalpha" -> {
                Value.Null.also {
                    val paint = paintKey()
                    val wasZero = alpha == 0
                    alpha = args[0].asInt.coerceIn(0, 255)
                    if (paintKey() != paint) alphaChanged(wasZero)
                }
            }

            "getalpha" -> {
                Value.Int(alpha)
            }

            "getparent" -> {
                Value.of(parent)
            }

            "getparentlayout" -> {
                Value.of(layoutObject)
            }

            "findobject" -> {
                Value.of(
                    findObjectChain(args[0].asString).also {
                        if (it ==
                            null
                        ) {
                            rt.host.log("findobject miss: $this -> ${args[0].asString}")
                        }
                    },
                )
            }

            "findobjectxy" -> {
                Value.of(rt.hitTest(args[0].asInt, args[1].asInt))
            }

            "getname" -> {
                Value.Str(attrs["name"] ?: id)
            }

            "bringtofront", "bringtoback", "setfocus", "invalidate", "repaint",
            "setcursor", "setenabled", "setcapture", "releasecapture", "setregion", "setregionfrommap",
            "fx_setenabled", "fx_setwrap", "fx_setrect", "fx_setbgfx", "fx_setclear", "fx_setspeed",
            "fx_setlocalized", "fx_setbilinear", "fx_setalphamode", "fx_setgridsize", "fx_update", "fx_restart",
            "fx_setsize",
            -> {
                if (name == "setenabled" && enabled != args[0].truthy) {
                    enabled = args[0].truthy
                    // scripts hear it (Wasabi SDK guiobjwnd)
                    dispatch("onenable", Value.Int(if (enabled) 1 else 0))
                }
                Value.Null
            }

            "getenabled" -> {
                Value.Bool(enabled)
            }

            "settargetx" -> {
                Value.Null.also { target[0] = args[0].asInt }
            }

            "settargety" -> {
                Value.Null.also { target[1] = args[0].asInt }
            }

            "settargetw" -> {
                Value.Null.also { target[2] = args[0].asInt }
            }

            "settargeth" -> {
                Value.Null.also { target[3] = args[0].asInt }
            }

            "settargeta" -> {
                Value.Null.also { target[4] = args[0].asInt.coerceIn(0, 255) }
            }

            "settargetspeed" -> {
                Value.Null.also { targetSeconds = args[0].asDouble }
            }

            "gototarget" -> {
                Value.Null.also { goToTarget() }
            }

            "isgoingtotarget" -> {
                Value.Bool(moveStart != null)
            }

            "canceltarget" -> {
                Value.Null.also { cancelTarget() }
            }

            "clienttoscreenx", "clienttoscreenw", "screentoclientx", "screentoclientw", "clienttoscreeny", "clienttoscreenh",
            "screentoclienty", "screentoclienth",
            -> {
                args[0]
            }

            // the object hears its own onAction; a plain object answers 1 (Wasabi SDK basewnd, guiobjwnd)
            "sendaction" -> {
                Value.Int(1).also {
                    rt.dispatch(this, "onaction", listOf(args[0], args[1], args[2], args[3], args[4], args[5], Value.Obj(this)))
                }
            }

            "istransparencysafe", "islayoutanimationsafe" -> {
                Value.Bool(true)
            }

            "getguid" -> {
                Value.Str("")
            }

            "ismouseoverrect" -> {
                Value.Bool(false)
            }

            "isactive" -> {
                Value.Bool(true)
            }

            else -> {
                null
            }
        }

    override fun toString() = "$tag#$id"

    private companion object {
        val GEOMETRY =
            setOf(
                "x",
                "y",
                "w",
                "h",
                "default_x",
                "default_y",
                "default_w",
                "default_h",
                "relatx",
                "relaty",
                "relatw",
                "relath",
                "fitparent",
                "padtitleleft",
                "padtitleright",
            )
    }
}

/** The size of a group that writes none and has no background (Wasabi SDK basewnd). */
private const val UNSIZED_W = 320
private const val UNSIZED_H = 200

/** Gui objects that hold others. A `<group>` whose id names a `<groupdef>` takes that definition's children. */
open class Group(
    rt: SkinRuntime,
    tag: String = "group",
) : GuiObject(rt, tag) {
    override val className: String get() = "Group"
    val children = ArrayList<GuiObject>()
    var background: String? = null

    /** Off until `background=` turns it on, so a later `drawbackground="0"` wins. */
    var drawBackground = false
    var instanceId: String? = null
    var autoWidthSource: String? = null
    var autoHeightSource: String? = null
    val systemObjects = ArrayList<SystemObject>()

    /** What the engine does for this group where Winamp's default set runs a script. */
    internal var behavior: GroupBehavior? = null

    /** Attributes a xui tag got that are no GUI attributes; sent to its scripts as `onSetXuiParam`. */
    val xuiParams = LinkedHashMap<String, String>()

    /**
     * `embed_xui` on a group definition: the id of the child the xui object exposes to scripts
     * (Bento's info lines are their text, its tab buttons their mousetrap button). The group is
     * the object a script finds by the instance id: its geometry and visibility are the group's,
     * methods and parameters the group has no use for go to that child, and the child's events
     * also fire on the group.
     */
    var embedXui: String? = null

    /** The child named by [embedXui], if any. */
    val embedded: GuiObject? get() = embedXui?.let { e -> children.firstOrNull { it.id == e } ?: findObject(e) }

    override fun setXmlParam(
        key: String,
        value: String,
    ) {
        // every attribute lands in attrs, a xui parameter too
        if (attrs[key.lowercase()] != value) rt.invalidate()
        val paint = paintKey()
        val wasZero = alpha == 0
        val known = setParam(key, value)
        if (paintKey() != paint) alphaChanged(wasZero)
        if (known) return
        // a xui parameter: for the group's scripts, and for the child it exposes
        xuiParams[key] = value
        rt.deliverXuiParams(this)
        embedded?.setParam(key, value)
    }

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "background" -> {
                background = value
                drawBackground = true
            }

            "drawbackground" -> {
                drawBackground = value.toBool()
            }

            "instance_id", "instanceid" -> {
                instanceId = value.lowercase()
            }

            "autowidthsource" -> {
                autoWidthSource = value
            }

            "autoheightsource" -> {
                autoHeightSource = value
            }

            "embed_xui" -> {
                embedXui = value.lowercase()
            }

            "inherit_group", "xuitag", "allowzerosize", "lockminmax" -> {
                Unit
            }

            "propagatesize", "design", "sysregion_offset" -> {
                Unit
            }

            else -> {
                return false
            }
        }
        return true
    }

    fun addChild(child: GuiObject) {
        child.parent?.children?.remove(child)
        child.parent = this
        children.add(child)
        rt.invalidate()
    }

    fun findObject(wanted: String): GuiObject? {
        val lower = wanted.lowercase()
        for (c in children) {
            if (c.id == lower || (c is Group && c.instanceId == lower)) return c
        }
        for (c in children) {
            if (c is Group) c.findObject(lower)?.let { return it }
        }
        return null
    }

    override fun intrinsicSize(): Pair<Int, Int>? {
        val bg = background?.let { rt.bitmapSize(it) }
        // the source's own size plus what its relative w/h leaves around it
        val aw =
            autoWidthSource?.let { findObject(it) }?.let { src ->
                autoSize(src.intrinsicSize()?.first, src.x, src.w, src.relatX, src.relatW)
            }
        val ah =
            autoHeightSource?.let { findObject(it) }?.let { src ->
                autoSize(src.intrinsicSize()?.second, src.y, src.h, src.relatY, src.relatH)
            }
        // then its background's size, then Wasabi's 320 x 200
        return (aw ?: defaultW ?: bg?.first ?: UNSIZED_W) to (ah ?: defaultH ?: bg?.second ?: UNSIZED_H)
    }

    /**
     * A size from an autowidthsource: its natural size plus its offset, or less its relative w
     * (the room it leaves); none when it is placed from the far side.
     */
    private fun autoSize(
        natural: Int?,
        pos: Int?,
        size: Int?,
        relatPos: Int,
        relatSize: Int,
    ): Int? {
        val rs = if (size == null) 0 else relatSize
        if (natural == null || relatPos != 0 || rs > 1) return null
        return if (rs == 0) natural + (pos ?: 0) else natural - (size ?: 0)
    }

    override fun layout(
        parentRect: Rect,
        parentAlpha: Int,
    ) {
        super.layout(parentRect, parentAlpha)
        for (c in children) c.layout(rect, effectiveAlpha)
    }

    override fun refreshAlpha(parentAlpha: Int) {
        super.refreshAlpha(parentAlpha)
        for (c in children) c.refreshAlpha(effectiveAlpha)
    }

    /** Lays this group out to fill [area] exactly, as a frame pane; its own position attributes do not apply. */
    fun layoutIn(
        area: Rect,
        parentAlpha: Int,
    ) {
        rect = area
        effectiveAlpha = paintAlpha(parentAlpha)
        for (c in children) c.layout(rect, effectiveAlpha)
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible) return
        // a shaped group draws like any other: only the window's shape cuts
        val bg = background?.takeIf { drawBackground }?.let { rt.bitmap(it) }?.let { DrawCmd.Image(it, rect, effectiveAlpha) }
        bg?.let { out.add(it.also { c -> c.ownedBy = this }) }
        drawChildren(out)
    }

    private fun drawChildren(out: MutableList<DrawCmd>) {
        // children paint only inside their group (Bento's title bar cuts its 20 px menu pictures at 18)
        out.add(DrawCmd.Clip(rect).also { it.ownedBy = this })
        for (c in children) {
            val from = out.size
            c.draw(out)
            for (i in from until out.size) if (out[i].ownedBy == null) out[i].ownedBy = c
        }
        out.add(DrawCmd.Unclip)
    }

    /** A group that wrote no sysregion shapes the window when one of its children does. */
    override fun regionOp(): Int =
        if (sysregionSet || this is Layout) {
            sysregion
        } else if (children.any { it.regionOp() != 0 }) {
            1
        } else {
            0
        }

    /** Its rectangle with rectrgn, else its background's opaque pixels stretched over it, else nothing. */
    override fun ownRegion(): Region? {
        if (rect.width <= 0 || rect.height <= 0) return null
        if (rectRgn) return Region.Box(rect)
        return background?.let { rt.bitmap(it) }?.let { Region.Pic(DrawCmd.Image(it, rect, 255)) }
    }

    override fun composedRegion(windowCuts: MutableList<Region>): Region? {
        val add = ArrayList<Region>()
        val keep = ArrayList<Region>()
        val cut = ArrayList<Region>()
        for (c in children) {
            // hidden or faded out, it is no part of the shape
            if (!c.visible || c.alpha == 0) continue
            val r = c.composedRegion(windowCuts) ?: continue
            when (c.regionOp()) {
                1 -> add.add(r)
                2 -> keep.add(r)
                -1 -> cut.add(r)
                -2 -> windowCuts.add(r)
            }
        }
        val base = ownRegion()
        if (base == null && add.isEmpty()) return null
        return Region.Op(base, add, keep, cut)
    }

    override fun hitTest(
        px: Int,
        py: Int,
    ): GuiObject? {
        if (!visible) return null
        for (c in children.asReversed()) c.hitTest(px, py)?.let { return it }
        // a group whose region is its rectangle takes what its children leave, and blocks what lies under it
        return if (rectRgn && takesTouchAt(px, py)) this else null
    }

    @Suppress("CyclomaticComplexMethod") // one arm per script method a group answers
    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getobject" -> {
                Value.of(
                    findObject(args[0].asString).also {
                        if (it ==
                            null
                        ) {
                            rt.host.log("getobject miss: $this -> ${args[0].asString}")
                        }
                    },
                )
            }

            // a xui tab button's activation is its embedded button's (suicore.maki activates Bento's tabs)
            "setactivated" -> {
                embedded?.makiCall(name, args) ?: Value.Null
            }

            "getactivated" -> {
                embedded?.makiCall(name, args) ?: Value.Bool(false)
            }

            "getnumobjects", "getnumchildren" -> {
                Value.Int(children.size)
            }

            "enumchildren" -> {
                Value.of(children.getOrNull(args[0].asInt))
            }

            "init" -> {
                Value.Null.also { (args[0].asObject as? Group)?.let { init(it) } }
            }

            "enumobject" -> {
                Value.of(children.getOrNull(args[0].asInt))
            }

            // the last pointer, relative to this group
            "getmouseposx" -> {
                Value.Int(rt.lastPointer.first - rect.left)
            }

            "getmouseposy" -> {
                Value.Int(rt.lastPointer.second - rect.top)
            }

            "islayout" -> {
                Value.Bool(this is Layout)
            }

            "getparentlayout" -> {
                Value.of(layoutObject)
            }

            "lockminmaxsize", "unlockminmaxsize" -> {
                Value.Null
            }

            "getxmlparam" -> {
                Value.Str(attrs[args[0].asString.lowercase()] ?: embedded?.attrs?.get(args[0].asString.lowercase()) ?: "")
            }

            else -> {
                super.makiCall(name, args) ?: embedded?.makiCall(name, args)
            }
        }
}

/**
 * `<Wasabi:Frame>`: two groups side by side (`orientation="v"`: `left`/`right`) or stacked
 * (`"h"`: `top`/`bottom`) with a divider between them, as Wasabi's FrameWnd lays them out: the
 * divider sits `width` (or `height`) pixels from the `from` side, kept between `minwidth` and
 * `maxwidth` (a negative maximum counts from the far edge), and takes 8 px. Bento's main window
 * is built from these.
 */
class FrameSplitter(
    rt: SkinRuntime,
) : Group(rt, "wasabi:frame") {
    private var vertical = true
    private var fromFar = false
    private var pos = DEFAULT_WIDTH
    private var minPos = 0
    private var maxPos: Int? = null
    private var first: Group? = null
    private var second: Group? = null

    /** The divider and grabber pictures ("" for none; null for the default set's), and whether there is a divider at all. */
    private var divider: String? = null
    private var grabber: String? = null
    private var resizable = true
    private var sizerRect = Rect.EMPTY

    @Suppress("CyclomaticComplexMethod") // one arm per XML attribute a frame takes
    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "orientation" -> vertical = value.lowercase().startsWith("v")
            "from" -> fromFar = value.lowercase().let { it.startsWith("r") || it.startsWith("b") }
            "width", "height" -> pos = value.toIntLenient() ?: 0
            "minwidth", "minheight" -> minPos = value.toIntLenient() ?: 0
            "maxwidth", "maxheight" -> maxPos = value.toIntLenient()
            "left", "top" -> first = rt.instantiateGroup(value)?.also { addChild(it) }
            "right", "bottom" -> second = rt.instantiateGroup(value)?.also { addChild(it) }
            "vbitmap", "hbitmap" -> divider = value.takeUnless { it.equals("empty", ignoreCase = true) } ?: ""
            "vgrabber", "hgrabber" -> grabber = value.takeUnless { it.equals("empty", ignoreCase = true) } ?: ""
            "resizable", "resizeable" -> resizable = value.toBool()
            "jump" -> Unit
            else -> return false
        }
        return true
    }

    override fun layout(
        parentRect: Rect,
        parentAlpha: Int,
    ) {
        super.layout(parentRect, parentAlpha)
        val total = if (vertical) rect.width else rect.height
        // one child hidden: the other takes the whole frame (FrameWnd::onResize)
        val a0 = first
        val b0 = second
        if (a0 != null && b0 != null && a0.visible != b0.visible) {
            (if (a0.visible) a0 else b0).layoutIn(rect, effectiveAlpha)
            for (c in children) if (c !== first && c !== second) c.layout(rect, effectiveAlpha)
            return
        }
        val sizer = if (resizable) SIZER else 0
        var p = pos
        maxPos?.let { m -> p = minOf(p, if (m < 0) total + m else m) }
        p = p.coerceIn(minOf(minPos, maxOf(0, total - sizer)), maxOf(0, total - sizer))
        // vertical: the divider comes out of the pane the position is not measured from; horizontal:
        // the top pane always gets the position's share, the bottom one what is left after the
        // divider (Wasabi SDK FrameWnd::onResize)
        val firstLen =
            when {
                !fromFar -> p
                vertical -> total - p - sizer
                else -> total - p
            }
        val secondStart = firstLen + sizer
        sizerRect = span(firstLen, sizer)
        val secondLen = maxOf(0, total - secondStart)
        val a = span(0, firstLen)
        val b = span(secondStart, secondLen)
        first?.layoutIn(a, effectiveAlpha)
        second?.layoutIn(b, effectiveAlpha)
        for (c in children) if (c !== first && c !== second) c.layout(rect, effectiveAlpha)
    }

    /**
     * The strip of the frame that starts [start] pixels in along the split and is [len] pixels
     * long, across the full height of a vertical frame or the full width of a horizontal one.
     */
    private fun span(
        start: Int,
        len: Int,
    ): Rect =
        if (vertical) {
            Rect(rect.left + start, rect.top, len, rect.height)
        } else {
            Rect(rect.left, rect.top + start, rect.width, len)
        }

    /**
     * The divider, stretched over the 8 px between the panes and 2 px past each end, its grabber
     * centered on it when the divider is longer (Wasabi SDK FrameWnd::onPaint).
     */
    override fun draw(out: MutableList<DrawCmd>) {
        super.draw(out)
        if (!visible || !resizable) return
        if (sizerRect.width <= 0 || sizerRect.height <= 0) return
        val a0 = first
        val b0 = second
        if (a0 != null && b0 != null && a0.visible != b0.visible) return
        val bar = divider ?: if (vertical) "wasabi.framewnd.verticaldivider" else "wasabi.framewnd.horizontaldivider"
        val grip = grabber ?: if (vertical) "wasabi.framewnd.verticalgrabber" else "wasabi.framewnd.horizontalgrabber"
        val r = sizerRect
        val long =
            if (vertical) {
                Rect(
                    r.left,
                    r.top - 2,
                    r.width,
                    r.height + 4,
                )
            } else {
                Rect(r.left - 2, r.top, r.width + 4, r.height)
            }
        if (bar.isNotEmpty()) {
            rt.bitmap(bar)?.let {
                out.add(
                    DrawCmd.Image(it, long, effectiveAlpha).also { c -> c.ownedBy = this },
                )
            }
        }
        if (grip.isNotEmpty()) drawGrabber(out, grip, long)
    }

    /** The grabber picture [grip] centered on the divider's rectangle [long], when the divider is longer. */
    private fun drawGrabber(
        out: MutableList<DrawCmd>,
        grip: String,
        long: Rect,
    ) {
        val (gw, gh) = rt.bitmapSize(grip) ?: return
        val len = if (vertical) long.height else long.width
        val glen = if (vertical) gh else gw
        if (len <= glen) return
        val cut = (len - glen) / 2
        val g =
            if (vertical) {
                Rect(long.left, long.top + cut, long.width, len - 2 * cut)
            } else {
                Rect(
                    long.left + cut,
                    long.top,
                    len - 2 * cut,
                    long.height,
                )
            }
        rt.bitmap(grip)?.let { out.add(DrawCmd.Image(it, g, effectiveAlpha).also { c -> c.ownedBy = this }) }
    }

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getposition" -> {
                Value.Int(pos)
            }

            "setposition" -> {
                Value.Null.also {
                    pos = args[0].asInt
                    rt.relayout()
                }
            }

            else -> {
                super.makiCall(name, args)
            }
        }

    private companion object {
        const val SIZER = 8

        /** A frame that names no width puts its divider 128 px in (Wasabi SDK xuiframe). */
        const val DEFAULT_WIDTH = 128
    }
}

/**
 * `<AlbumArt>`: the current song's cover. Nothing plays with art here, so it shows the skin's
 * `notfoundImage` at its own size, centered, as Winamp 5.666 does (Bento's llama).
 */
class AlbumArt(
    rt: SkinRuntime,
) : GuiObject(rt, "albumart") {
    override val className: String get() = "GuiObject"
    private var notFound: String? = null

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        return when (key.lowercase()) {
            "notfoundimage" -> {
                notFound = value
                true
            }

            "image", "stretch", "keepaspect" -> {
                true
            }

            else -> {
                false
            }
        }
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible || rect.width <= 0 || rect.height <= 0) return
        val id = notFound ?: return
        val bm = rt.bitmap(id) ?: return
        val (w, h) = rt.bitmapSize(id) ?: return
        out.add(DrawCmd.Clip(rect))
        out.add(DrawCmd.Image(bm, Rect(rect.left + (rect.width - w) / 2, rect.top + (rect.height - h) / 2, w, h), effectiveAlpha))
        out.add(DrawCmd.Unclip)
    }
}

/** `<rect>`: a rectangle in one color, filled or as a 1 px outline (Bento's video pane). */
class RectObj(
    rt: SkinRuntime,
) : GuiObject(rt, "rect") {
    override val className: String get() = "GuiObject"
    private var color = Rgb(0)
    private var colorId: String? = null
    private var filled = false

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        return when (key.lowercase()) {
            // a skin color by name, else one written out (Winamp2000SP4: "wasabi.list.background", "white")
            "color" -> {
                colorId = value.takeIf { rt.color(it) != null }
                parseRgb(value)?.let { color = it }
                true
            }

            "filled" -> {
                filled = value.toBool()
                true
            }

            "cursor" -> {
                true
            }

            else -> {
                false
            }
        }
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible || rect.width <= 0 || rect.height <= 0) return
        val color = colorId?.let { rt.color(it) } ?: color
        if (filled) {
            out.add(DrawCmd.Fill(rect, color, effectiveAlpha))
        } else {
            out.add(DrawCmd.Fill(Rect(rect.left, rect.top, rect.width, 1), color, effectiveAlpha))
            out.add(DrawCmd.Fill(Rect(rect.left, rect.bottom - 1, rect.width, 1), color, effectiveAlpha))
            out.add(DrawCmd.Fill(Rect(rect.left, rect.top, 1, rect.height), color, effectiveAlpha))
            out.add(DrawCmd.Fill(Rect(rect.right - 1, rect.top, 1, rect.height), color, effectiveAlpha))
        }
    }
}

/** One look of a container: the window at its normal size, or shaded. */
class Layout(
    rt: SkinRuntime,
) : Group(rt, "layout") {
    override val className: String get() = "Layout"
    var container: Container? = null
    var minW = 0
    var minH = 0
    var maxW = 0
    var maxH = 0

    /** `desktopalpha="1"`: Winamp draws the window with per-pixel transparency and no region (CornerAmp). */
    var desktopAlpha = false

    /**
     * `snapadjustleft`, `top`, `right` and `bottom`: how far in from each edge Winamp docks and
     * snaps the window, past a part that shows nothing (Winamp Modern's closed drawer under its
     * main window); a script changes them with snapAdjust. The right and bottom ones trim
     * [snappedSize]; the left and top ones are kept for scripts to read.
     */
    var snapLeft = 0
    var snapTop = 0
    var snapRight = 0
    var snapBottom = 0

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "minimum_w" -> minW = value.toIntLenient() ?: 0
            "minimum_h" -> minH = value.toIntLenient() ?: 0
            "maximum_w" -> maxW = value.toIntLenient() ?: 0
            "maximum_h" -> maxH = value.toIntLenient() ?: 0
            "desktopalpha" -> desktopAlpha = value == "1"
            "linkwidth" -> linkWidth = value.lowercase()
            "linkheight" -> linkHeight = value.lowercase()
            "nofocusapponclick", "owner", "nodock", "lockminmax", "scalable", "alphabackground" -> Unit
            else -> return setSnapParam(key, value)
        }
        return true
    }

    /** A `snapadjust*` attribute; false for any other. */
    private fun setSnapParam(
        key: String,
        value: String,
    ): Boolean {
        val px = value.toIntLenient() ?: 0
        when (key.lowercase()) {
            "snapadjustleft" -> snapLeft = px
            "snapadjusttop" -> snapTop = px
            "snapadjustright" -> snapRight = px
            "snapadjustbottom" -> snapBottom = px
            else -> return false
        }
        return true
    }

    /** `linkwidth` / `linkheight`: the layout of the same container whose width (height) this one shares. */
    private var linkWidth: String? = null
    private var linkHeight: String? = null

    /** The size its own attributes give: its w/h, else its default size, background or 320 x 200. */
    private fun ownSize(): Pair<Int, Int> {
        val bg = intrinsicSize()
        return (w?.takeIf { it > 0 } ?: bg?.first ?: minW) to (h?.takeIf { it > 0 } ?: bg?.second ?: minH)
    }

    /**
     * Size for the screen: its own, a width (height) not written taken from the layout it links to
     * (Winamp2000SP4's playlist from its 550 px shade layout), then kept within the minimum and
     * maximum.
     */
    val size: Pair<Int, Int>
        get() {
            val own = ownSize()

            fun linked(id: String?) = id?.let { container?.layout(it) }?.takeIf { it !== this }?.ownSize()
            var width = if (w == null) linked(linkWidth)?.first ?: own.first else own.first
            var height = if (h == null) linked(linkHeight)?.second ?: own.second else own.second
            if (minW > 0) width = maxOf(width, minW)
            if (minH > 0) height = maxOf(height, minH)
            if (maxW > 0) width = minOf(width, maxW)
            if (maxH > 0) height = minOf(height, maxH)
            // a window no screen could show is cut to the largest picture size (its shape and
            // drawing are made at this size)
            return width.coerceIn(0, ImageLimits.MAX_SIDE) to height.coerceIn(0, ImageLimits.MAX_SIDE)
        }

    /**
     * Where the window visibly ends: the lowest edge of its innermost shaped groups (sysregion 1
     * with no shaped group inside) that are shown. Winamp Modern's main layout is 280 px high to
     * leave room for its drawer, and an outer frame group spans all of it, while the window itself
     * ends at 164 px until the drawer slides out. The layout's height when it has none.
     */
    fun contentBottom(): Int {
        val at = rt.changeCount
        if (bottomAt != at) {
            bottomNow = workOutBottom()
            bottomAt = at
        }
        return bottomNow
    }

    private fun workOutBottom(): Int {
        var bottom = -1

        // returns whether a shaped group was found at or under [o]
        fun walk(o: GuiObject): Boolean {
            if (!o.visible || o !is Group) return false
            var inner = false
            for (c in o.children) if (walk(c)) inner = true
            if (o.sysregion == 1 && o.rect.height > 0) {
                if (!inner) bottom = maxOf(bottom, o.rect.bottom)
                return true
            }
            return inner
        }
        children.forEach { walk(it) }
        return if (bottom > 0) minOf(bottom, size.second) else size.second
    }

    /**
     * The window's shape: the layout's region with its children's, less every whole-window cut.
     * Null for a window with no shape at all, which shows as its rectangle.
     */
    private fun windowShape(): Region? {
        val at = rt.changeCount
        if (shapeAt != at) {
            val composed = composed()
            shapeNow =
                if (composed == null && cutsNow.isEmpty()) {
                    null
                } else {
                    Region.Op(composed ?: Region.Box(Rect(0, 0, size.first, size.second)), emptyList(), emptyList(), cutsNow)
                }
            shapeAt = at
        }
        return shapeNow
    }

    // the window's region and edges, each with the [SkinRuntime.changeCount] it was worked out at: a
    // frame asks for them several times, and a moving visualizer or ticker changes none of them
    private var composedAt = -1L
    private var composedNow: Region? = null
    private var cutsNow: List<Region> = emptyList()
    private var shapeAt = -1L
    private var shapeNow: Region? = null
    private var rightAt = -1L
    private var rightNow = 0
    private var bottomAt = -1L
    private var bottomNow = 0

    /** The layout's region with its children's, and the whole-window cuts, worked out once for each change. */
    private fun composed(): Region? {
        val at = rt.changeCount
        if (composedAt != at) {
            rt.shapesWorkedOut++
            val cuts = ArrayList<Region>()
            composedNow = composedRegion(cuts)
            cutsNow = cuts
            composedAt = at
        }
        return composedNow
    }

    /**
     * Where the window visibly ends on the right: the right edge of the parts its children add to
     * its shape, or of its background where that shows further, at most its width. MMD3's window is
     * 583 px wide with room on the right for a panel that slides out; its body ends at about 380.
     * Its shade bar is 800 px wide, and only its background reaches past the drawers ending at 677.
     */
    fun contentRight(): Int {
        val at = rt.changeCount
        if (rightAt != at) {
            rightNow = if (desktopAlpha) size.first else workOutRight(composed() as? Region.Op, size.first)
            rightAt = at
        }
        return rightNow
    }

    private fun workOutRight(
        shape: Region.Op?,
        width: Int,
    ): Int {
        if (shape == null) return width
        val right = shape.add.mapNotNull { it.bounds()?.right }.maxOrNull() ?: return width
        if (right >= width) return width
        val background =
            when (val base = shape.base) {
                is Region.Box -> base.rect.right
                is Region.Pic -> rt.opaqueRight(base.image.bitmap, base.image.dst)
                else -> 0
            }
        return maxOf(right, background).coerceIn(1, width)
    }

    /**
     * The part of the window Winamp docks and snaps to: its size less [snapRight] and [snapBottom],
     * but never less than what it shows now ([contentRight], [contentBottom]), so a drawer sliding
     * out over that margin still counts. Winamp Modern's main window is 280 px high and 164 px of it
     * shows while its drawer is closed. The window keeps its top left corner: [snapLeft] and
     * [snapTop] trim nothing.
     */
    fun snappedSize(): Pair<Int, Int> {
        val (width, height) = size
        val right = if (snapRight > 0) maxOf(width - snapRight, contentRight()) else width
        val bottom = if (snapBottom > 0) maxOf(height - snapBottom, contentBottom()) else height
        return right.coerceIn(0, width) to bottom.coerceIn(0, height)
    }

    /** A script's snapAdjust: new margins, then onSnapAdjustChanged when they changed. */
    private fun snapAdjust(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        if (listOf(snapLeft, snapTop, snapRight, snapBottom) == listOf(left, top, right, bottom)) return
        snapLeft = left
        snapTop = top
        snapRight = right
        snapBottom = bottom
        rt.invalidate()
        rt.dispatch(this, "onsnapadjustchanged", emptyList())
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible) return
        val shape = if (desktopAlpha) null else windowShape()
        if (shape == null) {
            super.draw(out)
            return
        }
        out.add(DrawCmd.WindowStart(shape).also { it.ownedBy = this })
        super.draw(out)
        out.add(DrawCmd.WindowEnd)
    }

    /**
     * Whether the user can change the window's height: one of its objects is a resize handle on a
     * top or bottom edge (`resize="bottomright"` and so on). Shade layouts have left and right
     * handles only, so they keep their height.
     */
    fun resizesVertically(): Boolean = hasHandle("top", "bottom")

    /** Whether the user can change the window's width: a handle on a left or right edge. */
    fun resizesHorizontally(): Boolean = hasHandle("left", "right")

    private fun hasHandle(vararg edges: String): Boolean {
        fun walk(o: GuiObject): Boolean {
            val r = o.attrs["resize"]?.lowercase()
            if (r != null && edges.any { it in r }) return true
            return o is Group && o.children.any { walk(it) }
        }
        return children.any { walk(it) }
    }

    fun layoutRoot() {
        val (width, height) = size
        rect = Rect(0, 0, width, height)
        effectiveAlpha = alpha
        for (c in children) c.layout(rect, effectiveAlpha)
    }

    /** The alphas of [layoutRoot] worked out again for the window's focus, with nothing moved or resized. */
    internal fun refreshAlphas() {
        effectiveAlpha = alpha
        for (c in children) c.refreshAlpha(effectiveAlpha)
    }

    override fun hitTest(
        px: Int,
        py: Int,
    ): GuiObject? = super.hitTest(px, py) ?: if (visible && (px to py) in rect) this else null

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getcontainer" -> {
                Value.of(container)
            }

            "getscale" -> {
                Value.Num(1.0)
            }

            "setscale", "beforeredock", "redock", "setdesktopalpha", "center" -> {
                Value.Null
            }

            "snapadjust" -> {
                snapAdjust(args[0].asInt, args[1].asInt, args[2].asInt, args[3].asInt)
                Value.Null
            }

            "getdesktopalpha" -> {
                Value.Bool(false)
            }

            "resize" -> {
                // moving the window is the container's business; the size is the layout's
                container?.let {
                    it.x = args[0].asInt
                    it.y = args[1].asInt
                }
                w = args[2].asInt
                h = args[3].asInt
                rt.relayout()
                Value.Null
            }

            "getleft" -> {
                Value.Int(container?.x ?: 0)
            }

            "gettop" -> {
                Value.Int(container?.y ?: 0)
            }

            "getsnapadjustleft" -> {
                Value.Int(snapLeft)
            }

            "getsnapadjusttop" -> {
                Value.Int(snapTop)
            }

            "getsnapadjustright" -> {
                Value.Int(snapRight)
            }

            "getsnapadjustbottom" -> {
                Value.Int(snapBottom)
            }

            else -> {
                super.makiCall(name, args)
            }
        }
}

/** A window: the main player, the playlist editor, the equalizer. It shows one of its layouts. */
class Container(
    val rt: SkinRuntime,
    val id: String,
    val attrs: Map<String, String>,
) : MakiObject {
    override val makiGuid: String get() = StdApi.guidOf("Container") ?: ""
    val layouts = ArrayList<Layout>()
    var current: Layout? = null
    var visible = attrs["default_visible"]?.toBool() ?: true
    var x = attrs["default_x"]?.toIntLenient() ?: 0
    var y = attrs["default_y"]?.toIntLenient() ?: 0
    val name = attrs["name"] ?: id

    fun layout(id: String): Layout? = layouts.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** `canclose="0"`: a close leaves it open. */
    val canClose: Boolean get() = attrs["canclose"]?.toBool() ?: true

    /**
     * Shows layout [id], or the first one when there is none by that id (Winamp3's eq shade asks
     * for "eq"); the new layout hears onSetVisible(1) before the container's onSwitchToLayout, and
     * takes the focus.
     */
    fun switchTo(id: String) {
        val target = layout(id) ?: layouts.firstOrNull() ?: return
        if (target === current) return
        // the two layouts themselves, not their ids (Wasabi SDK c_container)
        rt.dispatch(this, "onbeforeswitchtolayout", listOf(Value.of(current), Value.Obj(target)))
        current?.let { it.announceVisible(false) }
        current = target
        rt.relayout()
        target.announceVisible(true)
        rt.focus(target)
        rt.dispatch(this, "onswitchtolayout", listOf(Value.Obj(target)))
    }

    fun show() {
        if (!visible) {
            visible = true
            rt.invalidate()
            rt.dispatch(this, "onshowlayout", listOf(Value.of(current)))
        }
    }

    fun hide() {
        if (visible) {
            visible = false
            rt.invalidate()
            rt.dispatch(this, "onhidelayout", listOf(Value.of(current)))
        }
    }

    @Suppress("CyclomaticComplexMethod") // one arm per script method a container answers
    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> {
                Value.Str("Container")
            }

            "getid" -> {
                Value.Str(id)
            }

            "getname" -> {
                Value.Str(this.name)
            }

            "getlayout" -> {
                Value.of(layout(args[0].asString))
            }

            "getcurlayout" -> {
                Value.of(current)
            }

            "getnumlayouts" -> {
                Value.Int(layouts.size)
            }

            "enumlayout" -> {
                Value.of(layouts.getOrNull(args[0].asInt))
            }

            "switchtolayout" -> {
                Value.Null.also { switchTo(args[0].asString) }
            }

            // the same open and close as a button's
            "show" -> {
                Value.Null.also { rt.showContainer(this, true) }
            }

            "hide", "close" -> {
                Value.Null.also { rt.showContainer(this, false) }
            }

            "toggle" -> {
                Value.Null.also { rt.showContainer(this, !rt.isOpen(this)) }
            }

            "isvisible" -> {
                Value.Bool(rt.isOpen(this))
            }

            "setxmlparam" -> {
                when (args[0].asString.lowercase()) {
                    "x" -> x = args[1].asInt
                    "y" -> y = args[1].asInt
                }
                Value.Null
            }

            "getxmlparam" -> {
                Value.Str(attrs[args[0].asString.lowercase()] ?: "")
            }

            "setname" -> {
                Value.Null
            }

            "isdynamic" -> {
                Value.Bool(false)
            }

            "getguid" -> {
                Value.Str(attrs["component"] ?: "")
            }

            "setlocation" -> {
                x = args[0].asInt
                y = args[1].asInt
                Value.Null
            }

            "centerlayouts" -> {
                Value.Null
            }

            else -> {
                null
            }
        }

    override fun toString() = "container#$id"
}

/** A picture. With `move="1"` dragging it moves the window, which the phone does not do. */
open class Layer(
    rt: SkinRuntime,
    tag: String = "layer",
) : GuiObject(rt, tag) {
    override val className: String get() = "Layer"
    var image: String? = null

    /** `tile="1"` repeats the picture across the layer; otherwise it is stretched to fit. */
    var tile = false

    /** `inactiveimage`: drawn instead of the image while the window has no focus. */
    var inactiveImage: String? = null

    /** The picture it draws now. */
    protected val shownImage: String? get() = inactiveImage?.takeIf { !rt.hasFocus(layoutObject) } ?: image

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "image" -> image = value
            "tile" -> tile = (value.toIntLenient() ?: 0) != 0
            "inactiveimage" -> inactiveImage = value
            "region", "scale", "resize", "dblclickaction" -> Unit
            else -> return false
        }
        return true
    }

    override fun intrinsicSize(): Pair<Int, Int>? = image?.let { rt.bitmapSize(it) }

    /**
     * The opaque pixels of what it draws; its rectangle only with a rectrgn written after the
     * sysregion, as a sysregion turns a layer's rectrgn off.
     */
    override fun ownRegion(): Region? {
        val keys = attrs.keys.toList()
        val rectAfter = rectRgn && keys.indexOf("rectrgn") > maxOf(keys.indexOf("sysregion"), keys.indexOf("regionop"))
        if (rectAfter || (sysregion == 0 && rectRgn)) return super.ownRegion()
        val drawn = ArrayList<DrawCmd>()
        draw(drawn)
        val pics = drawn.filterIsInstance<DrawCmd.Image>().map { Region.Pic(DrawCmd.Image(it.bitmap, it.dst, 255)) }
        return when (pics.size) {
            0 -> null
            1 -> pics[0]
            else -> Region.Op(null, pics, emptyList(), emptyList())
        }
    }

    // clicks pass through transparent pixels, as in Winamp
    override fun hitTest(
        px: Int,
        py: Int,
    ): GuiObject? = super.hitTest(px, py)?.takeIf { rt.opaqueAt(image, rect, px, py) }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible) return
        // a region picture draws too: the shape it cuts hides it
        val img = shownImage ?: return
        val bm = rt.bitmap(img) ?: return
        val size = rt.bitmapSize(img)?.takeIf { it.first > 0 && it.second > 0 }
        if (!tile || size == null) {
            out.add(DrawCmd.Image(bm, rect, effectiveAlpha))
            return
        }
        // one command per tile; tiles at the right and bottom edges take only the part that fits
        val (tw, th) = size
        for (ty in rect.top until rect.bottom step th) {
            for (tx in rect.left until rect.right step tw) {
                val w = minOf(tw, rect.right - tx)
                val h = minOf(th, rect.bottom - ty)
                val part = if (w == tw && h == th) bm else BitmapDef(bm.id, bm.file, bm.x, bm.y, w, h, bm.gammaGroup)
                out.add(DrawCmd.Image(part, Rect(tx, ty, w, h), effectiveAlpha))
            }
        }
    }

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "isinvalid" -> Value.Bool(false)
            else -> super.makiCall(name, args)
        }
}

/**
 * A strip of frames, stacked vertically unless the XML gives a frame width. Scripts step it with
 * gotoFrame, or play it from a start frame to an end frame at `speed` milliseconds a frame.
 */
class AnimatedLayer(
    rt: SkinRuntime,
) : Layer(rt, "animatedlayer") {
    override val className: String get() = "AnimatedLayer"
    var frameW: Int? = null
    var frameH: Int? = null
    var frame = 0

    /** Set by frameheight (true) or framewidth (false), the last one written; else from the picture. */
    private var verticalSet: Boolean? = null
    private var startFrame = 0
    private var endFrame: Int? = null
    var speed = 200
    private var autoReplay = true
    private var autoPlay = false
    var playing = false
        private set
    private var paused = false
    private var step = 1
    var nextAt = 0L

    /** Playing and not paused: it steps on at [nextAt]. A paused strip shows one frame until it resumes. */
    val running: Boolean get() = playing && !paused

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "framewidth" -> {
                frameW = value.toIntLenient()
                verticalSet = false
            }

            "frameheight" -> {
                frameH = value.toIntLenient()
                verticalSet = true
            }

            "start" -> {
                startFrame = value.toIntLenient() ?: 0
            }

            "end" -> {
                endFrame = value.toIntLenient()
            }

            "speed" -> {
                speed = value.toIntLenient() ?: 200
            }

            "autoplay" -> {
                autoPlay = value.toBool()
            }

            "autoreplay" -> {
                autoReplay = value.toBool()
            }

            "debug", "realtime", "elementframes" -> {
                Unit
            }

            else -> {
                return false
            }
        }
        return true
    }

    private fun fullSize(): Pair<Int, Int>? = image?.let { rt.bitmapSize(it) }

    /**
     * Whether the frames run down the picture; with neither framewidth nor frameheight written, a
     * picture as wide as the object runs down and any other across. Null for a
     * picture the object's size: one frame.
     */
    private val vertical: Boolean?
        get() {
            verticalSet?.let { return it }
            val full = fullSize() ?: return true
            val ow = w ?: full.first
            val oh = h ?: full.second
            return when {
                full.first == ow && full.second != oh -> true
                full.first != ow -> false
                else -> null
            }
        }

    private fun frameSize(): Pair<Int, Int> {
        val full = fullSize() ?: return 0 to 0
        val ow = w ?: full.first
        val oh = h ?: full.second
        // across the strip a frame is as large as the picture
        return when (vertical) {
            true -> full.first to (frameH ?: oh)
            false -> (frameW ?: ow) to full.second
            null -> ow to oh
        }
    }

    /** How many frames the strip holds. */
    val length: Int
        get() {
            val full = fullSize() ?: return 0
            val (fw, fh) = frameSize()
            return when (vertical) {
                true -> full.second / maxOf(1, fh)
                false -> full.first / maxOf(1, fw)
                null -> 1
            }
        }

    override fun intrinsicSize(): Pair<Int, Int>? = if (fullSize() == null) null else frameSize()

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible) return
        val img = image ?: return
        val def = rt.bitmap(img) ?: return
        val (fw, fh) = frameSize()
        if (fw <= 0 || fh <= 0) return
        // a frame past the end shows the first
        val f = if (frame in 0 until length) frame else 0
        val sx = if (vertical == false) def.x + f * fw else def.x
        val sy = if (vertical == true) def.y + f * fh else def.y
        out.add(DrawCmd.Image(BitmapDef(def.id, def.file, sx, sy, fw, fh, def.gammaGroup), rect, effectiveAlpha))
    }

    fun gotoFrame(n: Int) {
        frame = n
        frameChanged()
        dispatch("onframe", Value.Int(n))
    }

    /**
     * Asks for a frame for a new picture of the strip: only while it shows, and with its window's
     * shape worked out again only when the strip is part of it (a sysregion).
     */
    private fun frameChanged() {
        when {
            regionOp() != 0 -> rt.invalidate()
            rt.shows(this) -> rt.repaint()
        }
    }

    fun play() {
        val end = endFrame ?: (length - 1)
        step = if (startFrame < end) 1 else -1
        paused = false
        gotoFrame(startFrame)
        dispatch("onplay")
        if (startFrame == end && !autoReplay) {
            stop()
            return
        }
        playing = true
        nextAt = rt.now() + speed
        rt.animations.add(this)
    }

    fun stop() {
        if (playing) {
            playing = false
            rt.animations.remove(this)
        }
        dispatch("onstop")
    }

    /** One step of a playing strip; called by the runtime when [nextAt] is due. */
    fun advance() {
        if (paused) return
        val end = endFrame ?: (length - 1)
        if (frame == end) {
            if (!autoReplay) {
                stop()
                return
            }
            // back to the start without onFrame
            frame = startFrame
            frameChanged()
        } else {
            gotoFrame(frame + step)
        }
        // on the beat, catching up ticks that came late
        nextAt += maxOf(1, speed)
        if (nextAt < rt.now() - maxOf(1, speed) * MAX_CATCH_UP) nextAt = rt.now()
    }

    fun startIfAuto() {
        if (autoPlay && !playing) play()
    }

    private companion object {
        /** At most this many late frames are caught up at once. */
        const val MAX_CATCH_UP = 8
    }

    @Suppress("CyclomaticComplexMethod") // one arm per script method an animated layer answers
    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "setspeed" -> {
                Value.Null.also { speed = args[0].asInt }
            }

            "play" -> {
                Value.Null.also { play() }
            }

            // pause again resumes
            "pause" -> {
                Value.Null.also {
                    if (!playing) return@also
                    paused = !paused
                    if (paused) {
                        dispatch("onpause")
                    } else {
                        nextAt = rt.now() + maxOf(1, speed)
                        dispatch("onresume")
                    }
                }
            }

            "stop" -> {
                Value.Null.also { stop() }
            }

            "setautoreplay" -> {
                Value.Null.also { autoReplay = args[0].truthy }
            }

            "setrealtime" -> {
                Value.Null
            }

            "gotoframe" -> {
                Value.Null.also { gotoFrame(args[0].asInt) }
            }

            "getcurframe" -> {
                Value.Int(frame)
            }

            "getlength" -> {
                Value.Int(length)
            }

            "getstartframe" -> {
                Value.Int(startFrame)
            }

            "getendframe" -> {
                Value.Int(endFrame ?: (length - 1))
            }

            "setstartframe" -> {
                Value.Null.also { startFrame = args[0].asInt }
            }

            "setendframe" -> {
                Value.Null.also { endFrame = args[0].asInt }
            }

            "isplaying" -> {
                Value.Bool(playing && !paused)
            }

            "ispaused" -> {
                Value.Bool(playing && paused)
            }

            "isstopped" -> {
                Value.Bool(!playing)
            }

            "getautoreplay" -> {
                Value.Bool(autoReplay)
            }

            "getdirection" -> {
                Value.Int(if (vertical == true) 1 else 0)
            }

            else -> {
                super.makiCall(name, args)
            }
        }
}

/** A push button with an action, such as PLAY, or a script listening to `onLeftClick`. */
open class Button(
    rt: SkinRuntime,
    tag: String = "button",
) : GuiObject(rt, tag) {
    override val className: String get() = "Button"
    var image: String? = null
    var downImage: String? = null
    var hoverImage: String? = null
    var activeImage: String? = null
    var action: String? = null
    var param: String? = null
    var actionTarget: String? = null
    var pressed = false
    var activated = false
    var borders = false
    var centerImage = false

    /** `text`: a label Winamp draws on the button, in its own Arial at 11 px (Wasabi:Button "Switch"). */
    var label: String? = null

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "image" -> image = value

            "downimage" -> downImage = value

            "hoverimage" -> hoverImage = value

            "activeimage" -> activeImage = value

            "action" -> action = value

            "param" -> param = value

            "action_target", "cbtarget" -> actionTarget = value

            // Winamp's button frame round the picture, and the picture at its own size in the middle
            "borders" -> borders = value.toBool()

            "text" -> label = value

            "center_image" -> centerImage = value.toBool()

            "retcode", "lefttext", "righttext" -> Unit

            else -> return false
        }
        return true
    }

    override fun intrinsicSize(): Pair<Int, Int>? =
        image?.let { rt.bitmapSize(it) } ?: label?.let { (rt.host.textWidth(it, LABEL_FONT, LABEL_SIZE) + LABEL_PAD) to 0 }

    /** Whether the thing this button toggles is on: an open window, a player switch. */
    open val isActive: Boolean
        get() = activated || rt.actionState(action, param)

    /**
     * Whether it lights up for a window the app may open or close without the skin: a TOGGLE. A
     * switch of the player or a config value that changes asks for a frame itself.
     */
    internal val followsHost: Boolean get() = action.equals("TOGGLE", ignoreCase = true)

    /** The state its picture shows: active (1) or not (0). */
    internal open val shownState: Int get() = if (isActive) 1 else 0

    /** [shownState] as the last frame drew it, or as last heard of; null before the first frame. */
    private var drawnState: Int? = null

    /**
     * Whether [shownState] moved, for a window of the app (see [followsHost]), since the last frame
     * drew the button or this last answered. Each change is told once.
     */
    internal fun stateChanged(): Boolean {
        if (!followsHost) return false
        val was = drawnState ?: return false
        val now = shownState
        if (now == was) return false
        drawnState = now
        return true
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible) return
        if (borders) drawFramedButton(rt, out, rect, effectiveAlpha, pressed)
        label?.let { t ->
            // pressed, the label sits 1 px right and down (Wasabi SDK buttwnd)
            val shift = if (pressed && borders) 1 else 0
            out.add(
                DrawCmd.Text(
                    t,
                    LABEL_FONT,
                    LABEL_SIZE,
                    Rgb(0),
                    Rect(
                        rect.left + 1 + shift,
                        rect.top + 1 + shift,
                        rect.width - 2,
                        rect.height - 2,
                    ),
                    Align.CENTER,
                    0,
                    effectiveAlpha,
                    VAlign.CENTER,
                ),
            )
        }
        drawnState = shownState
        val img = (if (pressed) picture(downImage) else null) ?: (if (isActive) activeImage else null) ?: picture(image) ?: return
        val bmp = rt.bitmap(img) ?: return
        // a centered picture sits in a box the normal picture's size, 1 px right and down while
        // pressed
        val size = picture(image)?.let { rt.bitmapSize(it) } ?: rt.bitmapSize(img)
        val shift = if (pressed && enabled) 1 else 0
        val dst =
            if (centerImage &&
                size != null
            ) {
                Rect(
                    rect.left + (rect.width - size.first) / 2 + shift,
                    rect.top + (rect.height - size.second) / 2 + shift,
                    size.first,
                    size.second,
                )
            } else {
                rect
            }
        out.add(DrawCmd.Image(bmp, dst, effectiveAlpha))
    }

    /** The bitmap id a picture attribute draws: itself, or for a multi-state button its state's. */
    protected open fun picture(base: String?): String? = base

    override fun pointerDown(
        px: Int,
        py: Int,
    ) {
        pressed = true
        rt.repaint()
        super.pointerDown(px, py)
    }

    override fun pointerUp(
        px: Int,
        py: Int,
        inside: Boolean,
    ) {
        pressed = false
        rt.repaint()
        super.pointerUp(px, py, inside)
        // a menu the release handler opened took the touch
        if (inside && !rt.pressEnded) click()
    }

    override fun pointerCancel() {
        pressed = false
        rt.repaint()
    }

    open fun click() {
        dispatch("onleftclick")
        action?.let { rt.action(this, it, param, actionTarget) }
    }

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "leftclick" -> {
                Value.Null.also { click() }
            }

            "rightclick" -> {
                Value.Null.also { dispatch("onrightclick") }
            }

            // tells the scripts (onActivate), unlike setActivatedNoCallback; Bento's tabs take their
            // active look from it
            "setactivated" -> {
                Value.Null.also {
                    activated = args[0].truthy
                    rt.repaint()
                    dispatch("onactivate", Value.Int(if (activated) 1 else 0))
                }
            }

            "getactivated" -> {
                Value.Bool(isActive)
            }

            "setactivatednocallback" -> {
                Value.Null.also {
                    activated = args[0].truthy
                    rt.repaint()
                }
            }

            else -> {
                super.makiCall(name, args)
            }
        }
}

private val LABEL_FONT = FontDef("arial", "")
private const val LABEL_SIZE = 15
private const val LABEL_PAD = 8

/** A button that stays down: shuffle, repeat, EQ on. Its state lives in a config attribute. */
class ToggleButton(
    rt: SkinRuntime,
) : Button(rt, "togglebutton") {
    override val className: String get() = "ToggleButton"
    var cfgAttrib: String? = null

    /** `cfgval`: the config value that means on (default 1). */
    var cfgVal = 1

    /** `autotoggle="0"`: a tap does not flip it; scripts do. */
    private var autoToggle = true

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "cfgattrib" -> {
                cfgAttrib =
                    value
                        .substringAfter(';', "")
                        .trim()
                        .lowercase()
                        .ifEmpty { value }
            }

            "cfgval" -> {
                cfgVal = value.toIntLenient() ?: 1
            }

            "autotoggle" -> {
                autoToggle = value.toBool()
            }

            else -> {
                return false
            }
        }
        return true
    }

    override val isActive: Boolean
        get() = cfgAttrib?.let { if (cfgVal == 1) rt.configFlag(it) else rt.configValue(it) == cfgVal } ?: super.isActive

    // the push first (onLeftClick, the action), then the flip and onToggle
    override fun click() {
        super.click()
        if (!autoToggle) return
        val attrib = cfgAttrib
        when {
            // without a config attribute, a button for a window, EQ on or EQ auto lights from that
            // state (see isActive), whatever changes it
            attrib == null -> if (!rt.hasActionState(action)) activated = !activated

            cfgVal == 1 -> rt.setConfigFlag(attrib, !rt.configFlag(attrib))

            else -> rt.setConfigValue(attrib, if (isActive) 0 else cfgVal)
        }
        rt.invalidate()
        dispatch("ontoggle", Value.Int(if (isActive) 1 else 0))
        dispatch("onactivate", Value.Int(if (isActive) 1 else 0))
    }

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getcurcfgval" -> Value.Int(if (isActive) 1 else 0)
            else -> super.makiCall(name, args)
        }
}

/**
 * A button that steps through `nstates` states, such as repeat off, all, one. Its pictures are
 * the named images with the state number appended, where the skin has them. With a config
 * attribute, `cfgvals` lists the config value of each state. Without one, a button for a window
 * (Winamp3x's playlist and equalizer lights), EQ on or EQ auto takes its state from that: on while
 * the window is open or the switch is on.
 */
class NStatesButton(
    rt: SkinRuntime,
) : Button(rt, "nstatesbutton") {
    override val className: String get() = "Button"
    private var states = 2
    private var values: List<Int>? = null
    private var cfgAttrib: String? = null
    private var localState = 0

    /** `autoelements="0"`: the pictures keep their names instead of taking the state number. */
    private var autoElements = true

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "nstates" -> {
                states = maxOf(1, value.toIntLenient() ?: 2)
            }

            "cfgvals" -> {
                values = value.split(';').map { it.trim().toIntLenient() ?: 0 }
            }

            "cfgattrib" -> {
                cfgAttrib =
                    value
                        .substringAfter(';', "")
                        .trim()
                        .lowercase()
                        .ifEmpty { value }
            }

            "autoelements" -> {
                autoElements = value.toBool()
            }

            else -> {
                return false
            }
        }
        return true
    }

    private fun valueOf(state: Int) = values?.getOrNull(state) ?: state

    /**
     * The current state: from the config value when the button has one; for a window, EQ on or EQ
     * auto, the first state that means on while that is on and the first that means off while not.
     */
    val state: Int
        get() {
            val attrib = cfgAttrib
            if (attrib != null) {
                val v = rt.configValue(attrib)
                return (0 until states).firstOrNull { valueOf(it) == v } ?: 0
            }
            if (!rt.hasActionState(action)) return localState
            val open = rt.actionState(action, param)
            return (0 until states).firstOrNull { (valueOf(it) != 0) == open } ?: 0
        }

    override val isActive: Boolean get() = valueOf(state) != 0

    override val shownState: Int get() = state

    /** The picture with the state number appended, missing or not. */
    override fun picture(base: String?): String? = if (autoElements && states >= 2) base?.let { "$it$state" } else base

    override fun intrinsicSize(): Pair<Int, Int>? = picture(image)?.let { rt.bitmapSize(it) }

    override fun click() {
        val next = (state + 1) % states
        val attrib = cfgAttrib
        if (attrib != null) rt.setConfigValue(attrib, valueOf(next)) else localState = next
        rt.invalidate()
        dispatch("ontoggle", Value.Int(if (valueOf(next) != 0) 1 else 0))
        super.click()
    }

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getcurcfgval" -> Value.Int(valueOf(state))
            else -> super.makiCall(name, args)
        }
}

/** The stop, play and pause light. */
class Status(
    rt: SkinRuntime,
) : GuiObject(rt, "status") {
    override val className: String get() = "Status"
    private val images = HashMap<Player.Status, String>()

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "stopbitmap" -> images[Player.Status.STOPPED] = value
            "playbitmap" -> images[Player.Status.PLAYING] = value
            "pausebitmap" -> images[Player.Status.PAUSED] = value
            else -> return false
        }
        return true
    }

    /** The play picture's size, else 16 x 16. */
    override fun intrinsicSize(): Pair<Int, Int> = images[Player.Status.PLAYING]?.let { rt.bitmapSize(it) } ?: (16 to 16)

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible) return
        val img = images[rt.player.status] ?: return
        rt.bitmap(img)?.let { out.add(DrawCmd.Image(it, rect, effectiveAlpha)) }
    }
}

/**
 * `<ProgressGrid>`: a grid over the played part of its rectangle, the song's position over its
 * length; from the left unless `orientation` says right, top or bottom. Nothing while stopped.
 * Bento's and Winamp Modern's seek bars light up with it (Wasabi SDK xuiprogressgrid, zlib).
 */
class ProgressGrid(
    rt: SkinRuntime,
) : Grid(rt, "progressgrid") {
    private var orientation = "right"

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (key.lowercase() == "orientation") {
            orientation = value.lowercase()
            return true
        }
        return super.setParam(key, value)
    }

    /** Whether it fills from top or bottom, so its height grows; else its width does. */
    private val upright: Boolean get() = orientation == "top" || orientation == "bottom"

    /** The length of its filled part, in layout pixels; -1 while it shows none (stopped, no length). */
    fun filled(): Int {
        val p = rt.player
        if (p.status == Player.Status.STOPPED || p.lengthMs <= 0) return -1
        val f = (p.positionMs.toFloat() / p.lengthMs).coerceIn(0f, 1f)
        return ((if (upright) rect.height else rect.width) * f).toInt()
    }

    /** [filled] as the last frame drew it. */
    private var drawnFilled = -1

    /** Whether its filled part is not what the last frame drew. */
    internal fun filledChanged(): Boolean = filled() != drawnFilled

    /** The play position and filled length [nextFillAt] last saw, and the time it gave for them. */
    private var askedPosition = -1
    private var askedFilled = -1
    private var askedDue = 0L

    /**
     * When, in the runtime's clock at [now], its filled part next grows while the song plays on: at
     * the first whole second of play from which it shows a pixel more, when the time display moves
     * on too, so one frame shows both. Null while it does not play or is full. A position that
     * still stands where it stood when that time came (a player waiting for its sound) asks again
     * a second later.
     */
    internal fun nextFillAt(now: Long): Long? {
        val p = rt.player
        val length = if (upright) rect.height else rect.width
        if (p.status != Player.Status.PLAYING || p.lengthMs <= 0 || length <= 0) return null
        val f = filled()
        if (f >= length) return null
        val position = p.positionMs
        if (position != askedPosition || f != askedFilled) {
            // the first position that fills one pixel more, never the one playing now, at a whole second
            val next = ceil((f + 1).toDouble() * p.lengthMs / length).toLong()
            val second = (next + SECOND_MS - 1) / SECOND_MS * SECOND_MS
            askedPosition = position
            askedFilled = f
            askedDue = now + maxOf(1L, second - position)
        } else if (now >= askedDue) {
            askedDue = now + SECOND_MS
        }
        return askedDue
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible) return
        val n = filled()
        drawnFilled = n
        if (n < 0) return
        val part =
            when (orientation) {
                "left" -> Rect(rect.right - n, rect.top, n, rect.height)
                "top" -> Rect(rect.left, rect.top, rect.width, n)
                "bottom" -> Rect(rect.left, rect.bottom - n, rect.width, n)
                else -> Rect(rect.left, rect.top, n, rect.height)
            }
        drawIn(out, part)
    }

    private companion object {
        const val SECOND_MS = 1000L
    }
}

/**
 * A stretchable background: corners and edges at their natural size, the middle stretched. A
 * one-row grid only has left, middle and right.
 */
open class Grid(
    rt: SkinRuntime,
    tag: String = "grid",
) : GuiObject(rt, tag) {
    override val className: String get() = "GuiObject"
    private val parts = HashMap<String, String>()

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        val k = key.lowercase()
        if (k in PARTS) {
            parts[k] = value
            return true
        }
        return false
    }

    private fun size(part: String) = parts[part]?.let { rt.bitmapSize(it) } ?: (0 to 0)

    override fun intrinsicSize(): Pair<Int, Int>? {
        val mid = size("middle")
        return if (mid.first == 0 &&
            mid.second == 0
        ) {
            null
        } else {
            (size("left").first + mid.first + size("right").first) to mid.second
        }
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible) return
        drawIn(out, rect)
    }

    /** The grid stretched over [area]. */
    protected fun drawIn(
        out: MutableList<DrawCmd>,
        area: Rect,
    ) {
        val rect = area
        if (rect.width <= 0 || rect.height <= 0) return

        fun put(
            part: String,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
        ) {
            if (w <= 0 || h <= 0) return
            parts[part]?.let { rt.bitmap(it) }?.let { out.add(DrawCmd.Image(it, Rect(x, y, w, h), effectiveAlpha)) }
        }
        // corners at their size; each edge and the middle span between their neighbors
        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom
        val tl = size("topleft")
        val tr = size("topright")
        val bl = size("bottomleft")
        val br = size("bottomright")
        val top = size("top")
        val bottom = size("bottom")
        val left = size("left")
        val right = size("right")
        put("topleft", l, t, tl.first, tl.second)
        put("top", l + tl.first, t, rect.width - tl.first - tr.first, top.second)
        put("topright", r - tr.first, t, tr.first, tr.second)
        put("left", l, t + tl.second, left.first, rect.height - tl.second - bl.second)
        put(
            "middle",
            l + left.first,
            t + top.second,
            rect.width - left.first - right.first,
            rect.height - top.second - bottom.second,
        )
        put("right", r - right.first, t + tr.second, right.first, rect.height - tr.second - br.second)
        put("bottomleft", l, b - bl.second, bl.first, bl.second)
        put("bottom", l + bl.first, b - bottom.second, rect.width - bl.first - br.first, bottom.second)
        put("bottomright", r - br.first, b - br.second, br.first, br.second)
    }

    override fun hitTest(
        px: Int,
        py: Int,
    ): GuiObject? = null

    private companion object {
        val PARTS = setOf("topleft", "top", "topright", "left", "middle", "right", "bottomleft", "bottom", "bottomright")
    }
}

/** Objects the skin names that this runtime does not draw. They still answer scripts. */
class Unknown(
    rt: SkinRuntime,
    tag: String,
    override val className: String,
) : GuiObject(rt, tag) {
    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        super.setParam(key, value)
        return true
    }

    override fun hitTest(
        px: Int,
        py: Int,
    ): GuiObject? = null
}
