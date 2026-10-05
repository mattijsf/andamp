// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import nl.mattix.andamp.BuildConfig
import nl.mattix.andamp.state.DspOps
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.PackSources
import nl.mattix.andamp.state.PluginOps
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.sourceRoute
import nl.mattix.andamp.widget.WidgetSettings

/**
 * Winamp's Preferences, as a plain Material screen. Not skinned: Winamp's own preferences were a
 * native dialog, and ENGINEERING.md keeps modern chrome out of the 275px virtual space.
 */
@Composable
fun PreferencesScreen(
    vm: WinampViewModel,
    access: LibraryAccess,
    onRequestAccess: () -> Unit,
    onClose: () -> Unit,
    /** A page to open straight into, named from outside; null is the list. */
    startAt: String? = null,
    onArrived: () -> Unit = {},
) {
    val context = LocalContext.current
    // the widget's settings, which live on disk because the widget is drawn from a broadcast when
    // this screen is not running
    var widgetSettings by remember { mutableStateOf(WidgetSettings.read(context)) }
    // a sign-in can finish where this screen cannot see it, so what this device holds is read again
    // when the screen opens. A return from another app is covered by SourceOps.reconcileWhileShown,
    // which runs under every screen
    LaunchedEffect(Unit) { vm.sourceOps.reconcile(context) }
    // on leaving, whatever was waiting on a sign-in here stops waiting. A configuration change
    // takes the screen down and puts it straight back, which is not leaving
    DisposableEffect(vm) {
        onDispose {
            if ((context as? Activity)?.isChangingConfigurations != true) vm.sourceOps.preferencesClosed()
        }
    }
    // the launcher is created here, not in the page, which stays free of the ViewModel so it can be
    // tested. A .lua has no MIME type of its own, so the filter is */*.
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) context.contentResolver.openInputStream(uri)?.let { vm.pluginOps.install(it) }
        }
    // the overlay permission is granted on a system screen that returns no result, so the
    // permission is checked again on return
    val overlayPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            vm.overlayOps.recheck()
        }
    PreferencesPage(
        access = access,
        dsp = vm.dspOps,
        plugins = vm.pluginOps,
        onRequestAccess = onRequestAccess,
        onPickPlugin = { picker.launch(arrayOf("*/*")) },
        arrival = PrefsArrival(startAt, onArrived),
        onClose = onClose,
        sections =
            PrefsSections(
                visualizer = visualizerPrefsOf(vm, onManagePresets = { vm.state.presetManagerRequested = true }),
                peaks = { vm.audioTap?.peaks ?: nl.mattix.andamp.core.playback.PeakReading.NONE },
                overlay =
                    OverlayPrefs(
                        offered = vm.overlayOps.supported,
                        wanted = vm.overlayOps.gate.wanted,
                        permitted = vm.overlayOps.gate.permitted,
                        onWant = { on ->
                            vm.overlayOps.askFor(
                                on = on,
                                prompt = { vm.state.prompt = it },
                                openSettings = { overlayPermission.launch(overlaySettingsIntent(context)) },
                            )
                        },
                        onGrant = { overlayPermission.launch(overlaySettingsIntent(context)) },
                        summary = vm.overlayOps.summary(),
                        systemCap = overlayCapOf(context),
                    ),
                tapAssist =
                    TapAssistPrefs(
                        enabled = vm.state.tapAssist,
                        // the windows follow the store
                        onEnabled = { on -> vm.tapAssistStore.enabled = on },
                    ),
                shade =
                    ShadePrefs(
                        shadeEnabled = vm.state.shadeEnabled,
                        // the windows follow the store
                        onShadeEnabled = { on -> vm.shadeStore.enabled = on },
                    ),
                palette =
                    PalettePrefs(
                        enabled = vm.paletteStore.enabled,
                        offered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        onEnabled = { on ->
                            vm.paletteStore.enabled = on
                            // the same path a wallpaper change takes: the base skin is rebuilt, and
                            // whatever is worn follows it
                            vm.skinOps.paletteChanged()
                        },
                    ),
                volume =
                    VolumePrefs(
                        mode = vm.volumeModes.mode,
                        offered = vm.canAttenuate,
                        onMode = vm::setVolumeMode,
                    ),
                sources =
                    SourcesPrefs(
                        phone =
                            PhonePrefs(
                                stats = vm.phoneLibrary.stats,
                                scanning = vm.phoneLibrary.scanning,
                                lastScan = vm.phoneLibrary.lastScan,
                                onScan = vm.phoneLibrary::scan,
                                onCount = vm.phoneLibrary::refresh,
                            ),
                        extras = PackSources.found,
                        signedIn =
                            vm.state.reach
                                ?.signedIn
                                .orEmpty(),
                        onSignedIn = vm.sourceOps::signedIn,
                        onSignedOut = vm.sourceOps::signedOut,
                        // a screen the source owns, started from here; what it does to the account
                        // is read back by the reconcile above
                        openSettings = { intent -> context.startActivity(intent) },
                        skins = vm.state.skinEntries,
                        skinOf = vm.sourceSkinOps.skins::of,
                        onSkin = vm.sourceSkinOps::choose,
                    ),
                widget =
                    WidgetPrefs(
                        settings = widgetSettings,
                        onSettings = { chosen ->
                            widgetSettings = chosen
                            WidgetSettings.update(context, chosen)
                        },
                    ),
            ),
    )
}

/** How far a page starts off to the side: a sixth of its width, like the platform. */
private const val ASIDE = 6

/**
 * From this width the list stays on screen beside the page it opened: Material's breakpoint for an
 * expanded window.
 */
private val BESIDE = 840.dp

/** The list's width when it sits beside a page. */
private val LIST_W = 360.dp

/** The most a page's content grows in width; past this the page keeps its width. */
private val PAGE_MAX = 720.dp

/**
 * The root row whose page is showing beside the list, for the list to mark; null when the list is a
 * page of its own.
 */
internal val LocalShowingPage = compositionLocalOf<String?> { null }

/**
 * The widget's page, by the name it is asked for from outside. The widget's menu opens Preferences
 * on it from another activity, through an intent, and both ends use this constant.
 */
internal const val WIDGET_PAGE = "widget"

/** Preferences > Plug-ins, which a .lua handed over by another app opens on. */
internal const val PLUGINS_PAGE = "plugins"

/** The blocks the page shows, one per area it has settings for, grouped in one holder. */
data class PrefsSections(
    val visualizer: VisualizerPrefs = VisualizerPrefs(),
    val overlay: OverlayPrefs = OverlayPrefs(),
    val shade: ShadePrefs = ShadePrefs(),
    val tapAssist: TapAssistPrefs = TapAssistPrefs(),
    val palette: PalettePrefs = PalettePrefs(),
    val volume: VolumePrefs = VolumePrefs(),
    val widget: WidgetPrefs = WidgetPrefs(),
    val sources: SourcesPrefs = SourcesPrefs(),
    /** What the output stage is doing to peaks, for the limiter's card; asked while the rack is on screen. */
    val peaks: () -> nl.mattix.andamp.core.playback.PeakReading = { nl.mattix.andamp.core.playback.PeakReading.NONE },
)

/** A page asked for by name from outside, and the callback that reports the request was used. */
data class PrefsArrival(
    val page: String? = null,
    val onArrived: () -> Unit = {},
)

/**
 * A page of preferences: the root list, or one of the pages it opens.
 *
 * Winamp's preferences were a tree beside a pane. A phone gets the same shape one page at a time:
 * the root names the areas, and an area fills the screen when it is opened.
 */
private enum class PrefsPage(
    val title: String,
    val route: String,
) {
    PHONE("This Phone", PHONE_PAGE),
    DSP("Effects", "effects"),
    INSTALLED("Plug-ins", PLUGINS_PAGE),
    VISUALIZER("Visualizer", "visualizer"),
    PLAYER("Player", "player"),
    WIDGET("Home screen widget", WIDGET_PAGE),
    ABOUT("About Andamp", "about"),
    LICENCES("Open-source licenses", "licences"),
    ;

    companion object {
        /** The list itself. */
        const val ROOT = "root"

        fun of(route: String?) = entries.firstOrNull { it.route == route }
    }
}

/** The page itself, given only what it shows: no ViewModel, so it can be tested. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferencesPage(
    access: LibraryAccess,
    dsp: DspOps,
    plugins: PluginOps,
    onRequestAccess: () -> Unit,
    onPickPlugin: () -> Unit,
    onClose: () -> Unit,
    sections: PrefsSections = PrefsSections(),
    arrival: PrefsArrival = PrefsArrival(),
) {
    // one snackbar host for every page
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // the phone's row and its page both say how much music there is, so it is counted here for
    // both. Keyed on access: without access there is nothing to count, and on a first visit access
    // is given on the phone's own page, which stays open while the prompt answers
    LaunchedEffect(access) { if (access == LibraryAccess.GRANTED) sections.sources.phone.onCount() }
    // the pages are a stack, so a nav host owns them: the route, the transitions and the back
    // gesture
    val pages = rememberNavController()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Winamp's shape where there is room for it: the list stays beside the page it opened, and
        // a row replaces the page instead of stacking on it. A phone keeps one page at a time.
        val beside = maxWidth >= BESIDE
        val open: (String) -> Unit = { route ->
            if (beside) {
                pages.navigate(route) {
                    popUpTo(pages.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            } else {
                pages.navigate(route)
            }
        }
        // the caller named a page: it is opened once, so a second visit opens the list
        LaunchedEffect(arrival.page) {
            val page = arrival.page ?: return@LaunchedEffect
            val known =
                PrefsPage.of(page)?.route
                    ?: page.takeIf { route -> sections.sources.extras.any { sourceRoute(it.source) == route } }
            if (known != null) {
                // the host is laid out inside the scaffold, so on the first pass it
                // may not have its pages yet; the list landing on its stack says it has
                pages.currentBackStackEntryFlow.first()
                open(known)
            }
            // called last, and not before the wait above: it clears the name the caller set, which
            // is this effect's own key, and an effect whose key changes is cancelled before it has
            // opened the page
            arrival.onArrived()
        }
        val here =
            pages
                .currentBackStackEntryAsState()
                .value
                ?.destination
                ?.route
        // beside the list there is always a page: the first row's, until another is chosen
        LaunchedEffect(beside, here, arrival.page) {
            if (beside && here == PrefsPage.ROOT && arrival.page == null) open(PrefsPage.PHONE.route)
        }
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbars) },
                topBar = {
                    // beside the list, only a page opened from inside another
                    // has somewhere to go back to
                    PrefsTopBar(
                        title =
                            PrefsPage.of(here)?.title
                                ?: sections.sources.extras
                                    .firstOrNull { sourceRoute(it.source) == here }
                                    ?.source
                                    ?.label
                                ?: "Preferences",
                        deeper = if (beside) pages.previousBackStackEntry != null else here != PrefsPage.ROOT,
                        onBack = { pages.popBackStack() },
                        onClose = onClose,
                    )
                },
            ) { padding ->
                // a page enters from the side it goes deeper towards and leaves back the way it
                // came
                val host: @Composable (Modifier) -> Unit = { modifier ->
                    NavHost(
                        pages,
                        startDestination = PrefsPage.ROOT,
                        modifier = modifier,
                        enterTransition = { slideInHorizontally { it / ASIDE } + fadeIn() },
                        exitTransition = { slideOutHorizontally { -it / ASIDE } + fadeOut() },
                        popEnterTransition = { slideInHorizontally { -it / ASIDE } + fadeIn() },
                        popExitTransition = { slideOutHorizontally { it / ASIDE } + fadeOut() },
                    ) {
                        page(
                            PrefsPage.ROOT,
                        ) {
                            // beside the list, the list is not a page as well
                            if (!beside) Root(access, dsp, sections, open)
                        }
                        page(PrefsPage.PHONE.route) { PhonePage(access, onRequestAccess, sections.sources.phone) }
                        // one page per pack the listener installed, each drawn by the source
                        sections.sources.extras.forEach { extra ->
                            page(sourceRoute(extra.source)) {
                                ExtraSourcePage(extra, sections.sources)
                            }
                        }
                        page(PrefsPage.DSP.route) {
                            DspRack(dsp, plugins, snackbars, scope, sections.peaks) { pages.navigate(PrefsPage.INSTALLED.route) }
                        }
                        page(PrefsPage.INSTALLED.route) { InstalledPlugins(plugins, dsp, snackbars, scope, onPickPlugin) }
                        page(PrefsPage.VISUALIZER.route) { VisualizerSettings(sections.visualizer) }
                        page(PrefsPage.PLAYER.route) { PlayerPage(sections) }
                        page(PrefsPage.WIDGET.route) { WidgetRows(sections.widget) }
                        page(PrefsPage.ABOUT.route) { AboutPage(pages::navigate) }
                        page(PrefsPage.LICENCES.route) { LicencesPage() }
                    }
                }
                if (beside) {
                    Row(Modifier.padding(padding).fillMaxSize()) {
                        CompositionLocalProvider(LocalShowingPage provides rootRowOf(here)) {
                            Column(
                                Modifier
                                    .width(LIST_W)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp),
                            ) {
                                Root(access, dsp, sections, open)
                                Spacer(Modifier.height(32.dp))
                            }
                        }
                        VerticalDivider()
                        host(Modifier.weight(1f))
                    }
                } else {
                    host(Modifier.padding(padding))
                }
            }
        }
    }
}

/**
 * One page of preferences, scrolling by itself. Each keeps its own scroll position by being its own
 * destination.
 */
private fun NavGraphBuilder.page(
    route: String,
    content: @Composable ColumnScope.() -> Unit,
) = composable(route) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .widthIn(max = PAGE_MAX)
            .padding(horizontal = 16.dp),
    ) {
        content()
        Spacer(Modifier.height(32.dp))
    }
}

/** The list's row a page belongs to: a page opened from inside another is marked as that one. */
private fun rootRowOf(route: String?): String? =
    when (route) {
        PrefsPage.INSTALLED.route -> PrefsPage.DSP.route
        PrefsPage.LICENCES.route -> PrefsPage.ABOUT.route
        else -> route
    }

@Composable
private fun Root(
    access: LibraryAccess,
    dsp: DspOps,
    sections: PrefsSections,
    onOpen: (String) -> Unit,
) {
    // the sources come first; the permission is on the phone's page, and its row says when it is
    // wanted
    SourcesSection(
        access = access,
        sources = sections.sources,
        onPhone = { onOpen(PrefsPage.PHONE.route) },
        onSource = { onOpen(sourceRoute(it)) },
    )
    // three groups under the sources: Sound, Look & feel, and the app itself. An area with several
    // settings is a page one tap away; a single switch stays here
    Divided()
    Section("Sound")
    // one entry: managing installed plug-ins is a button on the Effects page
    PageRow(
        page = PrefsPage.DSP,
        summary = dsp.summary(),
        onOpen = { onOpen(it.route) },
    )
    VolumeRow(sections.volume)

    Divided()
    Section("Look & feel")
    PageRow(
        page = PrefsPage.PLAYER,
        summary = sections.playerSummary(),
        onOpen = { onOpen(it.route) },
    )
    PageRow(
        page = PrefsPage.VISUALIZER,
        summary = sections.visualizer.summary(),
        onOpen = { onOpen(it.route) },
    )
    PageRow(
        page = PrefsPage.WIDGET,
        summary = sections.widget.summary(),
        onOpen = { onOpen(it.route) },
    )

    Divided()
    PageRow(
        page = PrefsPage.ABOUT,
        summary = "Version ${BuildConfig.VERSION_NAME} · open-source licenses",
        onOpen = { onOpen(it.route) },
    )
}

/** Preferences > About Andamp: what this is, and what it is built on. */
@Composable
private fun AboutPage(onOpen: (String) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(
        "Andamp ${BuildConfig.VERSION_NAME} — a Winamp 2.8 replica, not affiliated with Winamp or its owners.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    PageRow(
        page = PrefsPage.LICENCES,
        summary = "${Notices.ALL.size} projects Andamp is built on",
        onOpen = { onOpen(it.route) },
    )
}

@Composable
private fun Divided() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
}

/** A row that opens one of this screen's fixed pages; see [NavRow]. */
@Composable
private fun PageRow(
    page: PrefsPage,
    summary: String,
    onOpen: (PrefsPage) -> Unit,
) = NavRow(page.title, summary, tag = "prefs.open.${page.name.lowercase()}", onClick = { onOpen(page) }, route = page.route)

@Composable
internal fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
}

internal fun android.content.Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
