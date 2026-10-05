// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.VisualizerFeed
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.state.displayNameOf
import nl.mattix.andamp.ui.menu.AmpModals
import nl.mattix.andamp.ui.menu.AmpPromptHost
import nl.mattix.andamp.ui.menu.mainMenu
import nl.mattix.andamp.ui.menu.menuAnchorBounds
import nl.mattix.andamp.ui.menu.optionsMenu
import nl.mattix.andamp.ui.menu.visualizationMenu
import nl.mattix.andamp.ui.online.OnlineSkinsScreen
import nl.mattix.andamp.ui.prefs.MilkdropScreen
import nl.mattix.andamp.ui.prefs.PreferencesScreen
import nl.mattix.andamp.ui.prefs.overlaySettingsIntent
import nl.mattix.andamp.ui.prefs.visualizerPrefsOf
import nl.mattix.andamp.ui.theme.rememberSkinColorScheme
import nl.mattix.andamp.ui.welcome.WelcomeChoice
import nl.mattix.andamp.ui.welcome.WelcomeDialog
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.window.EQ_H
import nl.mattix.andamp.ui.window.EqFloatWindow
import nl.mattix.andamp.ui.window.LibraryWindow
import nl.mattix.andamp.ui.window.LocalSurfaceScreen
import nl.mattix.andamp.ui.window.LockedStack
import nl.mattix.andamp.ui.window.LoupeLayer
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MainFloatWindow
import nl.mattix.andamp.ui.window.MilkdropSurface
import nl.mattix.andamp.ui.window.MilkdropWindow
import nl.mattix.andamp.ui.window.PinnedVisual
import nl.mattix.andamp.ui.window.PlaylistFloatWindow
import nl.mattix.andamp.ui.window.PlaylistLayout
import nl.mattix.andamp.ui.window.PlaylistMenuActions
import nl.mattix.andamp.ui.window.PlaylistShadeFloatWindow
import nl.mattix.andamp.ui.window.SHADE_H
import nl.mattix.andamp.ui.window.SkinManagerWindow
import nl.mattix.andamp.ui.window.StackMember
import nl.mattix.andamp.ui.window.SurfaceScreen
import nl.mattix.andamp.ui.window.WindowStacking
import nl.mattix.andamp.ui.window.dockedOffset
import nl.mattix.andamp.ui.window.dockedStackHeight
import nl.mattix.andamp.ui.window.eqShadeWidgets
import nl.mattix.andamp.ui.window.eqWindowWidgets
import nl.mattix.andamp.ui.window.frameFor
import nl.mattix.andamp.ui.window.grabbableTop
import nl.mattix.andamp.ui.window.heightOf
import nl.mattix.andamp.ui.window.mainShadeWidgets
import nl.mattix.andamp.ui.window.mainWindowWidgets
import nl.mattix.andamp.ui.window.milkdropContentH
import nl.mattix.andamp.ui.window.milkdropHeight
import nl.mattix.andamp.ui.window.offsetInState
import nl.mattix.andamp.ui.window.unshadeEverything

// 60fps target; the loop self-corrects for scheduling drift
private const val VIS_FRAME_MS = 16L

/**
 * Drives the in-player visualizer (the 76x16 slot on the main window).
 *
 * Driven by `delay`, not `withFrameNanos`: a state write from a timer invalidates the canvas,
 * and a frame-clock waiter does not reliably schedule frames here. It runs only while the
 * activity is resumed and a track is playing.
 */
@Composable
private fun InPlayerVisualizerDriver(vm: WinampViewModel) {
    val s = vm.state
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // switched off, nothing is drawn from a frame, so the loop does not run
    val off = s.visMode == VisMode.Off
    LaunchedEffect(s.transport, off) {
        if (s.transport != Transport.Playing || off) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // asked per frame rather than taken once: a backend can hand its
            // audio over after playback has started. See [VisualizerFeed].
            val feed = VisualizerFeed(VIS_FRAME_MS) { vm.audioTap }
            var t = 0.0
            var next = SystemClock.uptimeMillis()
            while (true) {
                when (s.visMode) {
                    VisMode.Analyzer -> feed.stepAnalyzer(s, t)
                    VisMode.Oscilloscope -> feed.stepOscilloscope(s, t)
                    VisMode.Off -> Unit
                }
                s.visFrame++
                t += VIS_FRAME_MS / 1000.0
                // deadline pacing: delay() overshoots a little every tick
                next += VIS_FRAME_MS
                val wait = next - SystemClock.uptimeMillis()
                if (wait > 0) delay(wait) else next = SystemClock.uptimeMillis()
            }
        }
    }
}

@Composable
fun WinampScreen(
    vm: WinampViewModel = viewModel(),
    /** How this surface hands the player over when Always On Top is switched. */
    onFloatingChanged: (Boolean) -> Unit = {},
    /** How this surface steps out of the app, for a visit that came from outside it. */
    onDone: () -> Unit = {},
) {
    val skin = vm.skin
    val context = LocalContext.current
    val playlistActions = rememberPlaylistActions(vm)
    val libraryAccess = rememberLibraryAccess(vm)
    val skinPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                context.contentResolver.openInputStream(uri)?.let { stream ->
                    vm.skinOps.load(stream, context.displayNameOf(uri) ?: "picked skin")
                }
            }
        }
    // launches the preset pack picker when something asks for it
    PresetPicker(vm)

    // a source is signed in to on its own app's screen, so every source is asked what it
    // holds whenever the app comes forward
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { vm.sourceOps.reconcileWhileShown(context, lifecycle) }

    // menus and prompts capture this composition's picker launchers; after an activity
    // recreation those closures point at a dead activity's registry, so a modal retained
    // across the swap is dropped
    LaunchedEffect(Unit) {
        vm.state.activeMenu = null
        vm.state.namePrompt = null
    }

    BackCloses(vm.state, libraryBack = { vm.libraryOps.back() })

    // The player and the Material screens are destinations of one nav host.
    val nav = rememberNavController()
    // a menu item cannot hold a nav controller, so it sets a flag and the screen navigates
    LaunchedEffect(vm.state.presetManagerRequested) {
        if (!vm.state.presetManagerRequested) return@LaunchedEffect
        vm.state.presetManagerRequested = false
        nav.navigate(Screen.MILKDROP)
    }
    // a destination named from outside this screen, such as the floating player's menu
    LaunchedEffect(vm.state.arrivalDestination) {
        val going = vm.state.arrivalDestination ?: return@LaunchedEffect
        vm.state.arrivalDestination = null
        nav.navigate(going)
    }
    val here =
        nav
            .currentBackStackEntryAsState()
            .value
            ?.destination
            ?.route
    // the status bar's icons, dark or light for what is under the bar: the
    // wallpaper on the player, the screen's own background everywhere else
    val modern = skin?.let { rememberSkinColorScheme(it) } ?: darkColorScheme()
    StatusBarIcons(
        onPlayer = here == null || here == Screen.PLAYER,
        playerFillsScreen = vm.doubleSize.on,
        dark = modern.background.luminance() > LIGHT_BACKGROUND,
    )
    LaunchedEffect(here, vm.overlayOps.gate.wanted, vm.overlayOps.gate.permitted) {
        // Always On Top can be switched from Preferences too; the handover to the floating
        // player waits until the player is the destination shown. The route is read from
        // the controller: on the frame an arrival comes in, [here] still reads the player
        // while the effect above has already navigated.
        val onPlayer = nav.currentDestination?.route == Screen.PLAYER && vm.state.arrivalDestination == null
        if (onPlayer && vm.overlayOps.gate.wanted && vm.overlayOps.gate.permitted) {
            onFloatingChanged(true)
        }
    }
    NavHost(
        nav,
        startDestination = Screen.PLAYER,
        modifier = Modifier.fillMaxSize(),
        // a Material screen fades and scales in over the player
        enterTransition = { fadeIn(ARRIVING) + scaleIn(ARRIVING, initialScale = FROM) },
        exitTransition = { fadeOut(LEAVING) + scaleOut(LEAVING, targetScale = FROM) },
        popEnterTransition = { fadeIn(ARRIVING) + scaleIn(ARRIVING, initialScale = FROM) },
        popExitTransition = { fadeOut(LEAVING) + scaleOut(LEAVING, targetScale = FROM) },
    ) {
        modernScreens(vm, skin, libraryAccess) { route ->
            nav.popBackStack()
            // only leaving the screen the visit arrived at ends the visit
            if (vm.state.leavingEndsTheVisit(route)) onDone()
        }
        composable(Screen.PLAYER) {
            if (skin == null) return@composable // black screen until the base skin is decoded
            // One surface lays the player out at a time. This composition keeps recomposing
            // after ON_STOP, and with the floating player up, two layout passes would write
            // the same window rectangles, each measured against its own screen.
            if (vm.overlayOps.gate.showing) return@composable
            PlayerSurface(
                vm,
                skin,
                playlistActions,
                libraryAccess,
                onPickSkin = { skinPicker.launch(arrayOf("*/*")) },
                onPreferences = { nav.navigate(Screen.PREFERENCES) },
                onMuseum = { nav.navigate(Screen.MUSEUM) },
                onExit = {
                    vm.exit() // stops playback + the media notification service
                    (context as? Activity)?.finish()
                },
                // minimize moves the task to the back; playback continues
                onMinimize = { (context as? Activity)?.moveTaskToBack(true) },
                onOverlaySettings = { context.startActivity(overlaySettingsIntent(context)) },
                onFloatingChanged = onFloatingChanged,
            )
        }
    }
}

/**
 * The player: every skinned window, the visualizer and the modals over them.
 *
 * It has two callers. The activity shows it under a nav host, where a menu can open
 * Preferences or the museum; the floating overlay shows it over other apps and passes its
 * own callbacks for the places it cannot reach.
 */
@Composable
@Suppress("LongParameterList") // one parameter per place this player cannot reach itself
fun PlayerSurface(
    vm: WinampViewModel,
    skin: Skin,
    playlistActions: PlaylistMenuActions,
    libraryAccess: LibraryAccessHandle,
    onPickSkin: () -> Unit,
    onPreferences: () -> Unit,
    onMuseum: () -> Unit,
    onExit: () -> Unit,
    /** Winamp's minimize: the player goes away, the music does not. */
    onMinimize: () -> Unit,
    /** Opens the system screen that grants drawing over other apps. */
    onOverlaySettings: () -> Unit,
    /** Called with the new value when Always On Top has been switched from this surface. */
    onFloatingChanged: (Boolean) -> Unit = {},
    /**
     * What this surface knows about the screen around it, or null to read the window's
     * insets; see [LocalSurfaceScreen].
     */
    surfaceScreen: SurfaceScreen? = null,
    /**
     * Whether this surface can fill the screen with the player. The floating player cannot:
     * switching Double Size on there switches Always On Top off, and it is the app that
     * fills the screen.
     */
    fillsScreen: Boolean = true,
) {
    // filling the screen, the windows are one locked stack with black around it
    val locked = fillsScreen && vm.doubleSize.on
    // The clutter bar's D and the Options menu share this switch. Switching Double Size on
    // switches Always On Top off, and the surface is told as when its own A did that.
    val doubleSize = {
        val floating = vm.overlayOps.gate.wanted
        vm.doubleSize.toggle()
        if (floating && !vm.overlayOps.gate.wanted) onFloatingChanged(false)
    }
    Box(Modifier.fillMaxSize().then(if (locked) Modifier.background(Color.Black) else Modifier)) {
        // the fullscreen visualizer is drawn over every window and outside the status-bar
        // inset the windows are held inside
        val fullscreen = vm.state.milkdropOn && vm.state.milkdropFullscreen
        ImmersiveWhile(fullscreen)
        CompositionLocalProvider(LocalSurfaceScreen provides surfaceScreen) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .then(
                        surfaceScreen?.let { told ->
                            Modifier.padding(top = with(LocalDensity.current) { told.statusBar.toDp() })
                        } ?: Modifier.windowInsetsPadding(WindowInsets.statusBars),
                    ),
            ) {
                val s = vm.state
                LibraryAsks(vm, libraryAccess)
                val screenW = constraints.maxWidth
                val screenH = constraints.maxHeight
                val viewport = playerViewport(screenW, screenH, fillScreen = locked)
                val scale = viewport.scale

                val quit = onExit

                // the clutter bar's A and the Options menu share this switch
                val alwaysOnTop = {
                    vm.overlayOps.toggle(
                        prompt = { vm.state.prompt = it },
                        openSettings = onOverlaySettings,
                        onChanged = onFloatingChanged,
                    )
                }
                val openMenu = {
                    // built at tap time so the Skins submenu shows the current skin
                    s.activeMenu =
                        mainMenu(
                            vm,
                            // Play file... and Play directory... replace the queue and play
                            onOpenFile = { playlistActions.playFile() },
                            onOpenFolder = { playlistActions.playDir() },
                            onPickSkin = onPickSkin,
                            onExit = quit,
                            onPreferences = onPreferences,
                            onMuseum = onMuseum,
                            onAlwaysOnTop = alwaysOnTop,
                            onDoubleSize = doubleSize,
                        )
                }
                val mainWidgets =
                    remember(vm, s.mainShaded) {
                        if (s.mainShaded) {
                            mainShadeWidgets(
                                vm,
                                onOpenFile = { toPlay ->
                                    if (toPlay) playlistActions.addFileThenPlay() else playlistActions.addFile()
                                },
                                onOptions = openMenu,
                                onExit = quit,
                                visualizerMenu = { anchor -> visualizationMenu(vm, anchor) },
                            )
                        } else {
                            mainWindowWidgets(
                                vm,
                                onOpenFile = { toPlay ->
                                    if (toPlay) playlistActions.addFileThenPlay() else playlistActions.addFile()
                                },
                                onExit = quit,
                                onMinimize = onMinimize,
                                visualizerMenu = { anchor -> visualizationMenu(vm, anchor) },
                                optionsMenu = { anchor ->
                                    optionsMenu(
                                        vm,
                                        anchor,
                                        onPreferences,
                                        onAlwaysOnTop = alwaysOnTop,
                                        onDoubleSize = doubleSize,
                                    )
                                },
                                onAlwaysOnTop = alwaysOnTop,
                                onDoubleSize = doubleSize,
                                // the clutter bar's I: the item playing, or the last one that played
                                onFileInfo = { vm.trackInfoOps.show(s.lastPlayed) },
                            ) {
                                openMenu()
                            }
                        }
                    }
                val eqWidgets = remember(vm, s.eqShaded) { if (s.eqShaded) eqShadeWidgets(vm) else eqWindowWidgets(vm) }
                // the skin is laid out on the viewport's surface, the size of which it sees
                // as its constraints
                PlayerViewportBox(viewport) {
                    PlayerWindows(
                        vm,
                        skin,
                        scale,
                        locked,
                        onLongPress = openMenu,
                        playlistActions,
                        libraryAccess,
                        mainWidgets,
                        eqWidgets,
                    )
                }

                // menus pop from their opening widget, like Winamp's context menus. They and
                // the dialogs are at the screen's size, outside the surface the skin is on.
                AmpModals(s, skin = skin, anchorBounds = { anchor ->
                    menuAnchorBounds(anchor, scale, screenW, screenH, s.windowRects, viewport.shrink)
                })

                // the first-launch dialog, over the player
                Welcome(vm, skin)
            }

            // last in the box, so on top: over the windows and outside the inset they sit in
            if (fullscreen) MilkdropSurface(vm, Modifier.fillMaxSize())
        }
    }
}

/**
 * The windows on the surface they are laid out on, with what moves over them.
 *
 * The surface is this box, in its own pixels: [scale] of them to a virtual pixel.
 */
@Composable
@Suppress("LongParameterList") // the player's parts, handed down from the surface that builds them
private fun BoxWithConstraintsScope.PlayerWindows(
    vm: WinampViewModel,
    skin: Skin,
    scale: Int,
    /** Whether the windows are held in one stack; see [LockedStack]. */
    locked: Boolean,
    onLongPress: () -> Unit,
    playlistActions: PlaylistMenuActions,
    libraryAccess: LibraryAccessHandle,
    mainWidgets: List<Widget>,
    eqWidgets: List<Widget>,
) {
    val s = vm.state
    val virtualAvail = constraints.maxHeight / scale
    // a shaded window is only as high as its title bar
    val mainH = heightOf(MAIN_H, s.mainShaded)
    val eqH = heightOf(EQ_H, s.eqShaded)
    val milkdropH = milkdropHeight(frameFor(skin), milkdropContentH(s.milkdropSteps))
    // the docked stack: the player, with the equalizer and the plug-in window
    // under it. Only a window still attached counts toward its height, which
    // the playlist sizes itself against.
    val stackTop = grabbableTop(scale, LocalDensity.current)
    val dockedH =
        dockedStackHeight(
            top = stackTop,
            anchor = mainH,
            under =
                listOf(
                    stackMember(s, WindowStore.EQ, eqH, s.eqVisible),
                    stackMember(s, WindowStore.MILKDROP, milkdropH, s.milkdropOn),
                ),
        )
    val safeBottomPx =
        constraints.maxHeight -
            (LocalSurfaceScreen.current?.bottom ?: WindowInsets.safeDrawing.getBottom(LocalDensity.current))
    val safeBottom = safeBottomPx / scale
    s.screenW = constraints.maxWidth / scale
    s.screenH = virtualAvail
    s.stackLocked = locked
    val held =
        if (locked) {
            LockedStack.layout(
                LockedStack.Ask(
                    screenW = s.screenW,
                    // nothing in the stack is dragged, so it starts at the very top
                    top = 0,
                    safeBottom = safeBottom,
                    mainH = mainH,
                    eqH = eqH.takeIf { s.eqVisible },
                    visChromeH = frameFor(skin).chromeH.takeIf { s.milkdropOn && !s.milkdropFullscreen },
                    visSteps = vm.doubleSize.visSteps ?: s.milkdropSteps,
                    playlist =
                        when {
                            !s.plVisible -> LockedStack.Playlist.CLOSED
                            s.plShaded -> LockedStack.Playlist.SHADED
                            else -> LockedStack.Playlist.OPEN
                        },
                    covers =
                        listOfNotNull(
                            WindowStore.LIBRARY.takeIf { s.libraryOpen },
                            WindowStore.SKINS.takeIf { s.skinManagerOpen },
                        ),
                ),
            )
        } else {
            null
        }

    InPlayerVisualizerDriver(vm)
    UiClocks(s)

    FloatingWindows(
        vm,
        skin,
        scale,
        onLongPress,
        playlistActions,
        libraryAccess,
        mainWidgets,
        eqWidgets,
        mainH,
        eqH,
        dockedH,
        virtualAvail,
        safeBottom,
        held,
    )

    // over every window: a lens clipped to its own window would be cut off by
    // the window above it
    LoupeLayer(s, scale)
}

/**
 * Every window, stacked by z-index so the one pressed last is on top and hit-tested first.
 *
 * Each window floats. The classic stack is where they start, and a window with no stored
 * position is still docked there. With [held] the layout holds every window instead: each
 * is drawn in the rectangle it has there, and one that has none is not shown.
 */
@Composable
@Suppress("LongParameterList", "CyclomaticComplexMethod") // one window per branch, and what each needs
private fun FloatingWindows(
    vm: WinampViewModel,
    skin: Skin,
    scale: Int,
    /** Winamp's right click on the player or the equalizer: the main menu. */
    onLongPress: () -> Unit,
    playlistActions: PlaylistMenuActions,
    libraryAccess: LibraryAccessHandle,
    mainWidgets: List<Widget>,
    eqWidgets: List<Widget>,
    mainH: Int,
    eqH: Int,
    dockedH: Int,
    screenH: Int,
    safeBottom: Int,
    held: LockedStack.Places?,
) {
    val s = vm.state

    fun shown(id: String) = held == null || id in held.rects

    // the stack starts below the strip the system keeps for the notification shade
    val top = grabbableTop(scale, LocalDensity.current)
    // what a collapsed playlist expands back to
    val plExpandedH =
        PlaylistLayout
            .ofSegments(s.plSegments ?: PlaylistLayout.segmentsThatFit(safeBottom - top - dockedH))
            .height
    // with the shade gesture switched off, every shaded window is opened; this also runs
    // at launch, for a window that was stored shaded
    // and again when the layout changes: each layout has its own collapsed windows
    LaunchedEffect(s.shadeEnabled, plExpandedH, held != null) {
        if (!s.shadeEnabled) s.unshadeEverything(plExpandedH)
    }
    // Composition order is fixed and stacking is a zIndex: a window raised by reordering
    // these children would be torn down and rebuilt mid-press, cancelling the gesture
    // that raised it.
    WindowStacking.stack(WindowStore.WINDOWS, s.shownOrder).forEach { (id, z) ->
        key(id) {
            when (id) {
                WindowStore.MAIN -> {
                    MainFloatWindow(
                        vm,
                        skin,
                        scale,
                        widgets = mainWidgets,
                        height = mainH,
                        defaultOffset = dockedOffset(top, mainH, screenH),
                        modifier = Modifier.fillMaxSize().zIndex(z),
                        onLongPress = onLongPress,
                        pinnedAt = held?.rects?.get(id)?.topLeft,
                    )
                }

                WindowStore.EQ -> {
                    if (s.eqVisible && shown(id)) {
                        EqFloatWindow(
                            vm,
                            skin,
                            scale,
                            widgets = eqWidgets,
                            height = eqH,
                            defaultOffset = dockedOffset(top + mainH, eqH, screenH),
                            modifier = Modifier.fillMaxSize().zIndex(z),
                            onLongPress = onLongPress,
                            pinnedAt = held?.rects?.get(id)?.topLeft,
                        )
                    }
                }

                WindowStore.MILKDROP -> {
                    if (s.milkdropOn && !s.milkdropFullscreen && shown(id)) {
                        MilkdropWindow(
                            vm,
                            skin,
                            scale,
                            // under what is still docked: an equalizer that was dragged
                            // away leaves no gap
                            defaultOffset =
                                dockedOffset(
                                    top +
                                        dockedStackHeight(
                                            top = top,
                                            anchor = mainH,
                                            under = listOf(stackMember(s, WindowStore.EQ, eqH, s.eqVisible)),
                                        ),
                                    milkdropHeight(frameFor(skin), milkdropContentH(s.milkdropSteps)),
                                    screenH,
                                ),
                            modifier = Modifier.fillMaxSize().zIndex(z),
                            // the stack keeps the height its grip asks for
                            pinned =
                                held?.rects?.get(id)?.let { rect ->
                                    PinnedVisual(rect, held.visMaxSteps) { vm.doubleSize.visSteps = it }
                                },
                        )
                    }
                }

                WindowStore.PLAYLIST -> {
                    val open = s.plVisible && shown(id)
                    if (open && s.plShaded) {
                        PlaylistShadeFloatWindow(
                            vm,
                            skin,
                            scale,
                            expandedH = plExpandedH,
                            defaultOffset = dockedOffset(top + dockedH, SHADE_H, screenH),
                            modifier = Modifier.fillMaxSize().zIndex(z),
                            pinnedAt = held?.rects?.get(id)?.topLeft,
                        )
                    } else if (open) {
                        PlaylistFloatWindow(
                            vm,
                            skin,
                            scale,
                            playlistActions,
                            // docked under the stack, filling the rest of the height
                            dockedTop = top + dockedH,
                            dockedSegments = PlaylistLayout.segmentsThatFit(safeBottom - top - dockedH),
                            modifier = Modifier.fillMaxSize().zIndex(z),
                            pinned = held?.rects?.get(id),
                        )
                    }
                }

                WindowStore.LIBRARY -> {
                    if (s.libraryOpen) {
                        LibraryWindow(vm, skin, scale, libraryAccess, Modifier.fillMaxSize().zIndex(z), held?.rects?.get(id))
                    }
                }

                WindowStore.SKINS -> {
                    if (s.skinManagerOpen) {
                        SkinManagerWindow(vm, skin, scale, Modifier.fillMaxSize().zIndex(z), held?.rects?.get(id))
                    }
                }
            }
        }
    }
}

/**
 * A full screen of Material chrome, opaque from edge to edge.
 *
 * The activity's window is translucent, so these screens fill the whole surface, including
 * the space under the status bar, and their own top bar holds that inset. They use the
 * skin's color scheme.
 */
@Composable
private fun ModernScreen(
    skin: Skin?,
    state: WinampState,
    onLeave: () -> Unit,
    content: @Composable () -> Unit,
) {
    // the back gesture and the Done button make the same call
    BackHandler(onBack = onLeave)
    MaterialTheme(colorScheme = skin?.let { rememberSkinColorScheme(it) } ?: darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
            // a permission prompt or a backend's notice can be raised while one of these
            // screens is open
            AmpPromptHost(state)
        }
    }
}

/**
 * The destinations that are not the player.
 */
private fun NavGraphBuilder.modernScreens(
    vm: WinampViewModel,
    skin: Skin?,
    libraryAccess: LibraryAccessHandle,
    onLeave: (String) -> Unit,
) {
    composable(Screen.MUSEUM) {
        val leave = { onLeave(Screen.MUSEUM) }
        ModernScreen(skin, vm.state, leave) { OnlineSkinsScreen(vm.onlineSkins, onClose = leave) }
    }
    composable(Screen.PREFERENCES) {
        val leave = { onLeave(Screen.PREFERENCES) }
        ModernScreen(skin, vm.state, leave) {
            PreferencesScreen(
                vm,
                access = libraryAccess.status,
                onRequestAccess = { libraryAccess.request() },
                onClose = leave,
                // the page asked for from outside, cleared once used
                startAt = vm.state.arrivalPage,
                onArrived = { vm.state.arrivalPage = null },
            )
        }
    }
    composable(Screen.MILKDROP) {
        // no onManagePresets: this screen is where that would go
        val leave = { onLeave(Screen.MILKDROP) }
        ModernScreen(skin, vm.state, leave) { MilkdropScreen(visualizerPrefsOf(vm), onClose = leave) }
    }
}

/** The background luminance above which the status bar icons are drawn dark. */
private const val LIGHT_BACKGROUND = 0.5f

/**
 * The nav routes. The player is the start destination.
 *
 * Internal because the floating player cannot navigate: it names a destination and the
 * activity carries it here (`arrivalDestination`).
 */
internal object Screen {
    const val PLAYER = "player"
    const val PREFERENCES = "preferences"
    const val MUSEUM = "museum"
    const val MILKDROP = "milkdrop"
}

/**
 * The first-launch dialog: keep the skin on screen, or browse the museum. It is shown until
 * one of the two is chosen.
 */
@Composable
private fun Welcome(
    vm: WinampViewModel,
    skin: Skin?,
) {
    if (vm.welcome.seen) return
    val museum = vm.onlineSkins.museum
    // fetches the museum's first tiles for the dialog
    LaunchedEffect(Unit) { museum.show(0..TILES) }
    val done = { vm.welcome.seen = true }
    // colored by the skin on screen, like the other modals
    MaterialTheme(colorScheme = skin?.let { rememberSkinColorScheme(it) } ?: darkColorScheme()) {
        WelcomeDialog(
            WelcomeChoice(
                skin = skin,
                tiles = (0..TILES).mapNotNull { museum.skins[it] },
                museumCount = museum.total.coerceAtLeast(0),
                onKeep = done,
                onBrowse = {
                    done()
                    vm.state.arrivalDestination = Screen.MUSEUM
                },
            ),
        )
    }
}

/**
 * The last index of the museum tiles the welcome asks for: its first six, of which one row
 * is shown. Six, so that the tile the dialog moves up into that row is among them.
 */
private const val TILES = 5

/** The spring a screen arrives on; [LEAVING] is stiffer. */
private val ARRIVING = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

private val LEAVING = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)

/** The scale a screen arrives from and leaves to. */
private const val FROM = 0.92f

/** One window as the stack sees it: its height, whether it is there, and where it is. */
private fun stackMember(
    s: nl.mattix.andamp.state.WinampState,
    id: String,
    height: Int,
    shown: Boolean,
) = StackMember(
    height = height,
    shown = shown,
    top = s.windowRects[id]?.top,
    placed = s.offsetInState(id) != null,
)

/**
 * Asks for the audio permission when a song was pressed without it (`libraryAsk`), and
 * hands the song back through `accessGiven` once it is allowed.
 */
@Composable
private fun LibraryAsks(
    vm: WinampViewModel,
    libraryAccess: LibraryAccessHandle,
) {
    val asked = vm.state.libraryAsk ?: return
    LaunchedEffect(asked) {
        vm.state.libraryAsk = null
        libraryAccess.ask { vm.accessGiven(asked) }
    }
}
