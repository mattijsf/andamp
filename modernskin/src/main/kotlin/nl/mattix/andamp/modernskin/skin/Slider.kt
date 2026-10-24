// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

import nl.mattix.andamp.modernskin.maki.Value

/**
 * A thumb on a bar. Its action ties it to the player: SEEK, VOLUME, EQ_BAND, EQ_PREAMP, PAN. The
 * position runs from `low` to `high`, 0..255 unless the XML says otherwise.
 */
class Slider(
    rt: SkinRuntime,
) : GuiObject(rt, "slider") {
    override val className: String get() = "Slider"
    var action: String? = null
    var param: String? = null
    var thumb: String? = null
    var downThumb: String? = null
    var barLeft: String? = null
    var barMiddle: String? = null
    var barRight: String? = null
    var vertical = false
    var low = 0
    var high = 255
    var position = 0
        private set
    var dragging = false
        private set
    private var locked = false

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "action" -> action = value.lowercase()

            "param" -> param = value

            "thumb" -> thumb = value

            "downthumb" -> downThumb = value

            "hoverthumb" -> Unit

            "barleft" -> barLeft = value

            "barmiddle" -> barMiddle = value

            "barright" -> barRight = value

            "orientation" -> vertical = value.lowercase().startsWith("v")

            "low" -> low = value.toIntLenient() ?: 0

            "high" -> high = value.toIntLenient() ?: 255

            // Winamp 5.666 has no thumboffset: a skin's is ignored
            "cfgattrib", "cfgval", "thumboffset" -> Unit

            else -> return false
        }
        return true
    }

    private val range: Int get() = maxOf(1, high - low)

    /** Where in the thumb the finger holds it, along the bar: kept while dragging (Wasabi SDK slider). */
    private var grab = 0

    private fun thumbSize(): Pair<Int, Int> = thumb?.let { rt.bitmapSize(it) } ?: (0 to 0)

    override fun intrinsicSize(): Pair<Int, Int>? = barMiddle?.let { rt.bitmapSize(it) } ?: thumb?.let { rt.bitmapSize(it) }

    /** The thumb's box for the current position. */
    private fun thumbRect(): Rect {
        val (tw, th) = thumbSize()
        val frac = (position - low).toDouble() / range
        return if (vertical) {
            val travel = maxOf(0, rect.height - th)
            Rect(rect.left + (rect.width - tw) / 2, rect.top + ((1 - frac) * travel).toInt(), tw, th)
        } else {
            val travel = maxOf(0, rect.width - tw)
            Rect(rect.left + (frac * travel).toInt(), rect.top + (rect.height - th) / 2, tw, th)
        }
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible) return
        val bl = barLeft?.let { rt.bitmap(it) }
        val bm = barMiddle?.let { rt.bitmap(it) }
        val br = barRight?.let { rt.bitmap(it) }
        if (bm != null) {
            val lw = bl?.let { rt.bitmapSize(it.id)?.first } ?: 0
            val rw = br?.let { rt.bitmapSize(it.id)?.first } ?: 0
            if (bl != null) out.add(DrawCmd.Image(bl, Rect(rect.left, rect.top, lw, rect.height), effectiveAlpha))
            out.add(DrawCmd.Image(bm, Rect(rect.left + lw, rect.top, rect.width - lw - rw, rect.height), effectiveAlpha))
            if (br != null) out.add(DrawCmd.Image(br, Rect(rect.right - rw, rect.top, rw, rect.height), effectiveAlpha))
        }
        // a seek bar has no thumb while nothing plays
        if (action == "seek" && rt.player.status == Player.Status.STOPPED && !dragging) return
        val img = (if (dragging) downThumb else null) ?: thumb ?: return
        rt.bitmap(img)?.let { out.add(DrawCmd.Image(it, thumbRect(), effectiveAlpha)) }
    }

    private fun positionFrom(
        px: Int,
        py: Int,
    ): Int {
        val (tw, th) = thumbSize()
        val frac =
            if (vertical) {
                val travel = maxOf(1, rect.height - th)
                1.0 - (py - rect.top - grab).toDouble() / travel
            } else {
                val travel = maxOf(1, rect.width - tw)
                (px - rect.left - grab).toDouble() / travel
            }
        return (low + frac.coerceIn(0.0, 1.0) * range).toInt()
    }

    /** Sets the position and tells the script; [fromUser] also drives the player. */
    fun setPosition(
        p: Int,
        fromUser: Boolean,
    ) {
        val clamped = p.coerceIn(low, high)
        if (clamped == position && fromUser) return
        position = clamped
        rt.invalidate()
        dispatch("onsetposition", Value.Int(position))
        if (fromUser) rt.sliderMoved(this, final = false)
    }

    override fun pointerDown(
        px: Int,
        py: Int,
    ) {
        if (locked) return
        dragging = true
        // held where it was grabbed; a tap beside the thumb lands it just right of (below) the finger
        val (tw, th) = thumbSize()
        val t = thumbRect()
        grab =
            if (vertical) {
                th / 2 - 2
            } else if (px in t.left until t.right) {
                px - t.left - 1
            } else {
                tw / 2 - 2
            }
        setPosition(positionFrom(px, py), fromUser = true)
        super.pointerDown(px, py)
    }

    override fun pointerMove(
        px: Int,
        py: Int,
    ) {
        if (dragging) setPosition(positionFrom(px, py), fromUser = true)
        super.pointerMove(px, py)
    }

    override fun pointerUp(
        px: Int,
        py: Int,
        inside: Boolean,
    ) {
        if (dragging) {
            dragging = false
            rt.invalidate()
            super.pointerUp(px, py, inside)
            // a menu the release handler opened took the touch
            if (rt.pressEnded) return
            dispatch("onsetfinalposition", Value.Int(position))
            dispatch("onpostedposition", Value.Int(position))
            rt.sliderMoved(this, final = true)
        }
    }

    // the player hears no final position, and the thumb follows the player again; scripts hear
    // the button go up, so what they show while the thumb is held (Winamp Modern's "SEEK:" in its
    // ticker) gives way
    override fun pointerCancel() {
        if (dragging) {
            dragging = false
            rt.invalidate()
            val (px, py) = rt.lastPointer
            dispatch("onleftbuttonup", Value.Int(px), Value.Int(py))
        }
    }

    /**
     * Puts the thumb where the player is until its first [sync]: as the skin starts, and when its
     * layout shows for the first time, so that frame already shows it there. Scripts hear nothing
     * of it, nor of the first [sync] after it.
     */
    fun placeIfUnsynced() {
        if (dragging || synced) return
        rt.sliderTarget(this)?.let { position = it.coerceIn(low, high) }
    }

    /** Follows the player when the user is not holding the thumb. */
    fun sync() {
        if (dragging) return
        val target = rt.sliderTarget(this) ?: return
        if (target != position || !synced) {
            position = target.coerceIn(low, high)
            rt.invalidate()
            // the first placement is the skin starting up, not a change scripts react to
            if (synced) {
                dispatch("onsetposition", Value.Int(position))
                dispatch("onpostedposition", Value.Int(position))
            }
            synced = true
        }
    }

    private var synced = false

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "setposition" -> Value.Null.also { setPosition(args[0].asInt, fromUser = false) }
            "getposition" -> Value.Int(position)
            "lock" -> Value.Null.also { locked = true }
            "unlock" -> Value.Null.also { locked = false }
            else -> super.makiCall(name, args)
        }
}
