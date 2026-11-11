// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import android.app.Activity
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.mattix.andamp.R
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.CurrentSkin
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.state.displayNameOf
import nl.mattix.andamp.ui.menu.AmpModals
import nl.mattix.andamp.ui.menu.AmpPromptHost
import nl.mattix.andamp.ui.menu.MenuBarSection
import nl.mattix.andamp.ui.menu.mainMenu
import nl.mattix.andamp.ui.menu.menuAnchorBounds
import nl.mattix.andamp.ui.online.OnlineSkinsScreen
import nl.mattix.andamp.ui.prefs.MilkdropScreen
import nl.mattix.andamp.ui.prefs.PreferencesScreen
import nl.mattix.andamp.ui.prefs.overlaySettingsIntent
import nl.mattix.andamp.ui.prefs.presetSelectorOf
import nl.mattix.andamp.ui.prefs.visualizerPrefsOf
import nl.mattix.andamp.ui.theme.rememberSkinColorScheme
import nl.mattix.andamp.ui.welcome.WelcomeChoice
import nl.mattix.andamp.ui.welcome.WelcomeDialog
import nl.mattix.andamp.ui.window.ClassicStack
import nl.mattix.andamp.ui.window.EQ_H
import nl.mattix.andamp.ui.window.LibraryWindow
import nl.mattix.andamp.ui.window.LocalSurfaceScreen
import nl.mattix.andamp.ui.window.LoupeLayer
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MainPlaceholder
import nl.mattix.andamp.ui.window.MilkdropSurface
import nl.mattix.andamp.ui.window.MilkdropWindow
import nl.mattix.andamp.ui.window.PlaylistLayout
import nl.mattix.andamp.ui.window.PlaylistMenuActions
import nl.mattix.andamp.ui.window.SkinManagerWindow
import nl.mattix.andamp.ui.window.SnapTicks
import nl.mattix.andamp.ui.window.SurfaceScreen
import nl.mattix.andamp.ui.window.WindowStacking
import nl.mattix.andamp.ui.window.classicMember
import nl.mattix.andamp.ui.window.frameFor
import nl.mattix.andamp.ui.window.heightOf
import nl.mattix.andamp.ui.window.milkdropContentH
import nl.mattix.andamp.ui.window.milkdropHeight
import nl.mattix.andamp.ui.window.unshadeEverything
import nl.mattix.andamp.ui.window.windowScreen
import nl.mattix.andamp.wal.WAL_LAYER_Z
import nl.mattix.andamp.wal.WalBundled
import nl.mattix.andamp.wal.WalClassicSync
import nl.mattix.andamp.wal.WalFilled
import nl.mattix.andamp.wal.WalLayer
import nl.mattix.andamp.wal.WalLayerParts
import nl.mattix.andamp.wal.WalPlacement
import nl.mattix.andamp.wal.WalSurface
import nl.mattix.andamp.wal.rememberWalDock
import nl.mattix.andamp.wal.rememberWalLayerParts

@Composable
internal fun WinampScreen(
    vm: WinampViewModel = viewModel(),
    /** How this surface hands the player over when Always On Top is switched. */
    onFloatingChanged: (Boolean) -> Unit = {},
    /** How this surface steps out of the app, for a visit that came from outside it. */
    onDone: () -> Unit = {},
    /** The modern skin the player shows, the worn one unless told, or null for the classic skin; see [PlayerSurface]. */
    wal: WalSurface? = vm.skinOps.wal,
) {
    val skin = vm.skin
    val context = LocalContext.current
    val playlistActions = rememberPlaylistActions(vm)
    val libraryAccess = rememberLibraryAccess(vm)
    val skinPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                context.contentResolver.openInputStream(uri)?.let { stream ->
                    vm.skinOps.load(stream, context.displayNameOf(uri) ?: context.getString(R.string.skin_picked_name))
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
        nav.arriveAt(going)
    }
    val here =
        nav
            .currentBackStackEntryAsState()
            .value
            ?.destination
            ?.route
    // the status bar's icons, dark or light for what is under the bar: the wallpaper
    // behind a floating player, black behind one that fills the screen, and the screen's
    // own background everywhere else
    val modern = skin?.let { rememberSkinColorScheme(it) } ?: darkColorScheme()
    StatusBarIcons(
        onPlayer = here == null || here == Screen.PLAYER,
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
    val covered = here != null && here != Screen.PLAYER
    SideEffect { vm.state.playerCovered = covered }
    Box(Modifier.fillMaxSize()) {
        // The player is drawn under the nav host rather than as one of its destinations: a
        // Material screen arrives over it and leaves to it, and the back gesture pulls a screen
        // aside onto it, so the player and a visual running in it keep going behind. Covered,
        // accessibility services do not read it. Until the base skin is decoded the screen stays
        // black. With the floating player up this one is not laid out: the composition keeps
        // recomposing after ON_STOP, and two layout passes would write the same window
        // rectangles, each measured against its own screen.
        if (skin != null && !vm.overlayOps.gate.showing) {
            Box(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)) {
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
                    wal = wal,
                )
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
            // the player itself is drawn under the nav host, not as a destination: see above
            composable(Screen.PLAYER) {}
        }
    }
}

/**
 * The player: every skinned window, the visualizer and the modals over them.
 *
 * It has two callers. The activity shows it under a nav host, where a menu can open
 * Preferences or the museum over it; the floating overlay shows it over other apps and passes its
 * own callbacks for the places it cannot reach.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // one parameter per place this player cannot reach itself, each wired up here
internal fun PlayerSurface(
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
     * The skin the engine draws in place of the main window, equalizer and playlist, or null
     * while none is ready, when a placeholder holds the main window's place. The classic skin
     * still draws the library, the skin manager and the visualizer window.
     */
    wal: WalSurface? = null,
    /**
     * Whether a screen of the app covers this surface, so a modern skin's clock rests. The floating
     * player is never covered.
     */
    covered: () -> Boolean = { vm.state.playerCovered && !vm.state.screenPulled },
    /** Whether a modern skin keeps to its natural size instead of filling the screen, as over other apps. */
    compact: Boolean = false,
) {
    // read when the menu is built, so a menu opened later sees the skin worn then
    val walNow by rememberUpdatedState(wal)
    // The clutter bar's D and the Options menu share this switch: a classic skin fills the
    // screen, as its D has it. A modern skin has no Double Size, and neither has the
    // placeholder standing in while a skin loads.
    val doubleSize = {
        if (walNow?.classic == true) WalFilled.toggle(vm.getApplication())
    }
    SnapTicks(vm.state)
    Box(Modifier.fillMaxSize()) {
        // the fullscreen visualizer is drawn over every window and outside the insets the
        // windows are held inside
        val fullscreen = vm.state.visualizerFullscreen
        ImmersiveWhile(fullscreen)
        CompositionLocalProvider(LocalSurfaceScreen provides surfaceScreen) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .then(
                        surfaceScreen?.let { told ->
                            Modifier.padding(top = with(LocalDensity.current) { told.statusBar.toDp() })
                        } ?: Modifier.windowInsetsPadding(
                            // the status bar, and whatever sits at the sides of a phone held
                            // sideways: the camera hole, or a navigation bar with buttons
                            WindowInsets.statusBars.union(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                        ),
                    ),
            ) {
                val s = vm.state
                LibraryAsks(vm, libraryAccess)
                FolderAsks(vm)
                val screenW = constraints.maxWidth
                val screenH = constraints.maxHeight
                val viewport = playerViewport(screenW, screenH, sideways = s.sideways)
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
                val openMenu =
                    mainMenuOpener(
                        vm,
                        playlistActions,
                        onPickSkin,
                        quit,
                        onPreferences,
                        onMuseum,
                        alwaysOnTop,
                        doubleSize,
                        modernPlayer = { walNow?.classic == false },
                        classicLook = { walNow?.takeIf { it.classic } },
                    )
                val walParts =
                    wal?.let {
                        rememberWalLayerParts(it, vm, playlistActions, openMenu, onMinimize, quit, covered, compact, alwaysOnTop)
                    }
                // a classic skin walkit draws follows the app's time, vis and selection, and they follow it
                wal?.takeIf { it.classic }?.let { WalClassicSync(it, vm.state) }
                // the skin is laid out on the viewport's surface, the size of which it sees
                // as its constraints
                PlayerViewportBox(viewport) {
                    PlayerWindows(
                        vm,
                        skin,
                        scale,
                        onLongPress = { openMenu(null, null) },
                        libraryAccess,
                        walParts,
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
 * What opens the main menu, built at tap time so the Skins submenu shows the current skin. A
 * long press on the skin opens it at the press; one with no anchor opens it at the main
 * window's options button.
 */
private fun mainMenuOpener(
    vm: WinampViewModel,
    playlistActions: PlaylistMenuActions,
    onPickSkin: () -> Unit,
    onExit: () -> Unit,
    onPreferences: () -> Unit,
    onMuseum: () -> Unit,
    onAlwaysOnTop: () -> Unit,
    onDoubleSize: () -> Unit,
    /** Whether a modern skin is worn, asked each time the menu opens. */
    modernPlayer: () -> Boolean,
    /** The classic skin walkit draws, or null; asked each time the menu opens. */
    classicLook: () -> WalSurface?,
): (anchor: MenuAnchor?, section: MenuBarSection?) -> Unit =
    { anchor, section ->
        val menu =
            mainMenu(
                vm,
                // Play file... and Play directory... replace the queue and play
                onOpenFile = { playlistActions.playFile() },
                onOpenFolder = { playlistActions.playDir() },
                onPickSkin = onPickSkin,
                onExit = onExit,
                onPreferences = onPreferences,
                onMuseum = onMuseum,
                onAlwaysOnTop = onAlwaysOnTop,
                onDoubleSize = onDoubleSize,
                modernPlayer = modernPlayer(),
                section = section,
                classicLook = classicLook(),
            )
        vm.state.activeMenu = if (anchor == null) menu else menu.copy(anchor = anchor)
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
    onLongPress: () -> Unit,
    libraryAccess: LibraryAccessHandle,
    /** The modern skin shown in place of the main window, the equalizer and the playlist, or null. */
    wal: WalLayerParts?,
) {
    val s = vm.state
    val screen = windowScreen(scale, s)
    // a shaded window is only as high as its title bar
    val mainH = heightOf(MAIN_H, s.mainShaded)
    val eqH = heightOf(EQ_H, s.eqShaded)
    val milkdropH = milkdropHeight(frameFor(skin), milkdropContentH(s.milkdropSteps))
    // where the windows sit until they are moved; a modern skin's stack takes the main window's place
    val walDock by rememberWalDock(wal?.surface)
    val classic =
        ClassicStack.Ask(
            screen,
            s.sideways,
            mainH,
            eq = s.classicMember(WindowStore.EQ, eqH, s.eqVisible),
            vis = s.classicMember(WindowStore.MILKDROP, milkdropH, s.milkdropOn),
        )
    val docked =
        if (wal == null) {
            ClassicStack.layout(classic)
        } else {
            WalPlacement.dockedStack(classic, s.windowRects[WindowStore.MAIN], walDock, milkdropH)
        }
    // the clocks drive only the windows Andamp draws: the Library's caret, and a list thrown there
    // or in the Skin Browser
    UiClocks(s, followsScreen = true) { s.libraryOpen || s.skinManagerOpen }

    FloatingWindows(
        vm,
        skin,
        scale,
        onLongPress,
        libraryAccess,
        docked,
        wal,
    )

    // over every window: a lens clipped to its own window would be cut off by
    // the window above it
    LoupeLayer(s, scale)
}

/**
 * Every window, stacked by z-index so the one pressed last is on top and hit-tested first.
 *
 * Each window floats. The classic stack is where they start, and a window with no stored
 * position is still docked there.
 */
@Composable
@Suppress("LongParameterList", "CyclomaticComplexMethod") // one window per branch, and what each needs
private fun FloatingWindows(
    vm: WinampViewModel,
    skin: Skin,
    scale: Int,
    /** Winamp's right click on the player or the equalizer: the main menu. */
    onLongPress: () -> Unit,
    libraryAccess: LibraryAccessHandle,
    /** Where each window sits until it is moved. */
    docked: ClassicStack.Places,
    /** The modern skin shown in place of the main window, the equalizer and the playlist, or null. */
    wal: WalLayerParts?,
) {
    val s = vm.state

    // what a collapsed playlist expands back to
    val plExpandedH = PlaylistLayout.ofSegments(s.plSegments ?: docked.playlistSegments).height
    // with the shade gesture switched off, every shaded window is opened; this also runs
    // at launch, for a window that was stored shaded, and again when the phone is turned:
    // each way round keeps its collapsed windows apart
    LaunchedEffect(s.shadeEnabled, plExpandedH, s.sideways) {
        if (!s.shadeEnabled) s.unshadeEverything(plExpandedH)
    }
    // Composition order is fixed and stacking is a zIndex: a window raised by reordering
    // these children would be torn down and rebuilt mid-press, cancelling the gesture
    // that raised it.
    WindowStacking.stack(WindowStore.WINDOWS, s.windowOrder).forEach { (id, z) ->
        key(id) {
            when (id) {
                WindowStore.MAIN -> {
                    // a skin still loading, or none drawn, shows the player's place
                    val loading: @Composable () -> Unit = {
                        MainPlaceholder(s, scale, docked.main, onLongPress, Modifier.fillMaxSize().zIndex(z))
                    }
                    if (wal == null) {
                        loading()
                    } else {
                        WalLayer(wal, s, scale, Modifier.fillMaxSize().zIndex(WAL_LAYER_Z), companion = loading)
                    }
                }

                WindowStore.MILKDROP -> {
                    if (s.milkdropOn && !s.milkdropFullscreen) {
                        MilkdropWindow(
                            vm,
                            skin,
                            scale,
                            docked = docked.vis,
                            modifier = Modifier.fillMaxSize().zIndex(z),
                        )
                    }
                }

                WindowStore.LIBRARY -> {
                    if (s.libraryOpen) {
                        LibraryWindow(vm, skin, scale, libraryAccess, Modifier.fillMaxSize().zIndex(z))
                    }
                }

                WindowStore.SKINS -> {
                    if (s.skinManagerOpen) {
                        SkinManagerWindow(vm, skin, scale, Modifier.fillMaxSize().zIndex(z))
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
    // the back gesture pulls the screen aside, onto the player under it; a screen with levels of
    // its own takes the gesture first while it is deeper than its first
    val pull = rememberBackPull(onBack = onLeave)
    // the player under the screen wakes its visual while the gesture shows it
    LaunchedEffect(pull) { snapshotFlow { pull.progress > 0f }.collect { state.screenPulled = it } }
    DisposableEffect(Unit) { onDispose { state.screenPulled = false } }
    MaterialTheme(colorScheme = skin?.let { rememberSkinColorScheme(it) } ?: darkColorScheme()) {
        Surface(Modifier.fillMaxSize().pulledAside(pull), color = MaterialTheme.colorScheme.background) {
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
        ModernScreen(skin, vm.state, leave) {
            MilkdropScreen(
                visualizerPrefsOf(vm),
                presetSelectorOf(vm),
                onClose = leave,
                preview = { MilkdropSurface(vm, it, inWindow = false) },
            )
        }
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
    val context = LocalContext.current
    // Andamp's own skin as the engine opens it, in the phone's palette: written off main
    val own by produceState<java.io.File?>(null, skin) {
        value =
            withContext(Dispatchers.IO) {
                runCatching { WalBundled.fileOf(context, BundledSkins.BASE, CurrentSkin.live) }.getOrNull()
            }
    }
    val museum = vm.onlineSkins.museum
    // fetches the museum's first tiles for the dialog
    LaunchedEffect(Unit) { museum.show(0..TILES) }
    val done = { vm.welcome.seen = true }
    // colored by the skin on screen, like the other modals
    MaterialTheme(colorScheme = skin?.let { rememberSkinColorScheme(it) } ?: darkColorScheme()) {
        WelcomeDialog(
            WelcomeChoice(
                skin = own,
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
