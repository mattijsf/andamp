// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import nl.mattix.andamp.state.TapPcmSource
import nl.mattix.andamp.state.VisPlugin
import nl.mattix.andamp.state.VisualCommands
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.WhileOnScreen
import nl.mattix.andamp.visualizer.avs.AvsView
import nl.mattix.andamp.visualizer.core.VisualizerView
import nl.mattix.andamp.visualizer.projectm.ProjectMView
import java.io.File

@Composable
internal fun MilkdropEngineView(
    vm: WinampViewModel,
    leads: Boolean,
    canRest: Boolean,
) {
    val s: WinampState = vm.state
    EngineSurface(
        vm = vm,
        leads = leads,
        canRest = canRest,
        factory = { context ->
            ProjectMView(context).apply {
                pcmSourceFactory = { fps, maxSamples -> TapPcmSource(fps, maxSamples) { vm.audioTap } }
            }
        },
        update = { view ->
            // the view applies the pack and the shuffle flag on its own thread
            view.presets = vm.presetOps.active
            view.shuffle = s.presetShuffle
            view.skipped = vm.presetOps.skipped
        },
    )
}

/** The AVS engine's view. */
@Composable
internal fun AvsEngineView(
    vm: WinampViewModel,
    leads: Boolean,
    canRest: Boolean,
) {
    val s: WinampState = vm.state
    EngineSurface(
        vm = vm,
        leads = leads,
        canRest = canRest,
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
            view.skipped = vm.presetOps.skipped
        },
    )
}

/**
 * Hosts any [VisualizerView]: the lifecycle gate, the published [VisualCommands] and the
 * preset-name announcement are the same for every engine. The factory adds what only its
 * engine has.
 *
 * Only a view that [leads] publishes its commands and says what it shows. One that does not
 * follows the leader to the preset showing.
 */
@Composable
private fun <V : VisualizerView<*>> EngineSurface(
    vm: WinampViewModel,
    leads: Boolean,
    /** The window's view rests while a screen covers the player, and wakes when it shows again. */
    canRest: Boolean,
    factory: (android.content.Context) -> V,
    update: (V) -> Unit,
) {
    val leading by rememberUpdatedState(leads)
    // this view's own list, which a following view finds the leader's preset in
    var ownPaths by remember { mutableStateOf<List<String>>(emptyList()) }
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
    val rests = canRest && s.playerCovered && !s.screenPulled

    LeadOrFollow(s, surface.value, leads, ownPaths)
    AndroidView(
        factory = { context ->
            factory(context)
                .apply {
                    onPresetChanged = { name -> if (leading) s.presetName = name }
                    onPresetGaps = { path, gaps ->
                        // projectM names presets it tried and skipped as well; those are not showing
                        if (leading) {
                            vm.presetOps.record(path, gaps)
                            if (!(s.visPlugin == VisPlugin.Milkdrop && gaps.unreadable)) {
                                s.presetPath = path
                                s.presetGaps = gaps
                            }
                        }
                    }
                    onPlaylistChanged = { paths ->
                        ownPaths = paths
                        if (!leading) {
                            // it follows; the leader already went back to where the visual was
                            resumed.set(true)
                        } else {
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
                    }
                }.also { surface.value = it }
        },
        update = { view ->
            update(view)
            view.resting = rests
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * A leading view's commands are published while it exists, so a menu item can reach it
 * without holding the view; a following view goes where the leader went.
 */
@Composable
private fun LeadOrFollow(
    s: WinampState,
    target: VisualizerView<*>?,
    leads: Boolean,
    ownPaths: List<String>,
) {
    DisposableEffect(target, leads) {
        val view = target?.takeIf { leads }
        val commands =
            view?.let {
                object : VisualCommands {
                    override fun nextPreset() = it.nextPreset()

                    override fun previousPreset() = it.previousPreset()

                    override fun goToPreset(index: Int) = it.goToPreset(index)
                }
            }
        // two views overlap while the preset selector's preview is up; the one leaving last
        // must not take the other's commands along
        if (commands != null) s.visualCommands = commands
        onDispose { if (s.visualCommands === commands) s.visualCommands = null }
    }
    // a following view goes where the leader went, once it has the list to find it in
    LaunchedEffect(leads, s.presetPath, ownPaths) {
        if (leads) return@LaunchedEffect
        val index = ownPaths.indexOf(s.presetPath ?: return@LaunchedEffect)
        if (index >= 0) target?.goToPreset(index)
    }
}
