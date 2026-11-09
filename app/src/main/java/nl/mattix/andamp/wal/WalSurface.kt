// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.runtime.IntState
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.sync.Mutex
import nl.mattix.walkit.ClassicSwitches
import nl.mattix.walkit.RowMarks
import nl.mattix.walkit.SkinMenu
import nl.mattix.walkit.SkinOptions

/**
 * A worn modern skin as the player screen sees it: the frame to draw, where it sits, and where
 * touches, fits and menu answers go.
 *
 * Every call is made on the main thread and posts its work to the engine thread, so main never
 * waits on the skin and never reads its runtime. [frames], [layout] and [menu] change only on main.
 *
 * The activity and the floating player can each show the skin. Only the one holding [turn]
 * installs [sink], posts fits, asks for ticks, shows the skin's menus and publishes the window rect.
 */
@Suppress("TooManyFunctions") // every call the screen makes into a worn skin: touches, menus, fits, ticks and themes
internal interface WalSurface : WalTouch.Target {
    /** The seq of the down that moves the stack, as the latest [layout] says. */
    override val moveTarget: Int? get() = layout?.moveTarget

    /** Bumped on main after each new frame or menu change; changes close together count once. */
    val frames: IntState

    /** Whether the skin is a classic one (.wsz) walkit draws, rather than a modern skin. */
    val classic: Boolean get() = false

    /** Where the stack sits; snapshot state set on main, null until the first frame. */
    val layout: WalLayout?

    /** The skin's popup menu waiting for an answer, or null; snapshot state set on main. */
    val menu: SkinMenu?

    /**
     * The skin's color themes, in the order the skin's theme list shows them (case-insensitive),
     * one per name ignoring case, as the latest [layout] carries them; empty until the skin has
     * loaded. Read on main.
     */
    val themes: List<String> get() = layout?.themes.orEmpty()

    /** The color theme the latest frame wears, or null before the first frame. Read on main. */
    val theme: String? get() = layout?.theme

    /**
     * The options the skin lists for Winamp's Options menu, as the latest [layout] carries them;
     * empty until the skin has loaded. Read on main.
     */
    val skinOptions: SkinOptions get() = layout?.skinOptions ?: SkinOptions.EMPTY

    /** Held by the one player surface that owns [sink], [fit], ticks, menus and the window rect. */
    val turn: Mutex

    /** Where the listener moved the stack, upright and sideways, kept between runs. */
    val places: WalPlaces

    /** Where host actions and file requests go, or null to drop them; read and written on main only. */
    var sink: WalSink?

    /**
     * Whether the screen shows the skin now, as the surface holding [turn] tells it: false while
     * the player is covered or the screen is dark, and once that surface lets go of the turn. The
     * look keeps it and hands it to its engine, also when the engine starts after it was set; an
     * engine that is not awake sets itself no wakes. Written on main; false until a surface sets it.
     */
    var awake: Boolean

    /**
     * When the listener last touched the skin, answered its menu, closed one of its windows with
     * Back or picked one of its color themes, in [android.os.SystemClock.uptimeMillis]; 0 before
     * any. Every pointer call, [doubleTap], [rightClick], [answerMenu], [closeTopWindow] and
     * [pickTheme] sets it. Snapshot state set on main; each change gives the skin ticks on every
     * display frame for a while ([WAL_INPUT_FRAMES_MS]).
     */
    val lastInputAt: Long

    /**
     * Draws the latest frame with its top-left at [scope]'s origin, at [WalLayout.scale], keeping
     * in [layers] what it drew for the frames after (see [drawWalStackLayered]). At a scale between
     * whole numbers the frame is drawn into the stack layer off screen and shrunk to fit.
     */
    fun draw(
        scope: DrawScope,
        layers: WalLayers,
    )

    /**
     * Draws the frame [draw] drew last piece by piece at [zoom] surface pixels per skin pixel, with
     * hard edges, its top-left at [scope]'s origin: what the magnifier shows. Nothing before a frame.
     */
    fun drawMagnified(
        scope: DrawScope,
        zoom: Float,
    ) {}

    /** [draw] threw [error]; called on main. It counts toward the three failures in a row that drop the skin. */
    fun drawFailed(error: Throwable)

    /** The box the stack goes in. The first call starts the engine; a fit equal to the last does nothing. */
    fun fit(fit: WalFit)

    /**
     * Asks for one tick of the skin's timers and animations; at most one waits at a time. [cause]
     * is what the timing log names it by: a display frame, or a change the skin shows ([wakeKey]).
     * The surface holding [turn] asks on display frames only while [layout] wants frames
     * ([WalLayout.wantsFrames]) or the listener's last input is recent, at most sixty a second,
     * and on every display frame while it slides ([WalLayout.sliding]). A display frame passes
     * [showAt], when in [System.nanoTime] the frame the tick makes will reach the screen.
     */
    fun requestTick(
        cause: WalCause = WalCause.TICK,
        showAt: Long? = null,
    )

    /**
     * A value that changes when app state the skin shows changes, such as the volume, the track or
     * the library opening. Read on main in a snapshotFlow; each change earns the skin a tick.
     */
    fun wakeKey(): Any

    /**
     * A finger went down at ([x], [y]) in skin px. Returns this gesture's seq; the seqs a look hands
     * out from [pointerDown] and [doubleTap] increase.
     */
    override fun pointerDown(
        x: Int,
        y: Int,
    ): Int

    /** The finger moved to ([x], [y]) in skin px; moves close together are merged. */
    override fun pointerMove(
        x: Int,
        y: Int,
    )

    /** The finger lifted at ([x], [y]) in skin px. */
    override fun pointerUp(
        x: Int,
        y: Int,
    )

    /** The gesture ends with no click: a long press took it, or the pointer went elsewhere. */
    override fun pointerCancel()

    /** A second tap at ([x], [y]) in skin px; returns its seq, as [pointerDown] does. */
    override fun doubleTap(
        x: Int,
        y: Int,
    ): Int

    /** Right-clicks the object and point gesture [seq] went down on, for its skin menu. A stale seq does nothing. */
    fun rightClick(seq: Int)

    /**
     * The visualizer window closed: what the skin moved out into it goes back into the skin
     * (`SkinRuntime.visualizerWindowClosed`).
     */
    fun visualizerWindowClosed()

    /**
     * The listener's answer to [menu]: the picked command's [id], or 0 for Back, a tap outside or
     * the menu going away. Ignored unless [menu] is the one waiting.
     */
    fun answerMenu(
        menu: SkinMenu,
        id: Int,
    )

    /**
     * Closes the waiting menu without the listener, when the screen goes away, stops or changes
     * skin. It ends the gesture, so a later popup in the same touch answers 0.
     */
    fun dismissMenu()

    /** Posts a save of the skin's settings to the engine thread. */
    fun save()

    /**
     * Puts on the color theme [name], one of [themes], on the engine thread, and keeps it for the
     * next start, as a pick from the skin's theme list does. Does nothing before the skin has
     * loaded.
     */
    fun pickTheme(name: String) {}

    /**
     * Flips the skin option [key] on the engine thread, as a pick from Winamp's menu does: the
     * skin's scripts hear it, and it is saved like the skin's other settings. Does nothing before
     * the skin has loaded or for a key the skin no longer lists.
     */
    fun toggleSkinOption(key: SkinOptions.Key) {}

    /**
     * Opens or closes a classic skin's window of [container] (`equalizer`, `pledit`), as its main
     * window's EQ and PL buttons do; the next frame's [layout] shows it. Does nothing before the
     * skin has loaded.
     */
    fun showClassicWindow(
        container: String,
        open: Boolean,
    ) {}

    /**
     * Sets a classic skin's switches to the app's settings, now or as soon as the skin has loaded;
     * a frame after a press that changed them carries them in [layout]. Does nothing for a modern skin.
     */
    fun setClassicSwitches(switches: ClassicSwitches) {}

    /** Shows [rows] as a classic playlist's selection, now or as soon as the skin has loaded. Does nothing for a modern skin. */
    fun setClassicSelection(rows: Set<Int>) {}

    /** Lets a classic skin's windows shade or not, now or as soon as the skin has loaded. Does nothing for a modern skin. */
    fun setClassicShades(on: Boolean) {}

    /** Puts [marks] on a classic playlist's rows, now or as soon as the skin has loaded. Does nothing for a modern skin. */
    fun setClassicRowMarks(marks: RowMarks) {}

    /**
     * Shows a classic skin's playlist and equalizer windows that are in [open] (`pledit`,
     * `equalizer`) and closes the others, now or as soon as the skin has loaded. Does nothing for a
     * modern skin.
     */
    fun setClassicWindows(open: Set<String>) {}
}
