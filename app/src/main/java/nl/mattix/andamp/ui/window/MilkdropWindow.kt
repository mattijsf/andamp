// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.PresetImport
import nl.mattix.andamp.state.TapPcmSource
import nl.mattix.andamp.state.VisPlugin
import nl.mattix.andamp.state.VisualCommands
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.SkinCut
import nl.mattix.andamp.ui.WhileOnScreen
import nl.mattix.andamp.ui.menu.visualMenu
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import nl.mattix.andamp.visualizer.avs.AvsView
import nl.mattix.andamp.visualizer.core.VisualizerView
import nl.mattix.andamp.visualizer.projectm.ProjectMView
import java.io.File

/** The plug-in window's narrowest width, in virtual pixels. */
const val MILKDROP_W = 275

/** webamp's WINDOW_RESIZE_SEGMENT_WIDTH; the plug-in window uses the same one. */
const val MILKDROP_WIDTH_STEP = WidthSteps.WIDTH_STEP

/** How the plug-in window widens. */
val MILKDROP_WIDTH_STEPS = WidthSteps(MILKDROP_W, MILKDROP_WIDTH_STEP)

/** The window's width for a width index. */
fun milkdropWidth(cols: Int) = MILKDROP_WIDTH_STEPS.widthOf(cols)

/** The visual's default height; the window adds the frame's chrome. */
const val MILKDROP_CONTENT_H = 120

/**
 * The height step of a resize, in virtual pixels. The visual has no natural step; this one
 * keeps a live drag from jittering by single pixels.
 */
const val MILKDROP_STEP = 8

/** The smallest height of the visual, in steps. */
const val MILKDROP_MIN_STEPS = 5

internal const val MILKDROP_DEFAULT_STEPS = MILKDROP_CONTENT_H / MILKDROP_STEP

/** The visual's height for a remembered [steps], or the default while it is null. */
fun milkdropContentH(steps: Int?) = (steps ?: MILKDROP_DEFAULT_STEPS) * MILKDROP_STEP

/** Window height for [frame]'s chrome around a visual of [contentH]. */
fun milkdropHeight(
    frame: WindowFrame,
    contentH: Int = MILKDROP_CONTENT_H,
) = frame.chromeH + contentH

const val MILKDROP_TITLE = "Milkdrop"

/**
 * The window's title: the name of the plug-in running in it, or the progress or failure of
 * a preset import while there is one.
 */
fun milkdropTitle(
    state: WinampState,
    importing: PresetImport? = null,
): String =
    when {
        importing?.failed == true -> "${importing.name}: not presets"
        importing != null -> "Importing ${importing.name}... ${importing.filesWritten}"
        else -> state.visPlugin.menuLabel
    }

/** The close button, at the top right of the skin's frame. */
fun milkdropWidgets(
    vm: WinampViewModel,
    frame: WindowFrame,
    width: Int = MILKDROP_W,
): List<Widget> =
    listOf(
        button(
            "milkdrop.close",
            frame.closeX(width),
            frame.closeY(),
            GenWindow.CLOSE_W,
            GenWindow.CLOSE_W,
        ) { vm.presetOps.setWindowOpen(false) },
    )

/**
 * The visualizer plug-in window: the skin's window frame with the visualizer surface inset in
 * it. The visual is drawn by the engine's own view, not on the skin canvas.
 *
 * A tap moves to the next preset, a double tap toggles fullscreen and a long press opens the
 * plug-in's menu; see [MilkdropSurface].
 *
 * With [pinned] the layout holds it: the grip changes only its height in steps, up to
 * [visMaxSteps], and the height it floats at is kept as it was.
 */
@Composable
@Suppress("LongParameterList") // a window: its player, its skin, its scale and its place
fun MilkdropWindow(
    vm: WinampViewModel,
    skin: Skin,
    scale: Int,
    /** Its top-left corner until it is moved; see [FloatingSkinWindow]. */
    docked: IntOffset,
    modifier: Modifier = Modifier,
    /** The rectangle the layout holds it in, or null while it floats; see [FloatingSkinWindow]. */
    pinned: IntRect? = null,
    /** The most steps the visual may take while the layout holds it. */
    visMaxSteps: Int = MILKDROP_MIN_STEPS,
) {
    val s = vm.state
    val density = LocalDensity.current
    val frame = frameFor(skin)
    val contentH = pinned?.let { it.height - frame.chromeH } ?: milkdropContentH(s.milkdropSteps)
    val height = milkdropHeight(frame, contentH)
    BoxWithConstraints(modifier) {
        val screen = windowScreen(scale, s)
        // the width is limited to the screen's
        val cols = MILKDROP_WIDTH_STEPS.clamp(s.milkdropCols, screen.width)
        val width = pinned?.width ?: milkdropWidth(cols)
        val widgets = remember(vm, frame, width) { milkdropWidgets(vm, frame, width) }
        FloatingSkinWindow(
            id = WindowStore.MILKDROP,
            state = s,
            scale = scale,
            width = width,
            height = height,
            offset = s.milkdropOffset,
            docked = docked,
            onMove = { s.milkdropOffset = it },
            onResizeRaw = { grab ->
                if (pinned != null) {
                    // held, the grip changes the height in the stack and nothing else
                    s.milkdropSteps =
                        WindowSizing.stepsForHeight(
                            rawHeight = grab.rawHeight,
                            furnitureH = frame.chromeH,
                            stepPx = MILKDROP_STEP,
                            min = MILKDROP_MIN_STEPS,
                            max = visMaxSteps,
                            current = (pinned.height - frame.chromeH) / MILKDROP_STEP,
                        )
                    return@FloatingSkinWindow
                }
                // the height is capped at what fits between this window's top edge and the
                // safe bottom
                val topAtGrab = screen.topLeftOf(grab.atOffset, IntSize(grab.atWidth, grab.atHeight)).y
                val room = screen.safeBottom - topAtGrab
                val resized =
                    WindowSizing.resize(
                        grab,
                        screen,
                        heightAxis =
                            SizeAxis(
                                furniture = frame.chromeH,
                                step = MILKDROP_STEP,
                                min = MILKDROP_MIN_STEPS,
                                max = (room - frame.chromeH) / MILKDROP_STEP,
                                current = s.milkdropSteps ?: MILKDROP_DEFAULT_STEPS,
                            ),
                        heightOf = { steps -> milkdropHeight(frame, steps * MILKDROP_STEP) },
                        widthAxis = MILKDROP_WIDTH_STEPS.axis(cols, screen.width),
                        widthOf = ::milkdropWidth,
                    )
                s.milkdropCols = resized.cols
                s.milkdropSteps = resized.steps
                s.milkdropOffset = resized.offset
            },
            titleH = frame.titleH,
            cut = SkinCut(skin),
            pinned = pinned,
            // its grip still changes its height in the stack
            pinnedGrip = true,
            widgets = widgets,
            modifier = Modifier.matchParentSize(),
            overlay = {
                MilkdropSurface(
                    vm,
                    Modifier
                        .offset { IntOffset(frame.leftW * scale, frame.titleH * scale) }
                        .size(
                            with(density) { ((width - frame.chromeW) * scale).toDp() },
                            with(density) { (contentH * scale).toDp() },
                        ),
                )
            },
        ) {
            with(frame) {
                draw(skin, width, height, milkdropTitle(s, vm.presetOps.importing), s.pressedWidget == "milkdrop.close")
            }
        }
    }
}

/**
 * The bare visualizer surface, also used fullscreen without chrome.
 *
 * Which engine's view fills it follows [WinampState.visPlugin]; switching plug-ins swaps the
 * view through the `key`. The gestures are the same for every engine.
 */
@Composable
fun MilkdropSurface(
    vm: WinampViewModel,
    modifier: Modifier = Modifier,
    /**
     * In the plug-in window: tap for the next preset, double tap for fullscreen, long press for
     * the menu, and the line about a preset's problems. Off on the preset selector's preview,
     * which only takes a tap and shows those problems below itself.
     */
    inWindow: Boolean = true,
) {
    val s: WinampState = vm.state
    // Black under the engine's view: a TextureView draws nothing until its first frame,
    // and the window is translucent, so the wallpaper would show through until then.
    Box(modifier.background(Color.Black)) {
        key(s.visPlugin) {
            when (s.visPlugin) {
                VisPlugin.Avs -> AvsEngineView(vm)
                VisPlugin.Milkdrop -> MilkdropEngineView(vm)
            }
        }
        if (inWindow) PresetNoticeLine(s, Modifier.align(Alignment.BottomStart))
        // The gestures are on a layer above the engine's view: a pointerInput on the
        // AndroidView itself does not receive them.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(inWindow) {
                    if (inWindow) {
                        detectTapGestures(
                            onTap = { s.visualCommands?.nextPreset() },
                            onDoubleTap = { s.milkdropFullscreen = !s.milkdropFullscreen },
                            onLongPress = { s.activeMenu = visualMenu(vm) },
                        )
                    } else {
                        detectTapGestures(onTap = { s.visualCommands?.nextPreset() })
                    }
                },
        )
    }
}

@Composable
private fun MilkdropEngineView(vm: WinampViewModel) {
    val s: WinampState = vm.state
    EngineSurface(
        vm = vm,
        factory = { context ->
            ProjectMView(context).apply {
                pcmSourceFactory = { fps, maxSamples -> TapPcmSource(fps, maxSamples) { vm.audioTap } }
            }
        },
        update = { view ->
            // the view applies the pack and the shuffle flag on its own thread
            view.presets = vm.presetOps.active
            view.shuffle = s.presetShuffle
        },
    )
}

/** The AVS engine's view. */
@Composable
private fun AvsEngineView(vm: WinampViewModel) {
    val s: WinampState = vm.state
    EngineSurface(
        vm = vm,
        factory = { context ->
            AvsView(context).apply {
                pcmSourceFactory = { fps -> TapPcmSource(fps, AvsView.MAX_PCM_SAMPLES) { vm.audioTap } }
            }
        },
        update = { view ->
            view.presetDirectory = vm.presetOps.activeAvsDir
            view.presetFiles = vm.presetOps.activeAvsFiles
            view.presetRoots = vm.presetOps.avsRoots
            view.shuffle = s.presetShuffle
        },
    )
}

/**
 * Hosts any [VisualizerView]: the lifecycle gate, the published [VisualCommands] and the
 * preset-name announcement are the same for every engine. The factory adds what only its
 * engine has.
 */
@Composable
private fun <V : VisualizerView<*>> EngineSurface(
    vm: WinampViewModel,
    factory: (android.content.Context) -> V,
    update: (V) -> Unit,
) {
    val s: WinampState = vm.state
    // The preset that was playing before this view was built, read before the new engine
    // announces one. A new engine starts at the top of its playlist, and handing the player
    // between the activity and the floating window creates a new engine.
    val resumeTo = remember { s.presetName }
    val resumed =
        remember {
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        }
    // the view is told directly: a stopped activity may not apply another recomposition
    val surface = remember { mutableStateOf<V?>(null) }
    WhileOnScreen { onScreen -> surface.value?.rendering = onScreen }
    // the view's commands are published while it exists, so a menu item can reach it
    // without holding the view
    DisposableEffect(surface.value) {
        val view = surface.value
        val commands =
            view?.let {
                object : VisualCommands {
                    override fun nextPreset() = it.nextPreset()

                    override fun previousPreset() = it.previousPreset()

                    override fun goToPreset(index: Int) = it.goToPreset(index)
                }
            }
        s.visualCommands = commands
        // two views overlap while the preset selector's preview opens over the window; the one
        // leaving last must not take the other's commands along
        onDispose { if (s.visualCommands === commands) s.visualCommands = null }
    }
    AndroidView(
        factory = { context ->
            factory(context)
                .apply {
                    onPresetChanged = { name -> s.presetName = name }
                    onPresetGaps = { path, gaps ->
                        vm.presetOps.record(path, gaps)
                        // projectM names presets it tried and skipped as well; those are not showing
                        if (!(s.visPlugin == VisPlugin.Milkdrop && gaps.unreadable)) {
                            s.presetPath = path
                            s.presetGaps = gaps
                        }
                    }
                    onPlaylistChanged = { paths ->
                        val names = paths.map { File(it).nameWithoutExtension }
                        s.presetPaths = paths
                        s.presetNames = names
                        val wanted = s.presetWanted?.let { paths.indexOf(it) }?.takeIf { it >= 0 }
                        if (wanted != null) {
                            // a preset picked from a list that was not playing yet
                            s.presetWanted = null
                            resumed.set(true)
                            goToPreset(wanted)
                        } else if (resumed.compareAndSet(false, true)) {
                            names.indexOf(resumeTo).takeIf { it >= 0 }?.let { goToPreset(it) }
                        }
                    }
                }.also { surface.value = it }
        },
        update = update,
        modifier = Modifier.fillMaxSize(),
    )
}
