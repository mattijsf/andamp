// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.RegionTxt
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.ListFont
import nl.mattix.andamp.state.SpectrumColumns
import nl.mattix.andamp.state.SpectrumPhysics
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.InPlayerVisualizerDriver
import nl.mattix.andamp.ui.PlayerViewport
import nl.mattix.andamp.ui.PlayerViewportBox
import nl.mattix.andamp.ui.ScaledWindowCanvas
import nl.mattix.andamp.ui.SkinCut
import nl.mattix.andamp.ui.UiClocks
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import nl.mattix.andamp.ui.widget.hitTest
import nl.mattix.andamp.ui.window.EQ_H
import nl.mattix.andamp.ui.window.EQ_W
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import nl.mattix.andamp.ui.window.SliderMath
import nl.mattix.andamp.ui.window.VisBox
import nl.mattix.andamp.ui.window.VisualizerOverlay
import nl.mattix.andamp.ui.window.WindowControls
import nl.mattix.andamp.ui.window.drawEqWindow
import nl.mattix.andamp.ui.window.drawMainWindow
import nl.mattix.andamp.ui.window.eqWindowWidgets
import nl.mattix.andamp.ui.window.mainWindowWidgets
import nl.mattix.andamp.wal.WalStill
import nl.mattix.andamp.widget.VisFrame
import nl.mattix.walkit.ClassicVisMode
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * What a lent surface shows: one window at a time, on black, in the middle of [within].
 * [within] is in the surface's pixels; null is all of it. The window is drawn at the largest
 * whole scale that fits there. With [fill] it is as large as the whole surface takes, [within]
 * or not: the owner keeps room free for buttons it shows only now and then, and a window kept
 * out of that room is small on some cars' screens. See [remoteViewport].
 * The D in the main window's clutter bar is lit by [fill] and calls [onFill]: this surface's
 * own Double Size. The A is never lit here, and does nothing. Nor are the EQ and PL buttons
 * lit: neither window is open beside this one.
 *
 * It starts on the main window. The EQ button there swaps the screen to the equalizer and the
 * PL button to the playlist; the close button of either swaps it back. Which one is up belongs
 * to this surface and is not kept.
 *
 * The main window and the equalizer are drawn whole. The controls that need another window or
 * a menu do nothing here; see [mainAlone] and [eqAlone]. The playlist is [RemoteList], a list
 * to pick a song from, which fills the room.
 *
 * [sliders] is kept told where the sliders of the window that is up are, for a drag the
 * surface's owner reports by its distance alone; see [HeldSlider]. The playlist's rows are
 * written in [listFont].
 */
@Composable
internal fun RemoteScreen(
    vm: WinampViewModel,
    skin: Skin,
    /** [skin]'s file, which the engine draws the windows from; null until it is read. */
    skinFile: File?,
    within: IntRect?,
    scroll: ListScroll,
    sliders: Sliders = Sliders(),
    showing: Showing = remember { Showing() },
    fill: Boolean = false,
    onFill: () -> Unit = {},
    listFont: ListFont = ListFont.REGULAR,
) {
    val s = vm.state
    var up by showing::up
    val onFillNow by rememberUpdatedState(onFill)
    val main =
        remember(vm) {
            mainAlone(vm, onEq = { up = Up.EQUALIZER }, onPl = { up = Up.PLAYLIST }, onFill = { onFillNow() })
        }
    val eq = remember(vm) { eqAlone(vm, onClose = { up = Up.MAIN }) }
    InPlayerVisualizerDriver(vm)
    UiClocks(s)
    val context = LocalContext.current
    val still = remember(skinFile) { skinFile?.let { WalStill.of(it, context.cacheDir, WalStill.CAR) } }
    val picture = rememberStillPicture(still, s, if (up == Up.EQUALIZER) WalStill.EQUALIZER else WalStill.MAIN) { vm.audioTap }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val whole = IntRect(0, 0, constraints.maxWidth, constraints.maxHeight)
        val asked = if (fill) whole else within ?: whole
        val fit = remoteViewport(asked.width, asked.height, fill)
        // the windows are laid out on the viewport's surface, so the room is in its pixels
        PlayerViewportBox(fit) {
            val room = IntRect(fit.inner(asked.left), fit.inner(asked.top), fit.inner(asked.right), fit.inner(asked.bottom))
            if (up == Up.PLAYLIST) {
                RemoteList(vm, skin, room, fit, scroll, sliders, listFont, onClose = { up = Up.MAIN })
            } else {
                // the two windows are one size, so the swap moves nothing
                val left = room.left + (room.width - MAIN_W * fit.scale) / 2
                val top = room.top + (room.height - MAIN_H * fit.scale) / 2
                val controls = if (up == Up.EQUALIZER) eq else main
                SideEffect { sliders.at = { x, y -> sliderAt(controls, IntOffset(left, top), fit, x, y) } }
                Box(Modifier.offset { IntOffset(left, top) }) {
                    if (up == Up.EQUALIZER) {
                        EqualizerAlone(skin, s, fit.scale, eq, picture)
                    } else {
                        MainAlone(skin, s, fit.scale, main, fill, picture)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainAlone(
    skin: Skin,
    s: WinampState,
    scale: Int,
    widgets: List<Widget>,
    fill: Boolean,
    /** The window as the engine draws it, at 1x; null until the first one is drawn. */
    picture: ImageBitmap?,
) {
    ScaledWindowCanvas(MAIN_W, MAIN_H, scale, s, widgets, cut = SkinCut(skin, RegionTxt.Window.MAIN)) {
        if (picture != null) {
            drawImage(picture, dstSize = IntSize(picture.width, picture.height), filterQuality = FilterQuality.None)
        } else {
            drawMainWindow(skin, s, alwaysOnTop = false, doubleSize = fill, equalizerOpen = false, playlistOpen = false)
        }
    }
    // the engine's picture has the visualizer in it
    if (picture != null) return
    // the visualizer animates on its own canvas, so a frame repaints only that box
    VisualizerOverlay(
        skin,
        s,
        scale,
        VisBox.FULL,
        Modifier.offset { IntOffset(Dest.VISUALIZER.x * scale, Dest.VISUALIZER.y * scale) },
    )
}

@Composable
private fun EqualizerAlone(
    skin: Skin,
    s: WinampState,
    scale: Int,
    widgets: List<Widget>,
    picture: ImageBitmap?,
) {
    ScaledWindowCanvas(EQ_W, EQ_H, scale, s, widgets, cut = SkinCut(skin, RegionTxt.Window.EQ)) {
        if (picture !=
            null
        ) {
            drawImage(picture, dstSize = IntSize(picture.width, picture.height), filterQuality = FilterQuality.None)
        } else {
            drawEqWindow(skin, s)
        }
    }
}

/** The window a lent surface is showing. */
internal enum class Up { MAIN, EQUALIZER, PLAYLIST }

/**
 * Which window a lent surface shows. The buttons in the windows change it, and so does the
 * surface's owner, through [listOrBack]. Each change is reported to [changed].
 */
internal class Showing(
    private val changed: () -> Unit = {},
) {
    private var now by mutableStateOf(Up.MAIN)

    var up: Up
        get() = now
        set(value) {
            if (value == now) return
            now = value
            changed()
        }

    /** The list of songs from the main window; from the equalizer or the list, the main window. */
    fun listOrBack() {
        up = if (up == Up.MAIN) Up.PLAYLIST else Up.MAIN
    }
}

/**
 * The slider among [widgets] that a touch at ([x], [y]) on the surface lands on, or null.
 * The window's top left corner is at [origin] on [fit]'s surface, which is drawn
 * [PlayerViewport.shrink] times its size.
 */
internal fun sliderAt(
    widgets: List<Widget>,
    origin: IntOffset,
    fit: PlayerViewport,
    x: Float,
    y: Float,
): SurfaceSlider? {
    fun inWindow(
        onSurface: Float,
        from: Int,
    ) = floor((onSurface / fit.shrink - from) / fit.scale).toInt()

    fun onSurface(
        inWindow: Float,
        from: Int,
    ) = (from + inWindow * fit.scale) * fit.shrink

    val hit = hitTest(widgets, IntOffset(inWindow(x, origin.x), inWindow(y, origin.y))) ?: return null
    val handle = hit.handle ?: return null
    val at = hit.bounds
    return SurfaceSlider(
        hit.id,
        IntRect(
            onSurface(at.left.toFloat(), origin.x).roundToInt(),
            onSurface(at.top.toFloat(), origin.y).roundToInt(),
            onSurface(at.right.toFloat(), origin.x).roundToInt(),
            onSurface(at.bottom.toFloat(), origin.y).roundToInt(),
        ),
    ) { handle().let { Offset(onSurface(it.x, origin.x), onSurface(it.y, origin.y)) } }
}

/** How many pixels a virtual pixel gets in a room of this size: whole, and at least one. */
internal fun wholeScale(
    width: Int,
    height: Int,
): Int = minOf(width / MAIN_W, height / MAIN_H).coerceAtLeast(1)

/**
 * How a window is fitted into a room of [width] by [height] surface pixels.
 *
 * Without [fill], it is drawn at [wholeScale] and not shrunk, so every pixel of the skin is a
 * whole number of the surface's. With [fill], it is as large as the room takes in its own
 * shape: laid out at the next whole scale up and drawn smaller as one picture, as a player
 * that fills a phone's screen is (see [nl.mattix.andamp.ui.playerViewport]). A room that is a
 * whole multiple of the window is filled without shrinking.
 */
internal fun remoteViewport(
    width: Int,
    height: Int,
    fill: Boolean,
): PlayerViewport {
    if (!fill || width <= 0 || height <= 0) return PlayerViewport(wholeScale(width, height), 1f)
    // compared as whole numbers, so a room of exactly the window's shape is decided one way
    val widthDecides = width.toLong() * MAIN_H <= height.toLong() * MAIN_W
    val room = if (widthDecides) width else height
    val window = if (widthDecides) MAIN_W else MAIN_H
    val scale = ((room + window - 1) / window).coerceAtLeast(1)
    return PlayerViewport(scale, room.toFloat() / (window * scale))
}

/**
 * The main window's controls on a surface that shows one window: those that need nothing but
 * the window itself ([MAIN_ALONE]), the EQ and PL buttons, which call [onEq] and [onPl] in
 * place of opening a window beside it, and the clutter bar's D, which calls [onFill] in place
 * of the phone's Double Size.
 *
 * The balance finds its middle from farther off here ([WIDE_BALANCE_DETENT]): on a car's
 * screen its whole travel is about a finger wide, and a drag is no way to hit one pixel of it.
 */
internal fun mainAlone(
    vm: WindowControls,
    onEq: () -> Unit,
    onPl: () -> Unit,
    onFill: () -> Unit,
): List<Widget> {
    val here =
        object : WindowControls by vm {
            override fun setBalance(balance: Int) = vm.setBalance(SliderMath.stickyBalance(balance, WIDE_BALANCE_DETENT))
        }
    return mainWindowWidgets(here, onDoubleSize = onFill, onOptions = {}).mapNotNull { stock ->
        val at = stock.bounds
        when (stock.id) {
            EQ_SWITCH -> button(EQ_SWITCH, at.left, at.top, at.width, at.height, onTap = onEq)
            PL_SWITCH -> button(PL_SWITCH, at.left, at.top, at.width, at.height, onTap = onPl)
            in MAIN_ALONE, FILL_SWITCH -> stock
            else -> null
        }
    }
}

/** How near its middle the balance has to be brought on a lent surface to rest there, of 100 either way. */
internal const val WIDE_BALANCE_DETENT = 40

/**
 * The equalizer's controls on a surface that shows one window: everything but the preset
 * menu and the shade button. The close button calls [onClose], and takes a touch anywhere in
 * [EQ_CLOSE_REACH]: on a car's screen the 9x9 button itself is a small thing to hit.
 */
internal fun eqAlone(
    vm: WindowControls,
    onClose: () -> Unit,
): List<Widget> =
    listOf(
        button(EQ_CLOSE, EQ_CLOSE_REACH.left, EQ_CLOSE_REACH.top, EQ_CLOSE_REACH.width, EQ_CLOSE_REACH.height, onTap = onClose),
    ) + eqWindowWidgets(vm).filter { it.id !in EQ_NOT_ALONE }

private const val EQ_SWITCH = "main.eqToggle"
private const val PL_SWITCH = "main.plToggle"
private const val FILL_SWITCH = "main.clutter.d"
private const val EQ_CLOSE = "eq.close"

/** The right end of the equalizer's title bar, down to the row of buttons under it. */
internal val EQ_CLOSE_REACH = IntRect(235, 0, 275, 17)

/** The controls of the main window that need nothing but the window itself. */
internal val MAIN_ALONE =
    setOf(
        "main.time",
        "main.vis",
        "main.marquee",
        "main.posbar",
        "main.volume",
        "main.balance",
        "main.prev",
        "main.play",
        "main.pause",
        "main.stop",
        "main.next",
        "main.shuffle",
        "main.repeat",
    )

/** The equalizer's controls that are replaced or need more than the window: see [eqAlone]. */
private val EQ_NOT_ALONE = setOf(EQ_CLOSE, "eq.shade", "eq.presets")

/**
 * The window [window] of [still]'s skin as [s] has it, drawn again each display frame off the main
 * thread, with the audio's spectrum and waveform from [tap] in its visualizer; null until the first
 * picture is drawn, or without a [still].
 */
@Composable
private fun rememberStillPicture(
    still: WalStill?,
    s: WinampState,
    window: String,
    tap: () -> nl.mattix.andamp.core.playback.AudioTap?,
): ImageBitmap? {
    var picture by remember(still, window) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(still, window) {
        still ?: return@LaunchedEffect
        var columns: SpectrumColumns? = null
        var last = 0L
        while (true) {
            val now = withFrameMillis { it }
            if (now - last < STILL_FRAME_MS) continue
            val elapsed = if (last == 0L) STILL_FRAME_MS else now - last
            last = now
            columns = columns ?: tap()?.let { SpectrumColumns(it, STILL_FRAME_MS, VisFrame.BANDS, VisFrame.WAVE) }
            val heard = columns?.takeIf { s.transport == Transport.Playing }?.takeIf { it.read(elapsed) }
            val moment =
                WalStill.stillOf(s, now, window = window).copy(
                    vis = visOf(s.visMode),
                    spectrum =
                        heard?.let { c -> FloatArray(c.values.size) { (c.values[it] / SpectrumPhysics.MAX).coerceIn(0f, 1f) } }
                            ?: FloatArray(0),
                    waveform = heard?.wave?.copyOf() ?: FloatArray(0),
                )
            withContext(Dispatchers.Default) { still.main(moment) }?.let { picture = it }
        }
    }
    return picture
}

/** The skin's visualizer mode for the app's. */
private fun visOf(mode: VisMode) =
    when (mode) {
        VisMode.Analyzer -> ClassicVisMode.ANALYZER
        VisMode.Oscilloscope -> ClassicVisMode.SCOPE
        VisMode.Off -> ClassicVisMode.OFF
    }

/** How often the engine's picture of the window is drawn again: about 30 times a second. */
private const val STILL_FRAME_MS = 33L
