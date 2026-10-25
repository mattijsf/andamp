// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.sync.Mutex
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState
import kotlin.math.floor

/**
 * A worn modern skin with no engine behind it: it records what the screen asks of it, and a test
 * sets what the engine would publish (the layout, a popup menu, a new frame).
 */
internal class FakeWalSurface : WalSurface {
    override val frames = mutableIntStateOf(0)
    override var layout: WalLayout? by mutableStateOf(null)
    override var menu: SkinMenu? by mutableStateOf(null)
    override val turn = Mutex()
    override var sink: WalSink? = null

    /** What the screen said last; every value it sets is in [awakes]. */
    override var awake = false
        set(value) {
            field = value
            awakes += value
        }

    /**
     * Stamped on each of the listener's calls, as a look stamps it, with a count rather than a time:
     * a new value on every call. A test may set it too.
     */
    override var lastInputAt by mutableLongStateOf(0L)

    /** The pointer calls, the fits, the saves and the menu answers, in order, as short lines. */
    val calls = mutableListOf<String>()
    val fits = mutableListOf<WalFit>()
    val answers = mutableListOf<Pair<SkinMenu, Int>>()
    val rightClicks = mutableListOf<Int>()
    val drawFailures = mutableListOf<Throwable>()

    /** Each value [awake] was set to, in order. */
    val awakes = mutableListOf<Boolean>()

    /** What each tick asked for was named by, in order. */
    val tickCauses = mutableListOf<WalCause>()
    var ticks = 0
    var draws = 0
    var dismissals = 0
    var saves = 0
    var closedWindows = 0

    /** What every draw throws, or null to draw. */
    var drawThrows: Throwable? = null

    /** Lays the stack out for every fit posted, as the engine publishes a frame for it. */
    var layoutFor: ((WalFit) -> WalLayout)? = null

    /** Names each press as one whose object has a skin menu, as the engine's lookup does. */
    var menuUnderEveryPress = false

    /** Names each press, though no double tap, as one on a move area, as the engine's lookup does. */
    var moveAreaUnderEveryPress = false

    /** Each place kept, with whether it was sideways, in order. */
    val kept = mutableListOf<Pair<Boolean, IntOffset?>>()

    override val places = WalPlaces { sideways, move -> kept += sideways to move }

    /** What the skin does once a menu is answered, such as pop another in the same touch. */
    var afterAnswer: ((SkinMenu, Int) -> Unit)? = null

    /** A value the skin shows, such as the volume; it is what [wakeKey] reads. */
    var shown by mutableIntStateOf(0)

    private var seq = 0

    /** The pointer calls alone, in order. */
    val pointerCalls: List<String> get() = calls.filter { it.split(' ').first() in POINTER }

    override fun draw(
        scope: DrawScope,
        layer: GraphicsLayer,
    ) {
        draws++
        drawThrows?.let { throw it }
        scope.drawRect(Color.Magenta)
    }

    override fun drawFailed(error: Throwable) {
        drawFailures += error
    }

    override fun fit(fit: WalFit) {
        fits += fit
        calls += "fit"
        layoutFor?.let {
            layout = it(fit)
            frames.intValue++
        }
    }

    override fun requestTick(cause: WalCause) {
        ticks++
        tickCauses += cause
    }

    override fun wakeKey(): Any = shown

    override fun pointerDown(
        x: Int,
        y: Int,
    ): Int = press("down $x,$y")

    override fun pointerMove(
        x: Int,
        y: Int,
    ) {
        input()
        calls += "move $x,$y"
    }

    override fun pointerUp(
        x: Int,
        y: Int,
    ) {
        input()
        calls += "up $x,$y"
    }

    override fun pointerCancel() {
        input()
        calls += "cancel"
    }

    override fun doubleTap(
        x: Int,
        y: Int,
    ): Int = press("doubleTap $x,$y")

    private fun press(call: String): Int {
        input()
        calls += call
        seq++
        if (menuUnderEveryPress) layout = layout?.copy(menuTarget = seq)
        // the engine names no double tap as a move
        if (moveAreaUnderEveryPress) layout = layout?.copy(moveTarget = seq.takeIf { call.startsWith("down") })
        return seq
    }

    override fun rightClick(seq: Int) {
        input()
        calls += "rightClick $seq"
        rightClicks += seq
    }

    override fun closeTopWindow() {
        input()
        closedWindows++
    }

    override fun answerMenu(
        menu: SkinMenu,
        id: Int,
    ) {
        input()
        calls += "answer $id"
        answers += menu to id
        if (this.menu === menu) {
            this.menu = null
            afterAnswer?.invoke(menu, id)
        }
    }

    override fun dismissMenu() {
        calls += "dismiss"
        dismissals++
        menu = null
    }

    override fun save() {
        calls += "save"
        saves++
    }

    private fun input() {
        lastInputAt++
    }

    private companion object {
        val POINTER = setOf("down", "move", "up", "cancel", "doubleTap")
    }
}

/** A stack [stackW] by [stackH] skin pixels at [scale], centered across the top of whatever box it is fitted to. */
internal fun stackOf(
    stackW: Int,
    stackH: Int,
    scale: Float,
): (WalFit) -> WalLayout =
    { fit ->
        WalLayout(
            fit = fit,
            scale = scale,
            left = floor((fit.widthPx - stackW * scale) / 2f),
            top = 0f,
            stackW = stackW,
            stackH = stackH,
            windowOpen = false,
            animating = false,
            menuTarget = null,
        )
    }

/** A popup menu of one command per label, numbered from 1 as the sampled scripts number theirs. */
internal fun skinMenuOf(vararg labels: String) =
    SkinMenu(labels.mapIndexed { i, label -> SkinMenu.Command(i + 1, label, checked = false, disabled = false) })

/**
 * The parts a player surface hands [surface]'s layer, with a main menu that holds "Skin menu" (when
 * given its action) and one other entry, and records what the skin's buttons asked for in [asked].
 */
internal fun partsFor(
    surface: WalSurface,
    state: WinampState,
    asked: MutableList<String> = mutableListOf(),
    covered: () -> Boolean = { state.playerCovered && !state.screenPulled },
): WalLayerParts =
    WalLayerParts(
        surface,
        WalHostActions(
            openMenu = { asked += "menu" },
            minimize = { asked += "minimize" },
            toggleLibrary = { asked += "library" },
            toggleVisualizer = { asked += "visualizer" },
            visualizationMenu = { asked += "visualization menu" },
            fullscreenVisualizer = { asked += "fullscreen" },
            nextPreset = { asked += "next preset" },
            previousPreset = { asked += "previous preset" },
            pickFile = { andPlay -> asked += if (andPlay) "file to play" else "file" },
            fileInfo = { asked += "file info" },
        ),
        covered = covered,
        openMenu = { onSkinMenu: (() -> Unit)?, anchor: MenuAnchor? ->
            state.activeMenu =
                AmpMenu(
                    MAIN_MENU,
                    listOfNotNull(
                        onSkinMenu?.let { AmpMenuItem.Action(SKIN_MENU, onClick = it) },
                        AmpMenuItem.Action(PREFERENCES) {},
                    ),
                    anchor,
                )
        },
        menuTitle = SKIN_MENU,
    )

internal const val MAIN_MENU = "Winamp"
internal const val SKIN_MENU = "Skin menu"
internal const val PREFERENCES = "Preferences..."

/** A lifecycle a test moves by hand, as two player surfaces each have one. */
internal class TestOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)

    override val lifecycle: Lifecycle get() = registry
}
