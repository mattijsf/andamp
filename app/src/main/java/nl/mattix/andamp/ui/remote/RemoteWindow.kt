// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.CurrentSkin
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.overlay.OverlayOwners

/**
 * The main window on a surface another app owns.
 *
 * The surface becomes a display of this app's own, a virtual display, and a presentation on
 * that display holds the window, composed as it is on the phone's screen. So the skin, the
 * visualizer and the title are drawn by the code that draws them there. The skin is the one
 * chosen for Android Auto; see [nl.mattix.andamp.state.AndroidAutoStore].
 *
 * The owner of the surface reports a tap by its place. It is given to the window as a press
 * and, [PRESS_MS] later, a release, so a button is seen going down. A drag is reported by
 * its distance only. It moves the slider the last tap landed on ([HeldSlider]), and with no
 * slider held it scrolls [list]. The list's scrollbar is such a slider, and is let go again
 * once it has been left alone for [SCROLLBAR_MS]: a drag over a list is the list's own.
 *
 * Every call is made on the main thread.
 */
internal class RemoteWindow(
    private val context: Context,
    private val vm: WinampViewModel,
) {
    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var owners: OverlayOwners? = null

    /** The part of the surface the window keeps to, or null for all of it. */
    private var kept by mutableStateOf<IntRect?>(null)

    /** Where the playlist on the surface is scrolled to; a drag on the surface moves it. */
    val list = ListScroll()

    /** Where the sliders of the window that is up are; the screen keeps it told. */
    private val sliders = Sliders()

    /** The slider the last tap landed on, which a drag moves. */
    private val slider = HeldSlider(::touch)

    /** When the finger that moves [slider] came down. */
    private var sliderDownAt = 0L

    /** Lifts that finger once the drag has gone quiet: its owner does not say when it ends. */
    private val lift = Runnable { slider.letGo() }

    /** Lets go of the list's scrollbar once it has been left alone. */
    private val forget = Runnable { slider.tapped(null) }

    /** The release of the tap that is down, until it has been sent. */
    private var release: Runnable? = null

    val showing: Boolean get() = presentation != null

    /** Shows the window on [surface]; a window that is already up moves there. */
    fun show(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ) {
        hide()
        val displays = context.getSystemService(DisplayManager::class.java) ?: return
        // a surface its owner has already let go of gives no display
        val made =
            runCatching {
                displays.createVirtualDisplay(
                    DISPLAY_NAME,
                    width,
                    height,
                    densityDpi,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                )
            }.getOrNull() ?: return
        val holders = OverlayOwners()
        val shown = Presentation(context, made.display)
        val content =
            ComposeView(shown.context).apply {
                setViewTreeLifecycleOwner(holders)
                setViewTreeViewModelStoreOwner(holders)
                setViewTreeSavedStateRegistryOwner(holders)
                setContent {
                    val own = vm.skin ?: return@setContent
                    val skin = skinHere(own) ?: return@setContent
                    val chosen = vm.androidAutoStore
                    RemoteScreen(vm, skin, kept, list, sliders, fill = chosen.fill, onFill = { chosen.fill = !chosen.fill })
                }
            }
        shown.setContentView(content)
        shown.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        holders.onShown()
        if (runCatching { shown.show() }.isFailure) {
            holders.onHidden()
            made.release()
            return
        }
        display = made
        presentation = shown
        owners = holders
    }

    /**
     * The skin worn on the surface: the one chosen for Android Auto, once it has been read, or the
     * player's [own] when none is chosen or the chosen one will not load. Null while it is
     * being read.
     */
    @Composable
    private fun skinHere(own: Skin): Skin? {
        val asked = vm.androidAutoStore.worn ?: return own
        val read by produceState<Skin?>(null, asked, own) {
            value = withContext(Dispatchers.IO) { CurrentSkin.load(context, id = asked) } ?: own
        }
        return read
    }

    /** Keeps the window inside [area] of the surface, in its pixels. */
    fun keepTo(area: IntRect) {
        kept = area.takeIf { it.width > 0 && it.height > 0 }
    }

    /** A tap at ([x], [y]) on the surface. */
    fun tap(
        x: Float,
        y: Float,
    ) {
        val view = presentation?.window?.decorView ?: return
        // a tap that is still down is let go first: the window follows one finger
        finishTap()
        // asked before the tap is passed on, which may put another window up
        main.removeCallbacks(lift)
        val on = sliders.at(x, y)
        slider.tapped(on)
        keepTheScrollbar(held = on != null)
        val downAt = SystemClock.uptimeMillis()
        send(view, downAt, downAt, MotionEvent.ACTION_DOWN, x, y)
        val up =
            Runnable {
                release = null
                send(view, downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
            }
        release = up
        main.postDelayed(up, PRESS_MS)
    }

    /** The owner's finger moved by ([dx], [dy]): the slider last tapped follows it, or else the list. */
    fun drag(
        dx: Float,
        dy: Float,
    ) {
        finishTap()
        if (!slider.dragBy(dx, dy, sliders.at)) return list.dragBy(dy)
        main.removeCallbacks(lift)
        main.postDelayed(lift, LIFT_MS)
        keepTheScrollbar(held = true)
    }

    /**
     * Starts the wait after which the list's scrollbar is let go, or ends it. Only the list has
     * a slider that a drag would otherwise mean something else for.
     */
    private fun keepTheScrollbar(held: Boolean) {
        main.removeCallbacks(forget)
        if (held && list.shown) main.postDelayed(forget, SCROLLBAR_MS)
    }

    /** The owner's finger left the surface at this speed, which ends a drag and throws the list. */
    fun fling(
        @Suppress("UNUSED_PARAMETER") vx: Float,
        vy: Float,
    ) {
        if (!slider.down) return list.throwAt(vy)
        main.removeCallbacks(lift)
        slider.letGo()
    }

    /** Takes the window off the surface and lets the display go. */
    fun hide() {
        finishTap()
        main.removeCallbacks(lift)
        main.removeCallbacks(forget)
        slider.tapped(null)
        presentation?.let { runCatching { it.dismiss() } }
        owners?.onHidden()
        display?.release()
        presentation = null
        owners = null
        display = null
    }

    private fun finishTap() {
        val pending = release ?: return
        main.removeCallbacks(pending)
        pending.run()
    }

    /** A touch of the finger that moves [slider]. */
    private fun touch(
        what: HeldSlider.Touch,
        x: Float,
        y: Float,
    ) {
        val view = presentation?.window?.decorView ?: return
        val now = SystemClock.uptimeMillis()
        if (what == HeldSlider.Touch.DOWN) sliderDownAt = now
        val action =
            when (what) {
                HeldSlider.Touch.DOWN -> MotionEvent.ACTION_DOWN
                HeldSlider.Touch.MOVE -> MotionEvent.ACTION_MOVE
                HeldSlider.Touch.UP -> MotionEvent.ACTION_UP
            }
        send(view, sliderDownAt, now, action, x, y)
    }

    @Suppress("LongParameterList") // one touch event: when, what and where
    private fun send(
        view: View,
        downAt: Long,
        at: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(downAt, at, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private companion object {
        const val DISPLAY_NAME = "andamp-remote-window"

        /** How long a tap holds a button down. */
        const val PRESS_MS = 90L

        /** How long after the last move of a drag the finger on a slider is lifted. */
        const val LIFT_MS = 300L

        /** How long the list's scrollbar stays held after the last touch of it. */
        const val SCROLLBAR_MS = 3_000L
    }
}
