// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.LibraryAccessHandle
import nl.mattix.andamp.ui.PlayerSurface
import nl.mattix.andamp.ui.Screen
import nl.mattix.andamp.ui.playerScale
import nl.mattix.andamp.ui.window.PlaylistMenuActions
import nl.mattix.andamp.ui.window.SurfaceScreen

/**
 * The player, floating over other apps, as two windows.
 *
 * The picture is a full-screen window that is never touchable and never changes size. When a window
 * is resized, Android shows its old buffer in the new frame for a frame or two before the owner
 * redraws; a window that never resizes avoids that. The player composes against the whole display,
 * as it does in the activity.
 *
 * The touch window is invisible and empty, sized to the union of the skin windows. It hands every
 * event to the picture's view, translated by where it sits, and everything outside it falls through
 * to the app behind. A gesture stays with the window that received its first touch, so the union is
 * settled only when the finger leaves.
 *
 * A file picker, Preferences and the museum need an activity, so those bring Andamp forward. What
 * the X does is the caller's `onClose`.
 */
object PlayerOverlay {
    /** What [show]'s onOpenAppAt is handed when the player asks for the overlay permission. */
    const val OVERLAY_SETTINGS = "overlay-settings"

    /** The picture: the player, full screen, untouchable. */
    private var picture: ComposeView? = null

    /** The touch: invisible, union-sized, forwarding everything to the picture. */
    private var touch: TouchWindow? = null
    private var touchParams: WindowManager.LayoutParams? = null
    private var pictureParams: WindowManager.LayoutParams? = null

    private var owners: OverlayOwners? = null

    /** When the feeler may move and where to; this class only carries it out. */
    private var plan = TouchWindowPlan()

    val showing: Boolean get() = picture != null

    /**
     * Receives gestures on the union and forwards them to the picture's view,
     * translated by where this window sits on the screen.
     */
    private class TouchWindow(
        context: Context,
        private val to: View,
        private val onTouchChanged: (Boolean) -> Unit,
    ) : View(context) {
        private val here = IntArray(2)

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            // translated by where this window is, asked of the view itself: updateViewLayout is
            // asynchronous, so the geometry last asked for can run ahead of the window
            getLocationOnScreen(here)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouchChanged(true)
            event.offsetLocation(here[0].toFloat(), here[1].toFloat())
            val handled = to.dispatchTouchEvent(event)
            // the release is reported after the event has been delivered: a settle that moved this
            // window first would shift the UP's own coordinates by the settle
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTouchChanged(false)
                else -> Unit
            }
            return handled
        }
    }

    @Suppress("LongParameterList") // one per thing a window owned by no activity cannot do itself
    fun show(
        context: Context,
        vm: WinampViewModel,
        onOpenApp: () -> Unit,
        /** Opens the app aimed at a named screen - Preferences, the museum. */
        onOpenAppAt: (String) -> Unit,
        onClose: () -> Unit,
        onMinimize: () -> Unit,
    ) {
        if (showing) return
        val app = context.applicationContext
        val windows = app.getSystemService(WindowManager::class.java) ?: return
        // one measurement for both the window and the scale the player is drawn at
        val screen = windows.currentWindowMetrics.bounds
        val holders = OverlayOwners()
        val display = displayScreen(windows)

        val shown =
            ComposeView(app).apply {
                setViewTreeLifecycleOwner(holders)
                setViewTreeViewModelStoreOwner(holders)
                setViewTreeSavedStateRegistryOwner(holders)
                setContent {
                    val skin = vm.skin ?: return@setContent
                    // the activity's own rule, so the two surfaces draw the player the same size
                    val scale = playerScale(screen.width(), screen.height() - display.statusBar)
                    val here =
                        OverlayBounds.windowFor(
                            vm.state.windowRects.values,
                            scale = scale,
                            originX = 0,
                            originY = display.statusBar,
                        ) ?: IntRect(0, 0, 1, 1)
                    val modal = vm.state.modalShowing
                    // the preference itself, not the whole gate: the gate also says "not now" while
                    // the activity is in front, and that is for the activity to act on
                    val stillWanted = vm.overlayOps.gate.wanted
                    // the touch window follows the windows; the picture never
                    // moves - and while a modal is up the roles swap, see setModal
                    SideEffect {
                        // the clutter bar's A can switch the floating window off from inside the
                        // floating window, so the window follows the preference. Posted, because
                        // this tears down the composition it is running in.
                        if (!stillWanted) {
                            picture?.post { hide() }
                            return@SideEffect
                        }
                        setModal(windows, modal)
                        resize(windows, here)
                    }
                    Player(vm, skin, display, onOpenApp, onOpenAppAt, onClose, onMinimize)
                }
            }

        val feeler =
            TouchWindow(
                app,
                to = shown,
                // the release is what lets a deferred move happen
                onTouchChanged = { down -> run(windows, plan.touching(down)) },
            )

        holders.onShown()
        val forPicture =
            // The picture: the whole display, untouchable. Measured from the window manager:
            // `resources.displayMetrics` of an application context is the area an app gets, which
            // on some devices is the display less the system bars.
            overlayLayout(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply {
                width = screen.width()
                height = screen.height()
            }
        val pictureUp = runCatching { windows.addView(shown, forPicture) }.isSuccess
        if (pictureUp) pictureParams = forPicture
        val touchUp =
            pictureUp &&
                runCatching {
                    windows.addView(feeler, overlayLayout(0).also { touchParams = it })
                }.isSuccess
        if (!touchUp) {
            // permission withdrawn between the check and here, or no window manager: the player
            // stays where it was
            if (pictureUp) runCatching { windows.removeView(shown) }
            holders.onHidden()
            touchParams = null
            return
        }
        picture = shown
        touch = feeler
        owners = holders
    }

    fun hide() {
        val shown = picture ?: return
        val app = shown.context.applicationContext
        val windows = app.getSystemService(WindowManager::class.java)
        runCatching { touch?.let { windows?.removeView(it) } }
        runCatching { windows?.removeView(shown) }
        owners?.onHidden()
        picture = null
        touch = null
        owners = null
        touchParams = null
        pictureParams = null
        plan = TouchWindowPlan()
    }

    /** Every flag both windows need: absolute placement, nothing fitted, nothing focused. */
    private fun overlayLayout(extraFlags: Int): WindowManager.LayoutParams =
        WindowManager
            .LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE: the keyboard and the back gesture keep belonging to
                // whatever app is in front. LAYOUT_NO_LIMITS and the cutout mode:
                // without them the window manager pushes a window out from under the
                // display cutout. fitInsetsTypes = 0: a window fits itself around
                // the system bars by default, which deforms every frame it is asked
                // for - these windows are placed absolutely from display-level
                // measurements, and must fit nothing.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    extraFlags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                }
            }

    /**
     * The display's insets, measured from outside the composition. Inside it they would describe a
     * window, and the touch window moves with the windows drawn in the picture, so insets read
     * there would feed back into its own position.
     */
    private fun displayScreen(windows: WindowManager): SurfaceScreen {
        val insets = windows.currentWindowMetrics.windowInsets
        val bars =
            insets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type
                    .statusBars() or
                    android.view.WindowInsets.Type
                        .displayCutout(),
            )
        val gestures =
            insets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type
                    .systemGestures(),
            )
        return SurfaceScreen(
            statusBar = bars.top,
            shadeStrip = 0, // the picture starts at the display's own top
            bottom = gestures.bottom,
        )
    }

    /**
     * A modal (a menu, a prompt, a picker) swaps the two windows' roles.
     *
     * A menu is a Compose popup: a window of its own, layered under the touch window, whose content
     * the picture's view knows nothing about, so forwarded touches cannot reach it. While a modal
     * is up the picture becomes touchable itself, so the popup and tap-outside-to-close work the
     * ordinary way, and the touch window is parked. This is a flag change: the picture keeps its
     * size.
     */
    private fun setModal(
        windows: WindowManager,
        on: Boolean,
    ) = run(windows, plan.modal(on))

    private fun resize(
        windows: WindowManager,
        to: IntRect,
    ) = run(windows, plan.wants(to))

    /** Carries out the plan's commands. */
    private fun run(
        windows: WindowManager,
        commands: List<TouchWindowPlan.Command>,
    ) {
        commands.forEach { command ->
            when (command) {
                is TouchWindowPlan.Command.Move -> moveFeeler(windows, command.to)
                is TouchWindowPlan.Command.PictureTakesTouches -> pictureTakesTouches(windows, command.on)
            }
        }
    }

    /** Sets or clears the picture's FLAG_NOT_TOUCHABLE. The picture keeps its size. */
    private fun pictureTakesTouches(
        windows: WindowManager,
        on: Boolean,
    ) {
        val shown = picture ?: return
        val forPicture = pictureParams ?: return
        forPicture.flags =
            if (on) {
                forPicture.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                forPicture.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        runCatching { windows.updateViewLayout(shown, forPicture) }
    }

    private fun moveFeeler(
        windows: WindowManager,
        to: IntRect,
    ) {
        val layout = touchParams ?: return
        val feeler = touch ?: return
        applyTo(windows, layout, feeler, to)
    }

    private fun applyTo(
        windows: WindowManager,
        layout: WindowManager.LayoutParams,
        feeler: TouchWindow,
        to: IntRect,
    ) {
        layout.x = to.left
        layout.y = to.top
        layout.width = to.width
        layout.height = to.height
        runCatching { windows.updateViewLayout(feeler, layout) }
    }
}

/**
 * The player as the floating window shows it: everything it cannot do itself
 * routed back to the app that owns an activity.
 */
@Composable
@Suppress("LongParameterList") // one per thing a window owned by no activity cannot do
private fun Player(
    vm: WinampViewModel,
    skin: nl.mattix.andamp.skin.Skin,
    display: SurfaceScreen,
    onOpenApp: () -> Unit,
    onOpenAppAt: (String) -> Unit,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
) {
    PlayerSurface(
        vm,
        skin,
        // every one of these needs an activity's result registry,
        // which a window owned by no activity has none of; they
        // open the app instead
        playlistActions =
            PlaylistMenuActions(
                addFile = onOpenApp,
                addFileThenPlay = onOpenApp,
                addDir = onOpenApp,
                playFile = onOpenApp,
                playDir = onOpenApp,
                saveList = onOpenApp,
                loadList = onOpenApp,
            ),
        libraryAccess =
            LibraryAccessHandle(
                // asking is an activity's job, so from here it is
                // granted or it is a reason to open the app
                status =
                    if (vm.playlistFiles.canReadLibrary()) {
                        LibraryAccess.GRANTED
                    } else {
                        LibraryAccess.ASKABLE
                    },
                request = onOpenApp,
            ),
        onPickSkin = onOpenApp,
        onPreferences = { onOpenAppAt(Screen.PREFERENCES) },
        onMuseum = { onOpenAppAt(Screen.MUSEUM) },
        onExit = onClose,
        onMinimize = onMinimize,
        // the same rule as opening the app: an activity may be
        // started while this window is still showing, and the
        // system's own screen is an activity like any other
        onOverlaySettings = { onOpenAppAt(PlayerOverlay.OVERLAY_SETTINGS) },
        // switched off from the floating player: it is about to take itself down, so the app is
        // opened
        onFloatingChanged = { on -> if (!on) onOpenApp() },
        surfaceScreen = display,
        // over other apps the windows float: Double Size switched on here ends the floating
        // player, and the app that opens fills the screen
        fillsScreen = false,
    )
}
