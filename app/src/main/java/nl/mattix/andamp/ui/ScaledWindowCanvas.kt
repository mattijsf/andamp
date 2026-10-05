// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.withTimeoutOrNull
import nl.mattix.andamp.skin.RegionTxt
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.Sprite
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.hitTest
import nl.mattix.andamp.ui.widget.hitTestGroup
import nl.mattix.andamp.ui.window.Loupe
import nl.mattix.andamp.ui.window.LoupeGesture
import nl.mattix.andamp.ui.window.RegionShape
import kotlin.math.min

/** How long a press must hold to count as touch's right click. */
const val LONG_PRESS_MS = 450L

/** How far the finger may wander (virtual px) and still count as holding still. */
const val LONG_PRESS_SLOP = 4f

/**
 * Draws one sprite 1:1 at virtual coordinates with nearest-neighbor filtering. Source rects
 * are clamped to the sheet, so an undersized skin sheet draws what it has.
 */
fun DrawScope.sprite(
    sheet: ImageBitmap,
    sp: Sprite,
    x: Int,
    y: Int,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
) {
    val w = min(sp.w, sheet.width - sp.x)
    val h = min(sp.h, sheet.height - sp.y)
    if (w <= 0 || h <= 0) return
    drawImage(
        image = sheet,
        srcOffset = IntOffset(sp.x, sp.y),
        srcSize = IntSize(w, h),
        dstOffset = IntOffset(x, y),
        dstSize = IntSize(w, h),
        filterQuality = FilterQuality.None,
        blendMode = blendMode,
    )
}

fun DrawScope.sprite(
    sheet: ImageBitmap,
    sp: Sprite,
    at: IntOffset,
) = sprite(sheet, sp, at.x, at.y)

/**
 * Repeats a one-pixel-wide slice across [width]. With nearest-neighbor sampling a stretch is
 * identical to tiling that slice, in one draw call.
 */
fun DrawScope.spriteRow(
    sheet: ImageBitmap,
    sp: Sprite,
    x: Int,
    y: Int,
    width: Int,
) {
    val h = min(sp.h, sheet.height - sp.y)
    if (h <= 0 || width <= 0 || sp.x >= sheet.width) return
    drawImage(
        image = sheet,
        srcOffset = IntOffset(sp.x, sp.y),
        srcSize = IntSize(1, h),
        dstOffset = IntOffset(x, y),
        dstSize = IntSize(width, h),
        filterQuality = FilterQuality.None,
    )
}

/** Renders text with the 5x6 TEXT.BMP bitmap font at a 5px advance. */
fun DrawScope.bitmapText(
    textSheet: ImageBitmap,
    text: String,
    x: Int,
    y: Int,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
) {
    var cx = x
    for (c in nl.mattix.andamp.skin.BitmapFont
        .normalize(text)) {
        sprite(
            textSheet,
            nl.mattix.andamp.skin.BitmapFont
                .sprite(c),
            cx,
            y,
            blendMode,
        )
        cx += nl.mattix.andamp.skin.BitmapFont.CHAR_W
    }
}

/**
 * A window rendered at integer scale [scale]: a canvas of virtualW*scale x virtualH*scale
 * device pixels whose draw lambda works in virtual coordinates. Pointer events are converted
 * to virtual coordinates and dispatched to the widget under the finger, which captures the
 * rest of the gesture.
 */
@Composable
@Suppress("LongParameterList") // a window's size, its widgets and its two callbacks
fun ScaledWindowCanvas(
    virtualW: Int,
    virtualH: Int,
    scale: Int,
    state: WinampState,
    widgets: List<Widget>,
    modifier: Modifier = Modifier,
    /** The shape the skin asked for, when it asked for one; see [SkinCut]. */
    cut: SkinCut? = null,
    /** Any press in this window, before it is dispatched: what brings a window to the front. */
    onPress: (() -> Unit)? = null,
    /**
     * The magnifier a hold on a small control opens. [Loupe.fiddly] says which controls
     * qualify; a window's background widget never does, because it is what drags the window.
     */
    loupe: LoupeGesture? = null,
    onDraw: DrawScope.() -> Unit,
) {
    val density = LocalDensity.current
    val wDp = with(density) { (virtualW * scale).toDp() }
    val hDp = with(density) { (virtualH * scale).toDp() }
    // The gesture loop is not keyed on the widget list: a window that resizes itself
    // rebuilds its widgets on every step, and restarting the pointer input would cancel
    // the gesture doing the resizing. The list is read once per gesture and the widget it
    // hits is captured.
    val live = rememberUpdatedState(widgets)
    val press = rememberUpdatedState(onPress)
    val shape =
        cut?.let { c ->
            c.window?.let { RegionShape.of(c.skin, it, scale) } ?: RegionShape.corners(c.skin.regions.corner, scale)
        }
    val edge = cut?.window?.let { cut.skin.regions[it] }?.takeIf { it.isNotEmpty() }
    val corner = if (cut != null && cut.window == null) cut.skin.regions.corner else null
    Spacer(
        modifier
            .size(wDp, hDp)
            .clipToBounds()
            // this clip cuts the touches, so a press on a cut-away corner reaches what is
            // behind the window
            .then(if (shape != null) Modifier.clip(shape) else Modifier)
            .pointerInput(scale) {
                awaitEachGesture { oneGesture(scale, live, press, state, loupe) }
            }.drawBehind {
                // this one cuts the art with a hard edge: the layer's clip is antialiased
                // and would blend in the pixels a skin keeps outside its region
                withCut(edge, corner, scale) {
                    withTransform({ scale(scale.toFloat(), scale.toFloat(), pivot = Offset.Zero) }) {
                        onDraw()
                    }
                }
            },
    )
}

/**
 * One gesture, from the finger landing to the finger leaving: hit test, hold, drag and
 * hand-over between widgets.
 */
private suspend fun AwaitPointerEventScope.oneGesture(
    scale: Int,
    // read after the finger lands, so the hit test uses the widgets the window has then
    widgets: State<List<Widget>>,
    onPress: State<(() -> Unit)?>,
    state: WinampState,
    loupe: LoupeGesture?,
) {
    val down = awaitFirstDown()
    // before the hit test: a press anywhere on a window brings it to the front
    onPress.value?.invoke()
    val current = widgets.value
    val s = scale.toFloat()
    // the whole gesture is read as if the finger were where its press was aimed
    // a mouse or a stylus points where it presses; only a finger is aimed
    val aimed = loupe?.takeIf { down.type == PointerType.Touch }
    val shift = aimed?.shiftFor(current, down.position / s) ?: Offset.Zero
    var pos = down.position / s + shift
    val downPos = pos
    var target =
        hitTest(current, IntOffset(pos.x.toInt(), pos.y.toInt()))
            ?: return
    down.consume()
    target.down(state, pos)
    // a long press is touch's right click; it fires while the finger is still down and
    // swallows the tap on release. The lens opens after a shorter hold than the menu.
    val lens = Magnifier(loupe, target, downPos, shift)
    var deadline =
        when {
            lens.possible -> Loupe.HOLD_MS
            target.hasLongPress -> LONG_PRESS_MS
            else -> null
        }
    var longPressed = false
    while (true) {
        val waited = deadline
        val event = if (waited == null) awaitPointerEvent() else withTimeoutOrNull(waited) { awaitPointerEvent() }
        if (event == null) {
            // timed out: the hold happened, and the finger is still down
            longPressed = true
            deadline = null
            held(state, target, lens)
            continue
        }
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        pos = change.position / s + shift
        if (change.changedToUpIgnoreConsumed()) {
            change.consume()
            break
        }
        if (change.positionChanged()) {
            change.consume()
            val moved = travel(pos, downPos, target, current, lens, state)
            target = moved.target
            if (moved.travelled) deadline = null
        }
    }
    target.up(state, pos, fireTap = !longPressed)
    // when the lens is up, the release presses what the crosshair is on
    lens.release()
}

/** A hold on a small control ([Loupe.fiddly]) magnifies; anywhere else it is the long press. */
private fun held(
    state: WinampState,
    target: Widget,
    lens: Magnifier,
) {
    if (lens.possible) lens.open() else target.longPress(state)
}

/** What a movement did: who has the gesture now, and whether it cancels the hold. */
private class Moved(
    val target: Widget,
    val travelled: Boolean,
)

/** A movement steers the lens if one is up; otherwise it drags, which may hand over to a neighbor. */
@Suppress("LongParameterList") // a movement, where it started, and everything it can reach
private fun travel(
    now: Offset,
    from: Offset,
    target: Widget,
    widgets: List<Widget>,
    lens: Magnifier,
    state: WinampState,
): Moved {
    if (lens.on) {
        lens.steer(now)
        return Moved(target, travelled = false)
    }
    lens.follow(now)
    // sweeping across a drag group hands over to the widget under the finger,
    // like Winamp's EQ bands
    val next = handedOver(widgets, target, now.x, state)
    next.drag(now)
    // only travel past the slop cancels the long press: a resting finger wobbles a pixel or two
    return Moved(next, travelled = (now - from).getDistance() > LONG_PRESS_SLOP)
}

/**
 * The magnifier's half of a gesture. It applies to a small control ([Loupe.fiddly]) and never
 * to a window's background widget, which is what drags the window.
 */
private class Magnifier(
    private val loupe: LoupeGesture?,
    private val target: Widget,
    /** Where the finger came down, as the gesture reads it. */
    downAt: Offset,
    /** How far the gesture's positions are from the finger's own; see [LoupeGesture.shiftFor]. */
    private val shift: Offset,
) {
    /** Whether a hold here magnifies. */
    val possible = loupe != null && !target.background && Loupe.fiddly(target)

    /** Whether it is up now. */
    var on = false
        private set

    /** Where the finger was last seen, as the gesture reads it. */
    private var last = downAt
    private var started = false

    fun open() {
        // opens centered on the pressed control, which stays lit, and is told where the finger is
        loupe?.open(last - shift, target)
        on = true
    }

    /**
     * Moves the crosshair by what the finger did since the last event.
     *
     * The first movement after the lens opens is measured from itself, so what the finger
     * wandered while the hold timed out does not move the crosshair.
     */
    fun steer(pos: Offset) {
        if (started) loupe?.move(pos - last)
        started = true
        last = pos
    }

    /** Records the position while the lens is not up. */
    fun follow(pos: Offset) {
        last = pos
    }

    /** Presses whatever the crosshair is on, if the lens was ever opened. */
    fun release() {
        if (on) loupe?.release()
    }
}

/**
 * The widget a drag has swept onto when it shares a drag group with the one it started on,
 * as the equalizer's bands do; otherwise the one it was on.
 */
private fun handedOver(
    widgets: List<Widget>,
    target: Widget,
    x: Float,
    state: WinampState,
): Widget {
    val group = target.dragGroup ?: return target
    val next = hitTestGroup(widgets, group, x)?.takeIf { it !== target } ?: return target
    state.pressedWidget = next.id
    return next
}

/** A window's own shape: the skin that asked for one, and which of its sections. */
data class SkinCut(
    val skin: Skin,
    /**
     * The section that cuts this window, or null for a window `REGION.TXT` has no section
     * for, such as the playlist, which is cut with the skin's corner at whatever size it is.
     * See `RegionTxt.Regions.corner`.
     */
    val window: RegionTxt.Window? = null,
)

/** Draws [body] inside what the skin cut, with a hard edge, or as it is when it cut nothing. */
private inline fun DrawScope.withCut(
    polygons: List<RegionTxt.Polygon>?,
    corner: RegionTxt.Polygon?,
    scale: Int,
    body: DrawScope.() -> Unit,
) {
    val path =
        when {
            polygons != null -> RegionShape.pathOf(polygons, scale)

            // the draw scope's size is the window's size, which varies for a playlist
            corner != null -> RegionShape.cornerPath(corner, size, scale)

            else -> null
        }
    if (path == null) body() else clipPath(path) { body() }
}
