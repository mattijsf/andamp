// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.viewinterop.AndroidView
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.PresetImport
import nl.mattix.andamp.state.TapPcmSource
import nl.mattix.andamp.state.VisPlugin
import nl.mattix.andamp.state.VisualCommands
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
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
const val MILKDROP_WIDTH_STEP = 25

/** The window's width for a width index. */
fun milkdropWidth(cols: Int) = MILKDROP_W + cols.coerceAtLeast(0) * MILKDROP_WIDTH_STEP

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
 * With [pinned] the layout holds it: the grip changes only its height, and the height it
 * floats at is kept as it was.
 */
@Composable
fun MilkdropWindow(
    vm: WinampViewModel,
    skin: Skin,
    scale: Int,
    /** Where it sits until the listener drags it. */
    defaultOffset: IntOffset,
    modifier: Modifier = Modifier,
    pinned: PinnedVisual? = null,
) {
    val s = vm.state
    val density = LocalDensity.current
    val frame = frameFor(skin)
    val contentH = pinned?.let { it.rect.height - frame.chromeH } ?: milkdropContentH(s.milkdropSteps)
    val height = milkdropHeight(frame, contentH)
    // the width is limited to the screen's
    val maxCols = ((s.screenW - MILKDROP_W) / MILKDROP_WIDTH_STEP).coerceAtLeast(0)
    val cols = s.milkdropCols.coerceIn(0, maxCols)
    val width = pinned?.rect?.width ?: milkdropWidth(cols)
    val widgets = remember(vm, frame, width) { milkdropWidgets(vm, frame, width) }
    FloatingSkinWindow(
        id = "milkdrop",
        state = s,
        scale = scale,
        width = width,
        height = height,
        offset = s.milkdropOffset,
        defaultOffset = defaultOffset,
        onMove = { s.milkdropOffset = it },
        onResizeRaw = { grab ->
            if (pinned != null) {
                pinned.ask(grab, frame.chromeH)
                return@FloatingSkinWindow
            }
            // the height is capped at what fits between this window's top edge and the
            // safe bottom
            val topAtGrab = (s.screenH - grab.atHeight) / 2 + grab.atOffset.y
            val room = (s.safeBottom.takeIf { it > 0 } ?: s.screenH) - topAtGrab
            val resized =
                WindowSizing.resize(
                    grab,
                    screenW = s.screenW,
                    screenH = s.screenH,
                    heightAxis =
                        SizeAxis(
                            furniture = frame.chromeH,
                            step = MILKDROP_STEP,
                            min = MILKDROP_MIN_STEPS,
                            max = (room - frame.chromeH) / MILKDROP_STEP,
                            current = s.milkdropSteps ?: MILKDROP_DEFAULT_STEPS,
                        ),
                    heightOf = { steps -> milkdropHeight(frame, steps * MILKDROP_STEP) },
                    widthAxis =
                        SizeAxis(
                            furniture = MILKDROP_W,
                            step = MILKDROP_WIDTH_STEP,
                            min = 0,
                            max = maxCols,
                            current = cols,
                        ),
                    widthOf = ::milkdropWidth,
                )
            s.milkdropCols = resized.cols
            s.milkdropSteps = resized.steps
            s.milkdropOffset = resized.offset
        },
        titleH = frame.titleH,
        cut = SkinCut(skin),
        pinnedAt = pinned?.rect?.topLeft,
        widgets = widgets,
        modifier = modifier,
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

/**
 * The plug-in window while the layout holds it: the rectangle it is drawn in, how many steps
 * tall the grip may make its visual, and who is told the height the grip asks for.
 */
class PinnedVisual(
    val rect: IntRect,
    val maxSteps: Int,
    val onSteps: (Int) -> Unit,
) {
    /** Passes on the height a grip drag asks for, in steps, for a frame [chromeH] tall around the visual. */
    fun ask(
        grab: WindowGrab,
        chromeH: Int,
    ) = onSteps(
        WindowSizing.stepsForHeight(
            rawHeight = grab.rawHeight,
            furnitureH = chromeH,
            stepPx = MILKDROP_STEP,
            min = MILKDROP_MIN_STEPS,
            max = maxSteps,
            current = (rect.height - chromeH) / MILKDROP_STEP,
        ),
    )
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
) {
    val s: WinampState = vm.state
    Box(modifier) {
        key(s.visPlugin) {
            when (s.visPlugin) {
                VisPlugin.Avs -> AvsEngineView(vm)
                VisPlugin.Milkdrop -> MilkdropEngineView(vm)
            }
        }
        // The gestures are on a layer above the engine's view: a pointerInput on the
        // AndroidView itself does not receive them.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { s.visualCommands?.nextPreset() },
                        onDoubleTap = { s.milkdropFullscreen = !s.milkdropFullscreen },
                        onLongPress = { s.activeMenu = visualMenu(vm) },
                    )
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
                onPlaylistChanged = { paths -> s.presetNames = paths.map { File(it).nameWithoutExtension } }
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
                onPlaylistChanged = { names -> s.presetNames = names }
            }
        },
        update = { view ->
            view.presetDirectory = vm.presetOps.activeAvsDir
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
        s.visualCommands =
            view?.let {
                object : VisualCommands {
                    override fun nextPreset() = it.nextPreset()

                    override fun previousPreset() = it.previousPreset()

                    override fun goToPreset(index: Int) = it.goToPreset(index)
                }
            }
        onDispose { s.visualCommands = null }
    }
    AndroidView(
        factory = { context ->
            factory(context)
                .apply {
                    onPresetChanged = { name -> s.presetName = name }
                    // the engine's own listener runs first, so the list is in state before
                    // the resume
                    val engines = onPlaylistChanged
                    onPlaylistChanged = { names ->
                        engines?.invoke(names)
                        if (resumed.compareAndSet(false, true)) {
                            names.indexOf(resumeTo).takeIf { it >= 0 }?.let { goToPreset(it) }
                        }
                    }
                }.also { surface.value = it }
        },
        update = update,
        modifier = Modifier.fillMaxSize(),
    )
}
