// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.mattix.andamp.state.online.MuseumHttp
import nl.mattix.andamp.state.online.OnlineSkin
import nl.mattix.andamp.state.online.OnlineSkins
import nl.mattix.andamp.state.online.SkinCatalog
import nl.mattix.andamp.ui.pulledAside
import nl.mattix.andamp.ui.rememberBackPull

/**
 * The Winamp Skin Museum, in the app.
 *
 * The browser is a virtual list: every position in the museum is a row from the start, the pages
 * behind the visible rows are fetched as they are needed, and the handle beside the list runs the
 * whole catalog, not only the part that is loaded.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun OnlineSkinsScreen(
    online: OnlineSkins,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalog = online.catalog
    val ops = online.ops
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gridState =
        remember(catalog) {
            // a different list starts at its own top; the museum resumes where it was left
            if (catalog === online.museum) {
                LazyGridState(online.firstVisibleRow, online.firstVisibleOffset)
            } else {
                LazyGridState()
            }
        }
    var opened by remember { mutableStateOf<OnlineSkin?>(null) }
    // what the handle is pointing at while a finger is on it
    var steering by remember { mutableStateOf<Int?>(null) }
    var filtering by remember { mutableStateOf(false) }
    // one scroll at a time: a drag of the handle asks for a new row every frame, and each replaces
    // the one before
    val scrolling = remember { mutableStateOf<Job?>(null) }
    val scrollTo: (Int) -> Unit = { at ->
        scrolling.value?.cancel()
        scrolling.value = scope.launch { gridState.scrollToItem(at) }
    }

    BrowserEffects(online, catalog, gridState, snackbars)

    // one back handler: it closes the open skin first, then the museum
    val pull = rememberBackPull { if (opened != null) opened = null else onClose() }
    // the skin being looked at, kept until the viewer has finished leaving: when back clears
    // [opened], the viewer still needs something to draw while it fades out
    var leaving by remember { mutableStateOf<OnlineSkin?>(null) }
    LaunchedEffect(opened) { opened?.let { leaving = it } }
    // Which of the two the back gesture is about has to hold for the whole gesture, so the museum's
    // pull depends on the skin that is leaving, not the one that is open. With a skin open the
    // gesture belongs to the skin.
    Surface(modifier.fillMaxSize().pulledAside(pull, drawn = leaving == null)) {
        SharedTransitionLayout {
            // where the open skin's tile is, written by that tile and read by
            // the viewer while a gesture carries the skin back to it
            val home = remember { mutableStateOf<Rect?>(null) }
            CompositionLocalProvider(
                LocalSkinTransition provides this,
                LocalOpenedSkin provides opened?.md5,
                LocalSkinHome provides home,
            ) {
                // the museum stays underneath the viewer, blurred. Blur is a render effect, so on
                // Android 11 and older it does nothing and the viewer's wash alone dims the list.
                val softening by animateDpAsState(
                    targetValue = if (opened == null) 0.dp else BLUR.dp,
                    animationSpec = tween(HERO_MS),
                    label = "blur",
                )
                Browser(
                    online = online,
                    catalog = catalog,
                    gridState = gridState,
                    snackbars = snackbars,
                    filtering = filtering,
                    onFiltering = { filtering = it },
                    steering = steering,
                    onSteering = { steering = it },
                    scrollTo = scrollTo,
                    onOpen = { opened = it },
                    onClose = onClose,
                    modifier = Modifier.blur(softening),
                )
                // the viewer has to know when the shared element has stopped moving: the player
                // draws in whole virtual pixels, and the transition passes through every fraction
                // in between
                val showing = remember { MutableTransitionState(false) }
                showing.targetState = opened != null
                // the pull is reset only once the viewer has gone, so the skin does not return to
                // full size mid-transition
                LaunchedEffect(showing.isIdle, showing.currentState) {
                    if (showing.isIdle && !showing.currentState) {
                        pull.rest()
                        leaving = null
                    }
                }
                AnimatedVisibility(
                    visibleState = showing,
                    enter = fadeIn(tween(HERO_MS)),
                    exit = fadeOut(tween(HERO_MS)),
                ) {
                    CompositionLocalProvider(LocalSkinAppearance provides this@AnimatedVisibility) {
                        leaving?.let { skin ->
                            LaunchedEffect(skin.md5) { online.previews?.want(skin) }
                            SkinViewer(
                                pull = pull,
                                // a back gesture under way counts as movement too
                                settled = showing.isIdle && showing.currentState && pull.progress == 0f,
                                skin = skin,
                                live = online.previews?.of(skin.md5),
                                installed = ops.isInstalled(skin.md5),
                                busy = ops.isBusy(skin.md5),
                                onInstall = { ops.install(skin) },
                                onUninstall = { ops.uninstall(skin) },
                                onClose = { opened = null },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The museum itself: the bar, the grid and the handle beside it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // the whole browser, and what it does
private fun Browser(
    modifier: Modifier = Modifier,
    online: OnlineSkins,
    catalog: SkinCatalog,
    gridState: LazyGridState,
    snackbars: SnackbarHostState,
    filtering: Boolean,
    onFiltering: (Boolean) -> Unit,
    steering: Int?,
    onSteering: (Int?) -> Unit,
    scrollTo: (Int) -> Unit,
    onOpen: (OnlineSkin) -> Unit,
    onClose: () -> Unit,
) {
    val ops = online.ops
    Box(modifier) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbars) },
            topBar = {
                BrowserBar(
                    online = online,
                    total = catalog.total,
                    filtering = filtering,
                    onFiltering = onFiltering,
                    onClose = onClose,
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    catalog.loadingFirstPage -> {
                        Loading()
                    }

                    catalog.total <= 0 && catalog.failed.isNotEmpty() -> {
                        Unreachable(catalog.failed.values.first()) { catalog.retry(0) }
                    }

                    else -> {
                        SkinGrid(
                            catalog = catalog,
                            gridState = gridState,
                            wide = online.wide,
                            isInstalled = ops::isInstalled,
                            isBusy = ops::isBusy,
                            live = { md5 -> online.previews?.of(md5) },
                            onInstall = ops::install,
                            onUninstall = ops::uninstall,
                            onOpen = onOpen,
                        )
                    }
                }
                // the sheet slides out from under the bar as one piece. AnimatedVisibility's
                // default expand would squash the rows inside on the way in
                AnimatedVisibility(
                    filtering,
                    enter = fadeIn(tween(SHEET_MS)),
                    exit = fadeOut(tween(SHEET_MS)),
                    modifier = Modifier.matchParentSize(),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SHEET_SHADE))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { onFiltering(false) },
                            ).testTag("$TAG.filters.away"),
                    )
                }
                AnimatedVisibility(
                    filtering,
                    enter = slideInVertically(tween(SHEET_MS)) { -it } + fadeIn(tween(SHEET_MS)),
                    exit = slideOutVertically(tween(SHEET_MS)) { -it } + fadeOut(tween(SHEET_MS)),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    SkinFilters(online)
                }
                // the handle appears when the list moves and goes a moment after it settles
                val moving = gridState.isScrollInProgress || steering != null
                var restless by remember { mutableStateOf(false) }
                LaunchedEffect(moving) {
                    if (moving) {
                        restless = true
                    } else {
                        delay(HANDLE_LINGER_MS)
                        restless = false
                    }
                }
                if (catalog.total > 0) {
                    AnimatedVisibility(
                        visible = restless,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        CatalogueScrollbar(
                            total = catalog.total,
                            gridState = gridState,
                            onSteering = onSteering,
                            onScrollTo = scrollTo,
                        )
                    }
                    steering?.let { at -> Steering(at, catalog.total, Modifier.align(Alignment.CenterEnd)) }
                }
            }
        }
    }
}

/**
 * The browser's side effects: fetching what is on screen, saying what went wrong, and remembering
 * the place when it closes.
 */
@Composable
private fun BrowserEffects(
    online: OnlineSkins,
    catalog: SkinCatalog,
    gridState: LazyGridState,
    snackbars: SnackbarHostState,
) {
    val ops = online.ops
    // what is on screen decides what is fetched: the museum is asked only for the pages behind the
    // visible rows
    LaunchedEffect(gridState, catalog) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo
                .firstOrNull()
                ?.index to
                gridState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index
        }.collect { (first, last) ->
            if (first != null && last != null) catalog.show(first..last)
        }
    }
    LaunchedEffect(catalog) { catalog.show(0..0) }
    // the museum's host names are resolved now, before the first picture needs one
    LaunchedEffect(Unit) { MuseumHttp.warmNames() }
    // one to a screen: the skin on show is fetched for a live preview. The grid of small tiles
    // stays pictures, so it holds no skins in memory
    LaunchedEffect(online.wide, gridState, catalog) {
        if (!online.wide) return@LaunchedEffect
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { at ->
            catalog.skins[at]?.let { online.previews?.want(it) }
        }
    }
    PrefetchThumbnails(catalog, gridState)
    LaunchedEffect(ops.message) {
        ops.message?.let {
            snackbars.showSnackbar(it)
            ops.message = null
        }
    }
    // the skins fetched for previews live as long as the browser does
    DisposableEffect(online) {
        onDispose { online.previews?.forgetAll() }
    }
    DisposableEffect(gridState, catalog) {
        onDispose {
            if (catalog === online.museum) {
                online.remember(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // a title and the controls beside it
private fun BrowserBar(
    online: OnlineSkins,
    total: Int,
    filtering: Boolean,
    onFiltering: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    TopAppBar(
        title = {
            if (online.searchOpen) {
                SearchField(online, total)
            } else {
                Column {
                    Text("Skin Museum")
                    Text(
                        if (total >
                            0
                        ) {
                            "${total.asCount()} ${if (online.query.isBlank()) "in the museum" else "found"}"
                        } else {
                            "from the Winamp Skin Museum"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose, modifier = Modifier.testTag("$TAG.back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
            }
        },
        actions = {
            IconButton(
                onClick = {
                    online.searchOpen = !online.searchOpen
                    if (!online.searchOpen) online.search("")
                },
                modifier = Modifier.testTag("$TAG.search"),
            ) {
                Icon(
                    if (online.searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (online.searchOpen) "Stop searching" else "Search the museum",
                )
            }
            IconButton(onClick = { online.wide = !online.wide }, modifier = Modifier.testTag("$TAG.layout")) {
                Icon(
                    if (online.wide) Icons.Filled.GridView else Icons.Filled.ViewAgenda,
                    contentDescription = if (online.wide) "Show them as tiles" else "Show them one at a time",
                )
            }
            IconButton(onClick = { onFiltering(!filtering) }, modifier = Modifier.testTag("$TAG.tune")) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = "Order and filters",
                    tint =
                        if (filtering) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        },
    )
}

/**
 * The search field. It asks the museum, not the loaded part of the list, and waits [TYPING_MS]
 * after the last keystroke before asking.
 */
@Composable
private fun SearchField(
    online: OnlineSkins,
    total: Int,
) {
    var typed by remember { mutableStateOf(online.query) }
    // opening the field is asking to type in it
    val typing = remember { FocusRequester() }
    LaunchedEffect(Unit) { typing.requestFocus() }
    LaunchedEffect(typed) {
        delay(TYPING_MS)
        online.search(typed.trim())
    }
    TextField(
        value = typed,
        onValueChange = { typed = it },
        singleLine = true,
        placeholder = { Text("Search the museum") },
        // the count sits in the field, since the field has taken the title's place
        suffix = { if (online.query.isNotBlank() && total >= 0) Text("$total found") },
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(typing)
                .testTag("$TAG.query"),
    )
}

@Composable
private fun Steering(
    at: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        "${at + 1} / $total",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier =
            modifier
                .padding(end = BAR_W.dp)
                .clip(RoundedCornerShape(GAP.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = GAP.dp, vertical = TIGHT.dp),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Fetching the museum…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Unreachable(
    why: String,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(GAP.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("The museum is out of reach", style = MaterialTheme.typography.titleMedium)
        Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry, modifier = Modifier.testTag("$TAG.retry")) { Text("Try again") }
    }
}

/**
 * A handle for the whole museum, sized for a finger. It runs the catalog end to end, so any
 * position is one drag away.
 */
@Composable
private fun CatalogueScrollbar(
    total: Int,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    onSteering: (at: Int?) -> Unit,
    onScrollTo: (at: Int) -> Unit,
) {
    val density = LocalDensity.current
    var trackHeight by remember { mutableStateOf(1f) }
    // how far down the catalog the first visible item is
    val progress = if (total <= 1) 0f else gridState.firstVisibleItemIndex.toFloat() / (total - 1)
    Box(
        modifier
            .fillMaxHeight()
            .width(BAR_W.dp)
            .padding(vertical = GAP.dp)
            .testTag("$TAG.scrollbar")
            .pointerInput(total, trackHeight) {
                detectTapGestures(
                    onPress = { at ->
                        val to = rowAt(at.y, size.height.toFloat(), total)
                        onSteering(to)
                        onScrollTo(to)
                        tryAwaitRelease()
                        onSteering(null)
                    },
                )
            }.pointerInput(total) {
                detectVerticalDragGestures(
                    onDragEnd = { onSteering(null) },
                    onDragCancel = { onSteering(null) },
                ) { change, _ ->
                    val to = rowAt(change.position.y, size.height.toFloat(), total)
                    onSteering(to)
                    onScrollTo(to)
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(TRACK_W.dp)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(TRACK_W.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = (progress * (trackHeight - HANDLE_H)).dp.coerceAtLeast(0.dp))
                .size(HANDLE_W.dp, HANDLE_H.dp)
                .clip(RoundedCornerShape(HANDLE_W.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size -> trackHeight = with(density) { size.height.toDp().value } },
        )
    }
}

/** Which position in the museum a touch at [y] on a track of [height] px points at. */
private fun rowAt(
    y: Float,
    height: Float,
    total: Int,
): Int = ((y / height).coerceIn(0f, 1f) * (total - 1)).toInt()

internal const val TAG = "online"

// wide enough to hit without aiming: the tiles sit under this strip, and a drag that misses it
// opens a skin
private const val BAR_W = 44
private const val TRACK_W = 4
private const val HANDLE_W = 12
private const val HANDLE_H = 56

/** How long the filter sheet takes to slide in or out. */
private const val SHEET_MS = 220

/** How far the list behind the sheet is dimmed. */
private const val SHEET_SHADE = 0.5f

/** How long the handle stays after the list stops, so a pause does not blink it away. */
private const val HANDLE_LINGER_MS = 1_200L

/** How long the transition between the list and the viewer takes. */
private const val HERO_MS = 320

/** How far the museum is softened behind an open skin. */
private const val BLUR = 24

/** How long typing must pause before the query is sent. */
private const val TYPING_MS = 350L
private const val TIGHT = 8
