// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.model.TrackKey
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.displayName
import nl.mattix.andamp.core.model.durationSec
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.overlay.OverlayOps
import nl.mattix.andamp.state.overlay.OverlayStore
import nl.mattix.andamp.ui.menu.promptFor
import kotlin.math.roundToInt

/**
 * Where the app is wired together.
 *
 * It builds the collaborators (manual DI, so this is the one place that names them),
 * mirrors the backend's StateFlow into [WinampState]'s snapshot fields through a single
 * collector, and forwards user intents to the [PlayerFacade]. It holds no playback logic.
 *
 * The animation clocks are in `ui/UiClocks.kt`, reading and writing disk is in
 * [PersistenceOps], and the playlist's file operations are in [PlaylistFileOps].
 */
class WinampViewModel
    @JvmOverloads
    constructor(
        app: Application,
        // the real backend outlives this ViewModel (background playback) and is built by
        // PlaybackRoot, which the widget also uses; tests inject a scoped mock
        createBackend: (CoroutineScope) -> PlaybackBackend = { PlaybackRoot.backend(app) },
        presetStore: EqPresetStore = PrefsEqPresetStore(app),
        skinLibrary: SkinLibrary = SkinLibrary(app),
        private val playlistStore: PlaylistStore = PlaylistStore(app),
        private val transportStore: TransportStore = TransportStore(app),
        private val windowStore: WindowStore = WindowStore(app),
        browseSource: (MusicSource) -> BrowseSource = { Libraries.of(app, it) },
        playlistLibrary: PlaylistLibrary = PlaylistLibrary(java.io.File(app.filesDir, "playlists")),
        stationStore: StationStore = StationStore(java.io.File(app.filesDir, "stations.m3u")),
        bookmarkStore: BookmarkStore = BookmarkStore(java.io.File(app.filesDir, "bookmarks.m3u")),
    ) : AndroidViewModel(app),
        nl.mattix.andamp.ui.window.WindowControls {
        override val state = WinampState()

        private val backend = createBackend(viewModelScope)
        private val facade = PlayerFacade(backend)

        /** Not a constructor parameter: tests reach it through the prefs the app writes. */
        private val visualsStore = VisualsStore(app)
        private val library = MediaStoreAudio(app)
        private val mediaFiles = MediaFiles(app, library)
        val playlistOps = PlaylistOps(state, facade)
        val volumeModes = VolumeModeStore(app)

        /** Whether windows may be collapsed to their title bar; see [ShadeStore]. */
        val shadeStore = ShadeStore(app)

        /** Whether the live skins follow the wallpaper; see [PaletteStore]. */
        val paletteStore = PaletteStore(app)

        /** Whether a hold opens the magnifier; see [TapAssistStore]. */
        val tapAssistStore = TapAssistStore(app)

        /** Whether the welcome screen has been shown; see [WelcomeStore]. */
        val welcome = WelcomeStore(app)

        /**
         * Whether the player fills the screen; see [DoubleSizeStore]. Double Size and Always
         * On Top are on one at a time: switching this on switches [overlayOps] off, and the
         * other way round there.
         */
        val doubleSize: DoubleSizeStore =
            DoubleSizeStore(
                app,
                mirror = { state.doubleSize = it },
                onSwitchedOn = { if (overlayOps.gate.wanted) overlayOps.want(false) },
            ) { app.neverUpdated() }
        val eqOps = EqOps(state, facade, presetStore)

        /** Preferences > Effects. */
        val dspOps = DspOps(app, facade)

        /** Non-null when the backend can feed real PCM to the visualizer. */
        val audioTap get() = facade.audioTap

        val skinOps = SkinOps(app, state, viewModelScope, skinLibrary)

        /** The skin each music source asks for, worn while its tracks play; see [SourceSkins]. */
        val sourceSkinOps = SourceSkinOps(SourceSkins(app), skinOps::wear)

        val presetOps = PresetOps(app, state, viewModelScope)

        /**
         * The floating player. The permission is read through [Settings.canDrawOverlays]
         * every time, because it can be withdrawn in system settings while Andamp runs.
         */
        val overlayOps: OverlayOps =
            OverlayOps(
                OverlayStore(app),
                permitted = { Settings.canDrawOverlays(app) },
                // also when it is read as on at launch: an install that has both starts floating
                mirror = { on ->
                    state.alwaysOnTop = on
                    if (on && doubleSize.on) doubleSize.on = false
                },
            )

        /** The museum browser: its catalog, and what has been installed from it. */
        val onlineSkins =
            nl.mattix.andamp.state.online.OnlineSkins
                .of(
                    skinOps,
                    skinLibrary,
                    nl.mattix.andamp.state.online
                        .InstalledSkins(app),
                    viewModelScope,
                )

        init {
            // a skin removed in the Skin Manager also loses its museum record, and a source
            // that asked for it goes back to Default
            skinOps.onRemoved = { id ->
                onlineSkins.ops.forgetId(id)
                sourceSkinOps.skins.forgetSkin(id)
            }
        }

        /** Winamp's bookmarks: the list, with Open and Enqueue. */
        val bookmarkOps =
            BookmarkOps(
                bookmarkStore,
                state,
                viewModelScope,
                queue = { tracks, from -> facade.setQueue(tracks, from) },
                append = { facade.enqueue(it) },
            )

        /** Preferences > Plug-ins: installing and removing .lua files. */
        val pluginOps = PluginOps(app, facade, dspOps, viewModelScope)

        /** Which source the library window shows; see [LibrarySources]. */
        val librarySources =
            LibrarySources(
                LibrarySourceStore(app),
                // a source whose app is not installed is left out, so its rows are labeled
                // missing and not signed out
                MusicSource.present(PackSources.found.filter { it.reachable(app) }.map { it.source }),
                PackSources.found
                    .filter { it.reachable(app) && it.looksSignedIn(app) }
                    .map { it.source }
                    .toSet(),
                onReach = { state.reach = it },
            )

        /** The media library window: artists, albums, lists and radio. */
        val libraryOps =
            LibraryOps(
                { browseSource(librarySources.showing) },
                facade,
                state,
                viewModelScope,
                playlistLibrary,
                stationStore,
                nameOf = { librarySources.showing.label },
            )

        /** Picking a library, and what a sign-in or a sign-out changes; see [SourceOps]. */
        val sourceOps = SourceOps(state, librarySources, libraryOps)

        /**
         * The prompts for rows that cannot play; see [SourcePrompts]. The pages come from
         * the stored playlist, so a source whose app was uninstalled still has an address.
         */
        private val sourcePrompts =
            SourcePrompts(
                pages = { state.sourcePages },
                elsewhere = nl.mattix.andamp.ui.prefs.MORE_SOURCES_URL,
                open = { page -> app.openOutside(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(page))) },
                settings = { source ->
                    PackSources.found
                        .firstOrNull { it.source == source }
                        ?.settings(app)
                        ?.let(app::openOutside)
                },
            )

        /** A song on the phone pressed while music access is off; see [LibraryPrompt]. */
        private val libraryPrompt by lazy {
            LibraryPrompt(canRead = playlistFiles::canReadLibrary)
        }

        init {
            // a source that reports account changes relabels its playlist rows when
            // somebody signs in or out
            sourceOps.follow(app, viewModelScope)
            // the source pages recorded in the stored playlist, read off the main thread
            viewModelScope.launch {
                val known = withContext(Dispatchers.IO) { playlistStore.pagesKnown() }
                if (known.isNotEmpty()) state.sourcePages = known
            }
        }

        /** Preferences > Music sources > This Phone: how much music, and a scan; see [PhoneLibrary]. */
        val phoneLibrary = PhoneLibrary(app, viewModelScope)

        // the home screen widget's mirror of the player is started by PlaybackRoot, since
        // the player outlives every window

        /** What this session leaves for the next one: settings, queue, visuals, windows. */
        val persistence =
            PersistenceOps(state, facade, viewModelScope, playlistStore, transportStore, windowStore, visualsStore)

        /** Winamp's Alt+3. Not a constructor parameter: tests exercise [TrackInfoOps] directly. */
        val trackInfoOps = TrackInfoOps(state, FileInfoSource(app, liveSampleRateKhz = state::liveSampleRateKhz), viewModelScope)

        /** LIST > SAVE/LOAD LIST, which need a document picker and the file system. */
        val playlistFiles =
            PlaylistFileOps(app, state, facade, playlistStore, viewModelScope, library, mediaFiles, playlistLibrary)

        /** The skin every canvas draws with; null until the bundled one decodes. */
        val skin: Skin? get() = skinOps.skin

        init {
            // a plug-in's script finishes loading on a worker, so the list of effects can
            // change after the page has been drawn
            viewModelScope.launch {
                facade.effectsFlow.drop(1).collect {
                    dspOps.refresh()
                    pluginOps.settled()
                }
            }
            // before anything plays, so the slider moves the volume the listener chose
            facade.setVolumeMode(volumeModes.mode)
            // before the first window is drawn; the stores hold the settings and the
            // render state follows them
            state.shadeEnabled = shadeStore.enabled
            state.tapAssist = tapAssistStore.enabled
            viewModelScope.launch {
                snapshotFlow { shadeStore.enabled }.collect { state.shadeEnabled = it }
            }
            viewModelScope.launch {
                snapshotFlow { tapAssistStore.enabled }.collect { state.tapAssist = it }
            }
            // the locked stack's own collapsed windows: read here, and stored as they change
            state.stackShaded = doubleSize.shaded
            viewModelScope.launch {
                snapshotFlow { state.stackShaded }.drop(1).collect { doubleSize.shaded = it }
            }
            // restores what a previous session left: settings, queue, visuals, windows
            persistence.start()
            PlaybackRoot.follow(state)
            skinOps.start()
            presetOps.start()
            dspOps.start() // pushes the stored rack
            // the installed plug-ins reach the backend, and the rack is then decoded again;
            // see PluginOps.start
            pluginOps.start()
            // a playlist restored from disk may hold uris whose grants lapsed; with the
            // library readable those rows can be found again
            playlistFiles.healRestoredEntries()
            // the sequence number of the last notice shown; see BackendState.noticeSeq
            var shownUpTo = 0L
            // single mirror: backend state -> snapshot fields the canvases read
            viewModelScope.launch {
                facade.state.collect { b ->
                    if (b.currentIndex != state.currentIndex) {
                        // a new title scrolls from its start
                        state.marqueeStep = 0
                        state.marqueePixels = 0
                    }
                    if (b.transport == Transport.Stopped && state.transport != Transport.Stopped) {
                        FakeSpectrum.reset(state)
                        state.visFrame++
                    }
                    // the last track that played; it is set while playing and not cleared
                    if (b.transport == Transport.Playing) b.currentTrack?.let { state.lastPlayed = it }
                    state.transport = b.transport
                    state.currentTimeSec = (b.positionMs / 1000).toInt()
                    state.currentIndex = b.currentIndex
                    state.playlist = b.queue
                    state.streamBitrateKbps = b.streamBitrateKbps
                    state.streamSampleRateKhz = b.streamSampleRateKhz
                    state.station = b.station
                    state.connecting = b.connecting
                    state.volume = (b.volumeFraction * 100).roundToInt()
                    state.shuffle = b.shuffle
                    state.repeat = b.repeat
                    // a notice is part of the state, and is shown once per time it is
                    // raised: noticeSeq only grows, so a dialog the listener closed does
                    // not come back for the same notice, and does for the next one
                    val notice = b.notice
                    if (notice != null && b.noticeSeq > shownUpTo) {
                        shownUpTo = b.noticeSeq
                        // a notice follows a press that found nothing to play. Rows that
                        // cannot play are otherwise skipped without a prompt. A prompt
                        // that names the source and where to fix it is preferred; the
                        // notice's own words are used when the source cannot be named
                        val onDone = { state.prompt = null }
                        state.prompt =
                            sourcePrompts.of(state.playlist, state.reach, onDone)
                                ?: promptFor(notice, onDone)
                    }
                }
            }
            // Winamp's AUTO: a track that loads brings its own curve. Keyed by the song,
            // not by the queue entry, because the entry is rewritten while it plays (the
            // decoder patches its bitrate in) and the curve must not be reloaded then.
            viewModelScope.launch {
                facade.state
                    .map { it.currentTrack }
                    .distinctUntilChangedBy { track -> track?.let(TrackKey::of) }
                    .collect { eqOps.trackChanged(it) }
            }
            // a music source may ask for its own skin, worn while the current track is
            // from it. Keyed by source, so consecutive tracks from one source change nothing
            viewModelScope.launch {
                facade.state
                    .map { it.currentTrack?.let(SourceForRows::of) }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect(sourceSkinOps::playingFrom)
            }
        }

        /** Preferences: whose volume the slider moves, stored for the next launch. */
        fun setVolumeMode(mode: nl.mattix.andamp.core.model.VolumeMode) {
            volumeModes.mode = mode
            facade.setVolumeMode(mode)
        }

        /** Whether the backend offers that choice; see [nl.mattix.andamp.core.model.Capabilities.canAttenuate]. */
        val canAttenuate get() = facade.capabilities.canAttenuate

        override fun play() {
            facade.play()
            libraryPrompt.of(state.playlist.getOrNull(state.currentIndex))?.let { state.libraryAsk = it }
        }

        override fun pause() = facade.pause()

        override fun stop() = facade.stop()

        override fun next() = facade.next()

        override fun previous() = facade.previous()

        override fun playTrack(index: Int) {
            val row = state.playlist.getOrNull(index) ?: return
            // the press goes through: the player skips a row nothing can open and plays
            // the next one it can. The prompt says why this row cannot play
            facade.playAt(index)
            if (state.prompt == null) {
                sourcePrompts.forRow(row, state.reach) { state.prompt = null }?.let { state.prompt = it }
            }
            // a song on the phone without the audio permission asks for the permission
            libraryPrompt.of(row)?.let { state.libraryAsk = it }
        }

        /**
         * The audio permission was given after [LibraryPrompt] asked: the pressed song
         * plays, wherever it is in the list by then. Nothing plays if it was removed.
         */
        fun accessGiven(ask: LibraryAsk) {
            playlistFiles.healRestoredEntries()
            val index = state.playlist.indexOfFirst { it.id == ask.then.id && it.uri == ask.then.uri }
            if (index >= 0) facade.playAt(index)
        }

        override fun seekTo(fraction: Float) {
            facade.seekToFraction(fraction)
            state.seekPreview = null
        }

        override fun setVolume(fraction: Float) = facade.setVolume(fraction)

        /**
         * Winamp's balance, -100..+100. The slider position is kept in [WinampState]; the
         * facade is given the pan as -1..1.
         */
        override fun setBalance(balance: Int) {
            state.balance = balance.coerceIn(-BALANCE_RANGE, BALANCE_RANGE)
            facade.setBalance(state.balance / BALANCE_RANGE.toFloat())
        }

        override fun toggleShuffle() = facade.setShuffle(!state.shuffle)

        /** Winamp's Stop with fadeout. */
        fun stopWithFadeout() = facade.stopWithFadeout()

        /** Winamp's Stop after current track. The flag is kept in [WinampState] and the backend acts on it. */
        fun setStopAfterCurrent(on: Boolean) {
            state.stopAfterCurrent = on
            facade.setStopAfterCurrent(on)
        }

        /** Winamp's Ctrl+J: seeks to a typed time. */
        fun promptJumpToTime() {
            val length = state.playlist.getOrNull(state.currentIndex)?.durationSec ?: return
            if (length <= 0) return
            state.namePrompt =
                NamePrompt(title = "Jump to time", initial = "", confirmLabel = "Jump") { typed ->
                    state.namePrompt = null
                    JumpToTime.seconds(typed)?.let { seconds ->
                        seekTo(seconds.coerceIn(0, length).toFloat() / length)
                    }
                }
        }

        /** Winamp's J: the queue in a searchable list; the chosen row plays. */
        fun promptJumpToFile() {
            if (state.playlist.isEmpty()) return
            state.presetPicker =
                PresetPicker(
                    title = "Jump to file",
                    entries =
                        state.playlist.mapIndexed { index, track ->
                            PresetEntry("$index", track.displayName, "${index + 1}")
                        },
                    confirmLabel = "Jump",
                    emptyMessage = "The playlist is empty",
                ) { keys ->
                    keys.firstOrNull()?.toIntOrNull()?.let(::playTrack)
                }
        }

        override fun toggleRepeat() = facade.setRepeat(!state.repeat)

        // the equalizer's and the playlist's intents, forwarded so a window's widgets need
        // only WindowControls
        override fun toggleEq() = eqOps.toggleOn()

        override fun setBand(
            index: Int,
            value: Int,
        ) = eqOps.setBand(index, value)

        override fun setPreamp(value: Int) = eqOps.setPreamp(value)

        override fun resetBands() = eqOps.resetBands()

        override fun presetsMenu(anchor: nl.mattix.andamp.state.MenuAnchor) = eqOps.presetsMenu(anchor)

        override fun scrollBy(
            rows: Int,
            visibleRows: Int,
        ) = playlistOps.scrollBy(rows, visibleRows)

        override fun selectTrack(index: Int) = playlistOps.selectTrack(index)

        override fun selectZero() = playlistOps.selectZero()

        /**
         * Main menu > Exit, as in Winamp: the music stops and nothing of the backend keeps
         * running, the media notification included. It goes through the facade, so this
         * class names no backend host.
         */
        fun exit() {
            // the widget's mirror stops with the player
            PlaybackRoot.stopMirroring()
            facade.exit()
        }

        /** Keeps the backend alive while the floating player is the only window. */
        fun keepPlayingWithoutAWindow() = facade.keepAlive()

        /** Writes an edit still held by the save debounce, which would otherwise be lost with the scope. */
        override fun onCleared() {
            super.onCleared()
            persistence.flush()
        }

        // nothing else is released here: the Media3 backend lives in Media3PlaybackHost so
        // playback survives the activity, and PlaybackService owns teardown

        private companion object {
            /** Winamp's balance slider runs -100..+100 either side of center. */
            const val BALANCE_RANGE = 100
        }
    }
