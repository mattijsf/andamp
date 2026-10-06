// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.durationSec
import nl.mattix.andamp.skin.BitmapFont
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.state.rowLabel
import nl.mattix.andamp.ui.bitmapText
import nl.mattix.andamp.ui.sprite
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import nl.mattix.andamp.ui.widget.hSlider
import kotlin.math.roundToInt

const val MAIN_W = 275
const val MAIN_H = 116

/**
 * The height of the title bar of the player and the equalizer.
 */
const val TITLE_BAR_H = 14

/**
 * The Winamp logo at the bottom right, which opens the about box. From webamp's
 * `css/main-window.css`, `#about`. It has no pressed sprite, so it is a hit rectangle over
 * art the window already draws.
 */
private const val ABOUT_X = 253
private const val ABOUT_Y = 91
private const val ABOUT_W = 13
private const val ABOUT_H = 15

fun mainWindowWidgets(
    vm: WindowControls,
    /**
     * Opens the file browser: Play calls it on an empty queue, Eject always. True when Play
     * opened it, so that what is picked is also started.
     */
    onOpenFile: (Boolean) -> Unit = {},
    /** The close button: stops playback and leaves the app. */
    onExit: () -> Unit = {},
    /** Minimize: the player goes away and keeps playing. */
    onMinimize: () -> Unit = {},
    /** The menu a long press on the visualizer opens, or null for none. */
    visualizerMenu: (MenuAnchor) -> AmpMenu? = { null },
    /** The clutter bar's O: Winamp's Options menu on its own. */
    optionsMenu: (MenuAnchor) -> AmpMenu? = { null },
    /** The clutter bar's A: Winamp's Always On Top, which here is the floating window. */
    onAlwaysOnTop: () -> Unit = {},
    /** The clutter bar's I: file info for what is playing. */
    onFileInfo: () -> Unit = {},
    /** The clutter bar's D: Winamp's Double Size. */
    onDoubleSize: () -> Unit = {},
    onOptions: () -> Unit,
): List<Widget> {
    val s = vm.state
    return listOf(
        // opens the main menu (Winamp's top-left system-menu icon)
        button("main.options", 6, 3, 9, 9, onTap = onOptions),
        // Winamp's clutter bar: O is the Options menu, A is Always On Top (the floating
        // window), I is file info, D is Double Size (the player fills the screen) and V is
        // the visualization menu.
        button("main.clutter.o", Dest.CLUTTER_O.left, Dest.CLUTTER_O.top, Dest.CLUTTER_O.width, Dest.CLUTTER_O.height) {
            s.activeMenu = optionsMenu(MenuAnchor(WindowStore.MAIN, Dest.CLUTTER_O.left, Dest.CLUTTER_O.top, 8, 8))
        },
        button(
            "main.clutter.a",
            Dest.CLUTTER_A.left,
            Dest.CLUTTER_A.top,
            Dest.CLUTTER_A.width,
            Dest.CLUTTER_A.height,
            onTap = onAlwaysOnTop,
        ),
        button(
            "main.clutter.i",
            Dest.CLUTTER_I.left,
            Dest.CLUTTER_I.top,
            Dest.CLUTTER_I.width,
            Dest.CLUTTER_I.height,
            onTap = onFileInfo,
        ),
        button(
            "main.clutter.d",
            Dest.CLUTTER_D.left,
            Dest.CLUTTER_D.top,
            Dest.CLUTTER_D.width,
            Dest.CLUTTER_D.height,
            onTap = onDoubleSize,
        ),
        button(
            "main.clutter.v",
            Dest.CLUTTER_V.left,
            Dest.CLUTTER_V.top,
            Dest.CLUTTER_V.width,
            Dest.CLUTTER_V.height,
        ) {
            s.activeMenu = visualizerMenu(MenuAnchor(WindowStore.MAIN, Dest.CLUTTER_V.left, Dest.CLUTTER_V.top, 8, 7))
        },
        button("main.minimize", 244, 3, 9, 9, onTap = onMinimize),
        // collapses the window to its title bar; inert while the shade is switched off
        button("main.shade", 254, 3, 9, 9, inert = { !s.shadeEnabled }) { s.setShaded(WindowStore.MAIN, true, MAIN_H) },
        button("main.close", 264, 3, 9, 9, onTap = onExit),
        // the Winamp logo in the bottom-right corner opens the about box
        button("main.about", ABOUT_X, ABOUT_Y, ABOUT_W, ABOUT_H) { s.aboutOpen = true },
        button("main.time", 39, 26, 59, 13) { s.timeRemaining = !s.timeRemaining },
        marqueeDrag(s),
        // a tap cycles the visualizer mode, as in Winamp; a long press opens its menu
        button(
            "main.vis",
            Dest.VISUALIZER.x,
            Dest.VISUALIZER.y,
            Dest.VIS_W,
            Dest.VIS_H,
            onLongPress = {
                s.activeMenu =
                    visualizerMenu(MenuAnchor(WindowStore.MAIN, Dest.VISUALIZER.x, Dest.VISUALIZER.y, Dest.VIS_W, Dest.VIS_H))
            },
        ) { s.visMode = s.visMode.next() },
        hSlider(
            "main.posbar",
            Dest.POSBAR.x,
            Dest.POSBAR.y,
            248,
            10,
            thumbW = 29,
            onChange = { if (s.transport != Transport.Stopped) s.seekPreview = it },
            onCommit = { if (s.transport != Transport.Stopped) vm.seekTo(it) else s.seekPreview = null },
        ),
        hSlider(
            "main.volume",
            Dest.VOLUME.x,
            Dest.VOLUME.y,
            68,
            13,
            thumbW = 14,
            travelOverride = 51,
            onChange = { vm.setVolume(it) },
        ),
        hSlider(
            "main.balance",
            Dest.BALANCE.x,
            Dest.BALANCE.y,
            38,
            13,
            thumbW = 14,
            onChange = { vm.setBalance(SliderMath.stickyBalance((it * 200).roundToInt() - 100)) },
        ),
        button("main.eqToggle", Dest.EQ_TOGGLE.x, Dest.EQ_TOGGLE.y, 23, 12) {
            s.eqVisible = !s.eqVisible
            if (s.eqVisible) s.raiseWindow("eq")
        },
        button("main.plToggle", Dest.PL_TOGGLE.x, Dest.PL_TOGGLE.y, 23, 12) {
            s.plVisible = !s.plVisible
            if (s.plVisible) s.raiseWindow("pl")
        },
        button("main.prev", Dest.PREV.x, Dest.PREV.y, 23, 18, onTap = vm::previous),
        button("main.play", Dest.PLAY.x, Dest.PLAY.y, 23, 18) { playOrOpen(s.playlist, vm::play) { onOpenFile(true) } },
        button("main.pause", Dest.PAUSE.x, Dest.PAUSE.y, 23, 18, onTap = vm::pause),
        button("main.stop", Dest.STOP.x, Dest.STOP.y, 23, 18, onTap = vm::stop),
        button("main.next", Dest.NEXT.x, Dest.NEXT.y, 22, 18, onTap = vm::next),
        button("main.eject", Dest.EJECT.x, Dest.EJECT.y, 22, 16) { onOpenFile(false) },
        button("main.shuffle", Dest.SHUFFLE.x, Dest.SHUFFLE.y, 47, 15, onTap = vm::toggleShuffle),
        button("main.repeat", Dest.REPEAT.x, Dest.REPEAT.y, 28, 15, onTap = vm::toggleRepeat),
    )
}

fun DrawScope.drawMainWindow(
    skin: Skin,
    s: WinampState,
) {
    val pressed = s.pressedWidget
    val stopped = s.transport == Transport.Stopped

    sprite(skin[Sheet.MAIN], SpriteMap.MAIN_WINDOW_BACKGROUND, 0, 0)
    val titlebar = skin[Sheet.TITLEBAR]
    sprite(titlebar, SpriteMap.MAIN_TITLE_BAR_SELECTED, Dest.TITLE_BAR)
    sprite(titlebar, SpriteMap.MAIN_CLUTTER_BAR_BACKGROUND, Dest.CLUTTER_BAR)
    // the bar's art has the unlit letters, so only the lit ones are drawn over it
    clutterLit(pressed, s.alwaysOnTop, s.doubleSize).forEach { (art, at) -> sprite(titlebar, art, at.left, at.top) }

    // titlebar buttons: normal art is part of the titlebar strip; draw pressed art only
    when (pressed) {
        "main.options" -> sprite(titlebar, SpriteMap.MAIN_OPTIONS_BUTTON_DEPRESSED, Dest.OPTIONS_BUTTON)
        "main.minimize" -> sprite(titlebar, SpriteMap.MAIN_MINIMIZE_BUTTON_DEPRESSED, Dest.MINIMIZE_BUTTON)
        "main.shade" -> sprite(titlebar, SpriteMap.MAIN_SHADE_BUTTON_DEPRESSED, Dest.SHADE_BUTTON)
        "main.close" -> sprite(titlebar, SpriteMap.MAIN_CLOSE_BUTTON_DEPRESSED, Dest.CLOSE_BUTTON)
    }

    // play/pause/stop indicator
    val playpaus = skin[Sheet.PLAYPAUS]
    val indicator =
        when (s.transport) {
            Transport.Playing -> SpriteMap.MAIN_PLAYING_INDICATOR
            Transport.Paused -> SpriteMap.MAIN_PAUSED_INDICATOR
            Transport.Stopped -> SpriteMap.MAIN_STOPPED_INDICATOR
        }
    sprite(playpaus, indicator, Dest.PLAY_PAUSE_INDICATOR)

    drawTime(skin, s)
    drawMarquee(skin, s)

    // bitrate / sample rate (hidden when stopped)
    if (!stopped) {
        val text = skin[Sheet.TEXT]
        // unknown values draw nothing
        s.bitrateKbps?.let { bitmapText(text, it.toString(), Dest.KBPS.x, Dest.KBPS.y) }
        s.sampleRateKhz?.let { bitmapText(text, it.toString(), Dest.KHZ.x, Dest.KHZ.y) }
    }

    // mono / stereo lamps: stereo is lit whenever playback is not stopped
    val monoster = skin[Sheet.MONOSTER]
    sprite(monoster, SpriteMap.MAIN_MONO, Dest.MONO)
    sprite(monoster, if (stopped) SpriteMap.MAIN_STEREO else SpriteMap.MAIN_STEREO_SELECTED, Dest.STEREO)

    drawVisualizer(skin)
    drawVolume(skin, s)
    drawBalance(skin, s)

    // EQ / PL window toggles
    val shufrep = skin[Sheet.SHUFREP]
    sprite(
        shufrep,
        fourState(
            s.eqVisible,
            pressed == "main.eqToggle",
            SpriteMap.MAIN_EQ_BUTTON,
            SpriteMap.MAIN_EQ_BUTTON_DEPRESSED,
            SpriteMap.MAIN_EQ_BUTTON_SELECTED,
            SpriteMap.MAIN_EQ_BUTTON_DEPRESSED_SELECTED,
        ),
        Dest.EQ_TOGGLE,
    )
    sprite(
        shufrep,
        fourState(
            s.plVisible,
            pressed == "main.plToggle",
            SpriteMap.MAIN_PLAYLIST_BUTTON,
            SpriteMap.MAIN_PLAYLIST_BUTTON_DEPRESSED,
            SpriteMap.MAIN_PLAYLIST_BUTTON_SELECTED,
            SpriteMap.MAIN_PLAYLIST_BUTTON_DEPRESSED_SELECTED,
        ),
        Dest.PL_TOGGLE,
    )

    // position bar. The thumb is hidden while stopped and for a stream, which has no
    // position.
    val posbar = skin[Sheet.POSBAR]
    sprite(posbar, SpriteMap.MAIN_POSITION_SLIDER_BACKGROUND, Dest.POSBAR)
    if (!stopped && s.currentTrack?.isStream != true) {
        val thumb =
            if (pressed == "main.posbar") {
                SpriteMap.MAIN_POSITION_SLIDER_THUMB_SELECTED
            } else {
                SpriteMap.MAIN_POSITION_SLIDER_THUMB
            }
        sprite(posbar, thumb, Dest.POSBAR.x + SliderMath.posbarThumbOffset(s.positionFraction), Dest.POSBAR.y)
    }

    // transport buttons
    val cbuttons = skin[Sheet.CBUTTONS]

    fun transport(
        id: String,
        normal: nl.mattix.andamp.skin.Sprite,
        active: nl.mattix.andamp.skin.Sprite,
        at: androidx.compose.ui.unit.IntOffset,
    ) = sprite(cbuttons, if (pressed == id) active else normal, at)
    transport("main.prev", SpriteMap.MAIN_PREVIOUS_BUTTON, SpriteMap.MAIN_PREVIOUS_BUTTON_ACTIVE, Dest.PREV)
    transport("main.play", SpriteMap.MAIN_PLAY_BUTTON, SpriteMap.MAIN_PLAY_BUTTON_ACTIVE, Dest.PLAY)
    transport("main.pause", SpriteMap.MAIN_PAUSE_BUTTON, SpriteMap.MAIN_PAUSE_BUTTON_ACTIVE, Dest.PAUSE)
    transport("main.stop", SpriteMap.MAIN_STOP_BUTTON, SpriteMap.MAIN_STOP_BUTTON_ACTIVE, Dest.STOP)
    transport("main.next", SpriteMap.MAIN_NEXT_BUTTON, SpriteMap.MAIN_NEXT_BUTTON_ACTIVE, Dest.NEXT)
    transport("main.eject", SpriteMap.MAIN_EJECT_BUTTON, SpriteMap.MAIN_EJECT_BUTTON_ACTIVE, Dest.EJECT)

    // shuffle / repeat
    sprite(
        shufrep,
        fourState(
            s.shuffle,
            pressed == "main.shuffle",
            SpriteMap.MAIN_SHUFFLE_BUTTON,
            SpriteMap.MAIN_SHUFFLE_BUTTON_DEPRESSED,
            SpriteMap.MAIN_SHUFFLE_BUTTON_SELECTED,
            SpriteMap.MAIN_SHUFFLE_BUTTON_SELECTED_DEPRESSED,
        ),
        Dest.SHUFFLE,
    )
    sprite(
        shufrep,
        fourState(
            s.repeat,
            pressed == "main.repeat",
            SpriteMap.MAIN_REPEAT_BUTTON,
            SpriteMap.MAIN_REPEAT_BUTTON_DEPRESSED,
            SpriteMap.MAIN_REPEAT_BUTTON_SELECTED,
            SpriteMap.MAIN_REPEAT_BUTTON_SELECTED_DEPRESSED,
        ),
        Dest.REPEAT,
    )
}

private fun fourState(
    on: Boolean,
    pressed: Boolean,
    off: nl.mattix.andamp.skin.Sprite,
    offPressed: nl.mattix.andamp.skin.Sprite,
    onSprite: nl.mattix.andamp.skin.Sprite,
    onPressed: nl.mattix.andamp.skin.Sprite,
) = when {
    on && pressed -> onPressed
    on -> onSprite
    pressed -> offPressed
    else -> off
}

private fun DrawScope.drawTime(
    skin: Skin,
    s: WinampState,
) {
    if (s.transport == Transport.Stopped) return
    if (s.transport == Transport.Paused && !s.blinkOn) return
    val sheet = skin.numbersSheet
    val duration = s.currentTrack?.durationSec ?: return
    val shown = if (s.timeRemaining) (duration - s.currentTimeSec).coerceAtLeast(0) else s.currentTimeSec
    val minutes = (shown / 60).coerceAtMost(99)
    val seconds = shown % 60
    // minus sign (or its blank placeholder art)
    if (skin.hasNumsEx) {
        val sp = if (s.timeRemaining) SpriteMap.MINUS_SIGN_EX else SpriteMap.NO_MINUS_SIGN_EX
        sprite(sheet, sp, Dest.TIME_MINUS_EX)
    } else {
        val sp = if (s.timeRemaining) SpriteMap.MINUS_SIGN else SpriteMap.NO_MINUS_SIGN
        sprite(sheet, sp, Dest.TIME_MINUS)
    }
    val digits = intArrayOf(minutes / 10, minutes % 10, seconds / 10, seconds % 10)
    for (i in digits.indices) {
        if (i == 0 && digits[0] == 0) continue // tens-of-minutes slot stays blank under 10:00
        sprite(sheet, SpriteMap.digit(digits[i]), Dest.TIME_DIGIT_X[i], Dest.TIME_DIGIT_Y)
    }
}

private fun DrawScope.drawMarquee(
    skin: Skin,
    s: WinampState,
) {
    // a slider under the finger takes over the marquee, as in Winamp
    val raw =
        s.marqueeOverride ?: run {
            val track = s.currentTrack ?: return // empty playlist: blank marquee
            "${s.currentIndex + 1}. ${s.rowLabel(track, s.currentIndex)} (${formatTime(track.durationSec)})"
        }
    val text = BitmapFont.normalize(raw)
    val textSheet = skin[Sheet.TEXT]
    val visibleChars = Dest.MARQUEE_W / BitmapFont.CHAR_W // 30 full glyphs + partial
    clipRect(
        Dest.MARQUEE.x.toFloat(),
        Dest.MARQUEE.y.toFloat(),
        (Dest.MARQUEE.x + Dest.MARQUEE_W).toFloat(),
        (Dest.MARQUEE.y + BitmapFont.CHAR_H).toFloat(),
    ) {
        if (text.length <= visibleChars) {
            bitmapText(textSheet, text, Dest.MARQUEE.x, Dest.MARQUEE.y)
        } else {
            val scroll = "$text  ***  "
            // the clock counts characters and the finger counts pixels; both are added
            // here, so the text can start part way through a glyph
            val span = scroll.length * BitmapFont.CHAR_W
            val moved = (s.marqueeStep * BitmapFont.CHAR_W + s.marqueePixels).mod(span)
            val offset = moved / BitmapFont.CHAR_W
            var cx = Dest.MARQUEE.x - moved % BitmapFont.CHAR_W
            for (i in 0..visibleChars + 1) {
                val c = scroll[(offset + i) % scroll.length]
                sprite(textSheet, BitmapFont.sprite(c), cx, Dest.MARQUEE.y)
                cx += BitmapFont.CHAR_W
            }
        }
    }
}

private fun DrawScope.drawVolume(
    skin: Skin,
    s: WinampState,
) {
    val sheet = skin[Sheet.VOLUME]
    val bg = SpriteMap.MAIN_VOLUME_BACKGROUND
    val frameY = SpriteMap.volumeFrameY(SliderMath.volumeFrame(s.volume))
    sprite(
        sheet,
        nl.mattix.andamp.skin
            .Sprite(bg.x, frameY, 68, 13),
        Dest.VOLUME,
    )
    val thumb = if (s.pressedWidget == "main.volume") SpriteMap.MAIN_VOLUME_THUMB_SELECTED else SpriteMap.MAIN_VOLUME_THUMB
    sprite(sheet, thumb, Dest.VOLUME.x + SliderMath.volumeThumbOffset(s.volume), Dest.VOLUME.y + 1)
}

private fun DrawScope.drawBalance(
    skin: Skin,
    s: WinampState,
) {
    val sheet = skin.balanceSheet
    val bg = skin.balanceBackground
    val frameY = SpriteMap.volumeFrameY(SliderMath.balanceFrame(s.balance))
    sprite(
        sheet,
        nl.mattix.andamp.skin
            .Sprite(bg.x, frameY, 38, 13),
        Dest.BALANCE,
    )
    val thumb = if (s.pressedWidget == "main.balance") SpriteMap.MAIN_BALANCE_THUMB_ACTIVE else SpriteMap.MAIN_BALANCE_THUMB
    sprite(sheet, thumb, Dest.BALANCE.x + SliderMath.balanceThumbOffset(s.balance), Dest.BALANCE.y + 1)
}

/**
 * The visualizer's background only. The animated bars are drawn on their own overlay canvas
 * (`VisualizerOverlay` in PlayerFloatWindows.kt), so a frame repaints that box and not the
 * whole window.
 */
private fun DrawScope.drawVisualizer(skin: Skin) {
    val x0 = Dest.VISUALIZER.x
    val y0 = Dest.VISUALIZER.y
    drawRect(skin.visColors[0], Offset(x0.toFloat(), y0.toFloat()), Size(Dest.VIS_W.toFloat(), Dest.VIS_H.toFloat()))
}

/**
 * The title's drag, after webamp's Marquee: a pixel of finger is a pixel of text, the
 * scrolling stops while the finger is down, and the title stays where it was left. The
 * scrolling resumes about a second after the finger leaves.
 */
private fun marqueeDrag(s: WinampState): Widget {
    var from = 0f
    var at = 0
    return Widget(
        "main.marquee",
        IntRect(Dest.MARQUEE.x, Dest.MARQUEE.y, Dest.MARQUEE.x + Dest.MARQUEE_W, Dest.MARQUEE.y + BitmapFont.CHAR_H),
        pointer =
            Widget.Pointer(
                onDown = {
                    from = it.x
                    at = s.marqueePixels
                    s.marqueeHeld = true
                },
                // dragging leftwards moves the text leftwards: the offset grows
                onDrag = { s.marqueePixels = at - (it.x - from).toInt() },
                onUp = { _, _ -> s.marqueeHeld = false },
            ),
    )
}

/**
 * The lit clutter letters, and where each goes.
 *
 * A letter lights while it is held. The A stays lit while Always On Top is on, and the D
 * while Double Size is.
 */
private fun clutterLit(
    pressed: String?,
    alwaysOnTop: Boolean,
    doubleSize: Boolean,
): List<Pair<nl.mattix.andamp.skin.Sprite, androidx.compose.ui.unit.IntRect>> =
    listOfNotNull(
        (SpriteMap.MAIN_CLUTTER_BAR_BUTTON_O_SELECTED to Dest.CLUTTER_O).takeIf { pressed == "main.clutter.o" },
        (SpriteMap.MAIN_CLUTTER_BAR_BUTTON_A_SELECTED to Dest.CLUTTER_A).takeIf { pressed == "main.clutter.a" || alwaysOnTop },
        (SpriteMap.MAIN_CLUTTER_BAR_BUTTON_I_SELECTED to Dest.CLUTTER_I).takeIf { pressed == "main.clutter.i" },
        (SpriteMap.MAIN_CLUTTER_BAR_BUTTON_D_SELECTED to Dest.CLUTTER_D).takeIf { pressed == "main.clutter.d" || doubleSize },
        (SpriteMap.MAIN_CLUTTER_BAR_BUTTON_V_SELECTED to Dest.CLUTTER_V).takeIf { pressed == "main.clutter.v" },
    )
