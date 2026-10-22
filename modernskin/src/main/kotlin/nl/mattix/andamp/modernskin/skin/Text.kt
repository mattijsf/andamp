// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

import nl.mattix.andamp.modernskin.maki.Value

/**
 * A line of text: a fixed string, or a player display such as the time or the song name. With
 * `ticker="1"` a string wider than the box scrolls.
 */
class Text(
    rt: SkinRuntime,
    tag: String = "text",
) : GuiObject(rt, tag) {
    override val className: String get() = "Text"
    var staticText: String? = null
    var display: String? = null
    var font: String? = null
    var fontSize: Int = 0

    // a text that names no color draws white
    var color: Rgb = Rgb(0xffffff)
    private var colorId: String? = null

    // Winamp centers text vertically unless the skin says otherwise
    var valign = VAlign.CENTER
    var bold = false
    private var shadow: Rgb? = null

    /** `shadowx` / `shadowy`: a shadow is drawn only when one is not 0, at that offset. */
    private var shadowX = 0
    private var shadowY = 0

    /** `offsetx` / `offsety`: added to where the text is drawn. */
    private var offsetX = 0
    private var offsetY = 0
    private var forceLower = false

    /** `forcefixed`: a time's pieces are as wide as their characters times the width of "0". */
    private var forceFixed = false

    /** `leftpadding` and `rightpadding` move the text box's left and right edges (unset: bitmap text 2 and 0, TrueType 0). */
    private var leftPadding: Int? = null
    private var rightPadding = 0
    private var shadowId: String? = null
    private var timerOffStyle = 0
    var align = Align.LEFT
    var ticker = false

    /**
     * A `<songticker>`: its `ticker` is "scroll" (the default, a loop with " *** " between the
     * repeats), "bounce" or "off", and it moves 1 px every 39 ms.
     */
    var songTicker = false
    private var bounce = false
    private var tickerStep = 1
    var timeColonWidth: Int? = null
    var showLen = false
    var forceUpper = false
    var alternateText: String? = null

    /** Text a script set, shown instead of the display until it sets "" again. */
    private var scriptText: String? = null
    private var scroll = 0
    private var scrollDir = 1
    private var scrollPause = 0
    private var lastText = ""

    /** When a temporary message (setAlternateText) clears. */
    private var alternateUntil = 0L

    /** When the ticker last stepped, and the poll ticks since the last move. */
    private var tickedAt = -1L
    private var skipped = 0

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "text", "default" -> {
                staticText = value
                textChanged()
            }

            "display" -> {
                display = value.lowercase()
            }

            "font" -> {
                font = value
            }

            "fontsize" -> {
                fontSize = value.toIntLenient() ?: 0
            }

            "color", "textcolor" -> {
                parseRgb(value)?.let { color = it } ?: run { colorId = value }
            }

            "leftpadding" -> {
                leftPadding = value.toIntOrNull()
            }

            "rightpadding" -> {
                rightPadding = value.toIntOrNull() ?: 0
            }

            "shadowcolor" -> {
                parseRgb(value)?.let { shadow = it } ?: run { shadowId = value }
            }

            // Winamp knows top and center only: "bottom" and "middle" draw at the top
            // a songticker has no valign: it draws at the top of its box (winamp1)
            "valign" -> {
                if (!songTicker) valign = if (value.equals("center", ignoreCase = true)) VAlign.CENTER else VAlign.TOP
            }

            "bold" -> {
                bold = value.toBool()
            }

            "timeroffstyle" -> {
                timerOffStyle = value.toIntLenient() ?: 0
            }

            "align" -> {
                align =
                    when (value.lowercase()) {
                        "center" -> Align.CENTER
                        "right" -> Align.RIGHT
                        else -> Align.LEFT
                    }
            }

            // a text reads the number only ("scroll" is off); a songticker reads the words
            "ticker" -> {
                if (songTicker) {
                    ticker = !value.equals("off", ignoreCase = true)
                    bounce = value.equals("bounce", ignoreCase = true)
                } else {
                    ticker = (value.toIntLenient() ?: 0) != 0
                }
            }

            "tickerstep" -> {
                tickerStep = value.toIntLenient() ?: 1
            }

            "timecolonwidth" -> {
                timeColonWidth = value.toIntLenient()
            }

            "showlen" -> {
                showLen = value.toBool()
            }

            "forceupcase", "forceuppercase" -> {
                forceUpper = value.toBool()
            }

            "shadowx" -> {
                shadowX = value.toIntLenient() ?: 0
            }

            "shadowy" -> {
                shadowY = value.toIntLenient() ?: 0
            }

            "offsetx" -> {
                offsetX = value.toIntLenient() ?: 0
            }

            "offsety" -> {
                offsetY = value.toIntLenient() ?: 0
            }

            "forcelocase", "forcelowercase" -> {
                forceLower = value.toBool()
            }

            "antialias", "italic", "underline", "altfont",
            "forcefixed",
            -> {
                forceFixed = value.toBool()
            }

            "wrap", "nograb", "fontbgcolor",
            "ghost", "timeroffset", "dblclickaction", "image",
            -> {
                Unit
            }

            else -> {
                return false
            }
        }
        return true
    }

    private fun longTime(ms: Int): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else time(ms)
    }

    private fun time(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** What is shown now. */
    val currentText: String
        get() {
            alternateText?.takeIf { it.isNotEmpty() }?.let { return it }
            scriptText?.takeIf { it.isNotEmpty() }?.let { return cased(it) }
            val p = rt.player
            val stopped = p.status == Player.Status.STOPPED
            val t =
                when (display) {
                    null, "" -> {
                        staticText ?: ""
                    }

                    // the three are one display; stopped, they show the timeroffstyle
                    "time", "timeelapsed" -> {
                        if (stopped) offText(remaining = false) else time(p.positionMs)
                    }

                    "timeremaining" -> {
                        if (stopped) offText(remaining = true) else "-" + time(p.lengthMs - p.positionMs)
                    }

                    "songname", "songtitle" -> {
                        if (stopped &&
                            p.title.isEmpty()
                        ) {
                            staticText ?: ""
                        } else {
                            p.title + if (showLen) " (${time(p.lengthMs)})" else ""
                        }
                    }

                    "songlength" -> {
                        time(p.lengthMs)
                    }

                    "songinfo" -> {
                        if (stopped) {
                            ""
                        } else {
                            "${p.bitrateKbps}kbps ${if (p.channels > 1) "stereo" else "mono"} %.1fkHz".format(
                                java.util.Locale.ROOT,
                                p.sampleRateKhz.toDouble(),
                            )
                        }
                    }

                    "songartist" -> {
                        p.tags["artist"] ?: ""
                    }

                    "songalbum" -> {
                        p.tags["album"] ?: ""
                    }

                    "songbitrate" -> {
                        if (stopped) "" else "${p.bitrateKbps}"
                    }

                    "songsamplerate" -> {
                        if (stopped) "" else "${p.sampleRateKhz}"
                    }

                    // the playlist editor's "selected / total" lengths
                    // an hour or more shows its hours (3:20:00, WinampModern-pllong)
                    "pe_info" -> {
                        longTime(rt.playlistSelectedMs) + "/" + longTime(p.tracks.sumOf { it.lengthMs })
                    }

                    "vid_info", "componentbucket" -> {
                        ""
                    }

                    // any other display is a text feed: its text, else the text attribute, never the name
                    else -> {
                        staticText ?: ""
                    }
                }.let { if (it == ":componentname") layoutObject?.container?.name ?: "" else it }
            return cased(t)
        }

    /** forceupcase, then forcelowercase: lower wins when both are set. */
    private fun cased(t: String): String = (if (forceUpper) t.uppercase() else t).let { if (forceLower) it.lowercase() else it }

    /** A stopped time display by timeroffstyle. */
    private fun offText(remaining: Boolean): String {
        val minus = if (remaining) "-" else ""
        return when (timerOffStyle) {
            1 -> "${minus}00:00"
            2 -> ""
            3 -> "${minus}0:00:00"
            4 -> " :  :  "
            else -> "  :  "
        }
    }

    private fun bitmapFont(): BitmapFontDef? = font?.let { rt.bitmapFont(it) }

    // a font the skin does not define is one installed on the system, named as is
    private fun ttf(): FontDef? = font?.let { rt.font(it) ?: FontDef(it.lowercase(), "") }

    private val effectiveSize: Int get() = if (fontSize > 0) fontSize else DEFAULT_SIZE

    private val colonWidth: Int? get() =
        if (display == "time" || display == "timeelapsed" ||
            display == "timeremaining"
        ) {
            timeColonWidth?.takeIf { it >= 0 }
        } else {
            null
        }

    fun textWidth(text: String = currentText): Int {
        bitmapFont()?.let { f ->
            val advance = f.charWidth + f.hSpacing
            val colon = colonWidth ?: advance
            // as measured (getTextWidth, auto width): the last glyph in full, no spacing after it. The
            // Winamp3 base skin's title bar script centers its title from this and lands 1 px off
            // otherwise; drawing a centered text still centers the sum of the advances (MMD3)
            return text.sumOf { if (it == ':') colon else advance } - (if (text.isEmpty()) 0 else f.hSpacing)
        }
        return rt.host.textWidth(text, ttf(), effectiveSize, bold)
    }

    /**
     * The width it asks for: the text plus 4, plus both paddings; a time laid out by its colon slot
     * without the 4; 32 for no text.
     */
    override fun intrinsicSize(): Pair<Int, Int> {
        val text = currentText
        val pads = (leftPadding ?: 0) + rightPadding
        val w =
            when {
                text.isEmpty() -> {
                    EMPTY_WIDTH
                }

                isTime && ':' in text && bitmapFont() == null -> {
                    timePieces(text).let { (widths, cw) ->
                        widths.sum() +
                            cw * (widths.size - 1)
                    } +
                        pads
                }

                else -> {
                    textWidth() + 2 * PAD + pads
                }
            }
        return w to (bitmapFont()?.charHeight ?: effectiveSize)
    }

    /** A time's pieces between its colons, their widths and the colon slot. */
    private fun timePieces(text: String): Pair<List<Int>, Int> {
        fun w(s: String) = rt.host.textWidth(s, ttf(), effectiveSize, bold)
        val zero = w("0")
        return text.split(':').map { if (forceFixed) it.length * zero else w(it) } to (colonWidth ?: w(":"))
    }

    /** A TrueType time display: laid out by its pieces between the colons. */
    private val isTime: Boolean get() = display == "time" || display == "timeelapsed" || display == "timeremaining"

    /**
     * The poll timer a text ticks on, in ms, and the ticks it pauses at each end; null for a text
     * that never ticks: static texts and text feeds, `ticker` or not.
     */
    private fun pollTimer(): Pair<Int, Int>? =
        when (display) {
            "songname", "songinfo", "songtitle", "songartist", "songalbum", "songlength", "songinfo_localise" -> 25 to 160
            "time", "timeelapsed", "timeremaining" -> 250 to 16
            "componentbucket" -> 50 to 80
            else -> null
        }

    /** Moves a wide ticker by the time passed since [now] last came; true when something changed. */
    fun tick(now: Long): Boolean {
        if (alternateText != null && now >= alternateUntil) {
            alternateText = null
            rt.invalidate()
        }
        if (!ticker || rect.width <= 0) return false
        val text = currentText
        if (text != lastText) {
            val wasTime = display in TIME_DISPLAYS && lastText.isNotEmpty()
            lastText = text
            tickedAt = now
            // a time that counts on keeps its place; a new title starts over
            if (!wasTime || songTicker) {
                scroll = 0
                scrollDir = 1
                scrollPause = if (songTicker) 0 else pollTimer()?.second ?: 0
                skipped = 0
            }
            return true
        }
        if (tickedAt < 0) tickedAt = now
        val overflow = textWidth(text) - rect.width
        if (overflow <= 0) {
            tickedAt = now
            if (scroll != 0) {
                scroll = 0
                return true
            }
            return false
        }
        // a temporary message holds the ticker still
        if (!alternateText.isNullOrEmpty()) {
            tickedAt = now
            return false
        }
        return if (songTicker) songStep(now, text) else textStep(now, overflow)
    }

    /** A text's ticker: on every third poll tick it moves `tickerstep` px, pausing 4 s at each end. */
    private fun textStep(
        now: Long,
        overflow: Int,
    ): Boolean {
        val (period, pause) = pollTimer() ?: return false
        var changed = false
        // ticks Windows could not deliver in time are caught up
        while (now - tickedAt >= period) {
            tickedAt += period
            if (scrollPause > 0) {
                scrollPause--
                continue
            }
            if (skipped++ < TEXT_SKIP) continue
            skipped = 0
            scroll = (scroll + scrollDir * tickerStep).coerceIn(0, overflow)
            changed = true
            if (scroll >= overflow || scroll <= 0) {
                scrollDir = -scrollDir
                scrollPause = pause
            }
        }
        return changed
    }

    /**
     * A songticker: a loop with no pause, or a bounce with 2 s at each end, 1 px every 39 ms while
     * playing.
     */
    private fun songStep(
        now: Long,
        text: String,
    ): Boolean {
        // it holds still while stopped
        if (rt.player.status != Player.Status.PLAYING) {
            tickedAt = now
            return false
        }
        var changed = false
        while (now - tickedAt >= SONG_STEP_MS) {
            tickedAt += SONG_STEP_MS
            if (!bounce) {
                val period = textWidth(text + LOOP_GAP + text) - textWidth(text)
                scroll = if (period > 0) (scroll + 1) % period else 0
                changed = true
                continue
            }
            if (scrollPause > 0) {
                scrollPause--
                continue
            }
            val overflow = textWidth(text) - rect.width
            scroll = (scroll + scrollDir).coerceIn(0, maxOf(0, overflow))
            changed = true
            if (scroll >= overflow || scroll <= 0) {
                scrollDir = -scrollDir
                scrollPause = BOUNCE_PAUSE_MS / SONG_STEP_MS.toInt()
            }
        }
        return changed
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible || rect.width <= 0 || rect.height <= 0) return
        val text = currentText
        if (text.isEmpty()) return
        val wide = ticker && textWidth(text) > rect.width
        val a = if (wide) Align.LEFT else align
        // a looping songticker draws the title twice, the second after " *** "
        val shown = if (wide && songTicker && !bounce) text + LOOP_GAP + text else text
        val bf = bitmapFont()
        if (bf != null) {
            // Winamp 5.666 keeps 2 px free left of bitmap text (timers and drawer tabs); a
            // songticker's leftpadding="5" rightpadding="-5" moves both edges instead, and
            // without padding it starts at the edge
            val lp = leftPadding ?: if (songTicker) 0 else PAD
            val box = Rect(rect.left + lp + offsetX, rect.top + offsetY, maxOf(0, rect.width - lp + rightPadding), rect.height)
            out.add(DrawCmd.BitmapText(shown, bf, box, a, effectiveAlpha, valign, colonWidth, if (wide) scroll else 0))
        } else {
            val c = colorId?.let { rt.color(it) } ?: color
            // a shadow only with an offset, black unless shadowcolor says otherwise
            val sh = if (shadowX != 0 || shadowY != 0) shadow ?: shadowId?.let { rt.color(it) } ?: Rgb(0) else null
            // 2 px in, plus leftpadding unless a centered text fits; the right edge stays at the
            // box less 2; a songticker starts at its padding
            val rect =
                if (songTicker) {
                    val lp = leftPadding ?: 0
                    Rect(rect.left + lp + offsetX, rect.top + offsetY, maxOf(0, rect.width - lp + rightPadding), rect.height)
                } else {
                    val lp = if (a == Align.CENTER && !wide) 0 else leftPadding ?: 0
                    Rect(rect.left + PAD + lp + offsetX, rect.top + offsetY, maxOf(0, rect.width - PAD), rect.height)
                }
            if (isTime && !wide && ':' in text) {
                // a time: the pieces laid out by hand, each colon at the left of its slot; 2 px in
                // from the side it is aligned to, none when centered
                val pieces = text.split(':')
                val (widths, cw) = timePieces(text)
                val total = widths.sum() + cw * (pieces.size - 1)
                val box = this.rect
                var x =
                    when (a) {
                        Align.LEFT -> box.left + PAD + (leftPadding ?: 0)
                        Align.CENTER -> box.left + (box.width - total) / 2
                        Align.RIGHT -> box.right - rightPadding - PAD - total
                    } + offsetX
                pieces.forEachIndexed { i, piece ->
                    if (piece.isNotEmpty()) {
                        out.add(
                            DrawCmd.Text(
                                piece,
                                ttf(),
                                effectiveSize,
                                c,
                                Rect(x, rect.top, widths[i], rect.height),
                                Align.LEFT,
                                0,
                                effectiveAlpha,
                                valign,
                                bold,
                                sh,
                                shadowX,
                                shadowY,
                            ),
                        )
                    }
                    x += widths[i]
                    if (i < pieces.size - 1) {
                        out.add(
                            DrawCmd.Text(
                                ":",
                                ttf(),
                                effectiveSize,
                                c,
                                Rect(x, rect.top, cw, rect.height),
                                Align.LEFT,
                                0,
                                effectiveAlpha,
                                valign,
                                bold,
                                sh,
                                shadowX,
                                shadowY,
                            ),
                        )
                        x += cw
                    }
                }
            } else {
                out.add(
                    DrawCmd.Text(
                        shown,
                        ttf(),
                        effectiveSize,
                        c,
                        rect,
                        a,
                        if (wide) scroll else 0,
                        effectiveAlpha,
                        valign,
                        bold,
                        sh,
                        shadowX,
                        shadowY,
                    ),
                )
            }
        }
    }

    /**
     * A script setting `text` sets what shows, as setText does: on a display text it takes the
     * display's place (Winamp3x's shaded timer writes "  :  " over its TIME display).
     */
    override fun setXmlParam(
        key: String,
        value: String,
    ) {
        val k = key.lowercase()
        if ((k == "text" || k == "default") && !display.isNullOrEmpty()) {
            attrs[k] = value
            scriptText = value
            alternateText = null
            textChanged()
            return
        }
        super.setXmlParam(key, value)
    }

    /** Scripts hear `onTextChanged` with the text now shown. */
    private fun textChanged() {
        rt.invalidate()
        dispatch("ontextchanged", Value.Str(currentText))
    }

    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "settext" -> {
                Value.Null.also {
                    if (display.isNullOrEmpty()) staticText = args[0].asString else scriptText = args[0].asString
                    // new text also ends a temporary message
                    alternateText = null
                    textChanged()
                }
            }

            // a temporary message: it clears itself after a second; the same text again keeps that second
            "setalternatetext" -> {
                Value.Null.also {
                    val t = args[0].asString
                    if (t != alternateText) alternateUntil = rt.now() + ALTERNATE_MS
                    alternateText = t
                    rt.invalidate()
                }
            }

            "gettext" -> {
                Value.Str(currentText)
            }

            // the width the text asks for, its 2 px padding on both sides included: a 50 px bitmap
            // text answers 54 (measured with Bento's playlist time, which pledit.maki hides below that)
            // TrueType too
            "gettextwidth" -> {
                Value.Int(textWidth() + 2 * PAD)
            }

            else -> {
                super.makiCall(name, args)
            }
        }

    companion object {
        /**
         * The em size to draw a TrueType font at for a skin's `fontsize`. Winamp 5.666 draws text
         * at three quarters of it, rounded to whole pixels as Windows sizes fonts (Big Bento's
         * fontsize 14 draws at 11 px; measured against its screenshots).
         */
        fun emSize(fontsize: Int): Float = Math.round(fontsize * 0.75f).toFloat()

        /** A text that names no size. */
        private const val DEFAULT_SIZE = 14
        private const val PAD = 2

        /** A text ticker moves on every third poll tick: the default "Text Ticker Speed" of 1/3. */
        const val TEXT_SKIP = 2
        const val ALTERNATE_MS = 1000L
        const val EMPTY_WIDTH = 32

        /** A songticker's step: "Text Ticker Increment" 1 x 13 / speed 1/3 ms. */
        const val SONG_STEP_MS = 39L
        const val BOUNCE_PAUSE_MS = 2000
        const val LOOP_GAP = " *** "
        val TIME_DISPLAYS = setOf("time", "timeelapsed", "timeremaining")
    }
}
