// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.displayName

/**
 * The commands the plug-in window accepts from the rest of the app. Implemented by
 * whatever is rendering; every call is run on the render thread, which holds the GL
 * context.
 */
interface VisualCommands {
    fun nextPreset()

    fun previousPreset()

    /** Plays the preset at [index] of [WinampState.presetNames]. */
    fun goToPreset(index: Int)
}

/** Winamp's click-to-cycle visualizer modes. */
enum class VisMode {
    Analyzer,
    Oscilloscope,
    Off,
    ;

    fun next(): VisMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Which engine draws the plug-in window: Winamp's "Select plug-in".
 *
 * AVS and MilkDrop were separate plug-ins in Winamp and neither can run the other's
 * presets. AVS is the default (docs/avs-integration.md).
 */
enum class VisPlugin(
    val menuLabel: String,
) {
    /** `:visualizer:avs`: a reimplementation of Winamp's AVS that runs `.avs` presets. */
    Avs("AVS"),

    /** libprojectM via `:visualizer:projectm`, running `.milk` presets. Needs GLES 3. */
    Milkdrop("Milkdrop"),
}

/**
 * Render state for the canvases, one snapshot field per concern, so only the canvases that
 * read a changed field redraw. A single state object would redraw all six windows on every
 * drag or visualizer tick.
 *
 * Playback fields (transport, time, index, playlist, volume, shuffle, repeat) are mirrored
 * from the backend by WinampViewModel's collector. Draw code reads them; interactions go
 * through the ViewModel to the PlayerFacade.
 */
@Stable
class WinampState {
    var transport by mutableStateOf(Transport.Stopped)
    var currentTimeSec by mutableIntStateOf(0)
    var currentIndex by mutableIntStateOf(0)
    var playlist by mutableStateOf(FakeTracks.tracks)

    /**
     * Where each source's app is handed out, by source id, as the saved playlist records
     * it, for a source that is not installed. See [PlaylistCodec.Saved.sources].
     */
    var sourcePages by mutableStateOf(emptyMap<String, String>())

    var volume by mutableIntStateOf(78) // 0..100
    var balance by mutableIntStateOf(0) // -100..100
    var shuffle by mutableStateOf(false)
    var repeat by mutableStateOf(false)

    var eqOn by mutableStateOf(true)
    var eqAuto by mutableStateOf(false)

    // 0..63 with 32 = 0 dB
    val eqBands = mutableStateListOf<Int>().apply { repeat(EQ_BANDS) { add(EqOps.CENTER) } }
    var preamp by mutableIntStateOf(EqOps.CENTER) // 0..63

    var eqVisible: Boolean by openOf(WindowStore.EQ)

    /**
     * Every window's placement, one entry per [WindowStore.WINDOWS] id. The named
     * properties below delegate to this map: `plOffset` is the playlist's `offset`.
     */
    private val placements = WindowPlacementState.forWindows()

    /** One window's placement, for code that loops over the windows. */
    fun placementOf(id: String): WindowPlacementState? = placements[id]

    private fun placement(id: String) = placements.getValue(id)

    private fun offsetOf(id: String) =
        object : kotlin.properties.ReadWriteProperty<Any?, androidx.compose.ui.unit.IntOffset?> {
            override fun getValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
            ) = placement(id).offset

            override fun setValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
                value: androidx.compose.ui.unit.IntOffset?,
            ) {
                placement(id).offset = value
            }
        }

    /** For the two list windows, whose offset defaults to the center. */
    private fun placedOf(id: String) =
        object : kotlin.properties.ReadWriteProperty<Any?, androidx.compose.ui.unit.IntOffset> {
            override fun getValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
            ) = placement(id).offset ?: androidx.compose.ui.unit.IntOffset.Zero

            override fun setValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
                value: androidx.compose.ui.unit.IntOffset,
            ) {
                placement(id).offset = value
            }
        }

    private fun sizeOf(id: String) =
        object : kotlin.properties.ReadWriteProperty<Any?, Int?> {
            override fun getValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
            ) = placement(id).size

            override fun setValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
                value: Int?,
            ) {
                placement(id).size = value
            }
        }

    private fun colsOf(id: String) =
        object : kotlin.properties.ReadWriteProperty<Any?, Int> {
            override fun getValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
            ) = placement(id).cols ?: 0

            override fun setValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
                value: Int,
            ) {
                placement(id).cols = value
            }
        }

    private fun openOf(id: String) =
        object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
            override fun getValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
            ) = placement(id).open ?: false

            override fun setValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
                value: Boolean,
            ) {
                placement(id).open = value
            }
        }

    private fun shadedOf(id: String) =
        object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
            override fun getValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
            ) = placement(id).shaded ?: false

            override fun setValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
                value: Boolean,
            ) {
                placement(id).shaded = value
            }
        }

    /** Window shade: the window collapsed to its title bar. Stored per window across restarts. */
    var mainShaded: Boolean by shadedOf(WindowStore.MAIN)

    var eqShaded: Boolean by shadedOf(WindowStore.EQ)

    var plShaded: Boolean by shadedOf(WindowStore.PLAYLIST)

    /**
     * Whether collapsing is offered at all; see [ShadeStore]. It is mirrored here so the
     * windows read it as render state and redraw when it changes.
     */
    var shadeEnabled: Boolean by mutableStateOf(ShadeStore.DEFAULT)

    /**
     * Whether holding a small control opens the magnifier over it; see [TapAssistStore].
     * Mirrored here for the same reason as [shadeEnabled].
     */
    var tapAssist: Boolean by mutableStateOf(TapAssistStore.DEFAULT)

    /**
     * How many 25px steps wider than 275 the playlist is: Winamp's width index, as in
     * webamp (`size: [width, height]`, W = 275 + 25 * width).
     */
    var plCols: Int by colsOf(WindowStore.PLAYLIST)
    var plVisible: Boolean by openOf(WindowStore.PLAYLIST)

    // The plug-in window (AVS or Milkdrop), independent of the in-player visualizer.
    var milkdropOn: Boolean by openOf(WindowStore.MILKDROP)
    var milkdropFullscreen by mutableStateOf(false)
    var visPlugin by mutableStateOf(VisPlugin.Avs)

    /** Name of the preset pack the current engine runs; null is the engine's idle preset. */
    var presetPack by mutableStateOf<String?>(null)
    var presetShuffle by mutableStateOf(false)

    /** The preset now showing, as the engine reported it. Shown in the window's title. */
    var presetName by mutableStateOf<String?>(null)

    /**
     * Every preset in the loaded pack, by name, in the engine's playlist order, so an index
     * into this list is what [VisualCommands.goToPreset] takes. Empty while no pack is
     * loaded.
     */
    var presetNames by mutableStateOf<List<String>>(emptyList())

    /**
     * The commands the visual accepts, published by it while it is on screen, so a menu
     * item and a preferences page can reach the render thread without holding the view.
     * Null while the plug-in window is closed.
     */
    var visualCommands by mutableStateOf<VisualCommands?>(null)

    /** Set by the menu, consumed by the screen, which owns the document picker. */
    var presetPickRequested by mutableStateOf(false)

    /** Set by the menu, consumed by the screen, which navigates. */
    var presetManagerRequested by mutableStateOf(false)

    /**
     * Whether anything modal is up. The floating player uses it to decide whether to take
     * touches itself. It is defined next to the fields it reads, so a new modal is added
     * here too.
     */
    val modalShowing: Boolean
        get() =
            activeMenu != null || namePrompt != null || presetPicker != null ||
                bookmarkSheet != null || choiceSheet != null || trackInfo != null ||
                prompt != null || aboutOpen

    /**
     * The magnifier over a title bar, while a finger is holding one open. It is kept here
     * because it is drawn over all the windows and must not be clipped by one.
     */
    var loupe by mutableStateOf<nl.mattix.andamp.ui.window.Loupe?>(null)

    /** The about box, opened from the logo in the corner of the main window. */
    var aboutOpen by mutableStateOf(false)

    /**
     * Where the activity should navigate on arrival, set by the floating player, whose menu
     * can name a destination (Preferences, the museum) that only an activity can show. The
     * screen navigates and clears it.
     */
    var arrivalDestination by mutableStateOf<String?>(null)

    /**
     * Which page of that screen to open, where the screen has pages: "Widget settings" on
     * the widget's menu opens the widget's page of Preferences.
     */
    var arrivalPage by mutableStateOf<String?>(null)

    /**
     * The route this visit was for, when the visit came from outside the app.
     *
     * Done on a screen opened from the home screen widget goes back to the home screen.
     * The route is kept, so going deeper (Preferences into the museum) and pressing Done
     * comes back one step, and only leaving the route that was asked for leaves the app.
     *
     * [arrivalDestination] is cleared when the navigation happens; this lasts until the
     * visit ends.
     */
    var arrivedAt by mutableStateOf<String?>(null)

    /**
     * Whether one of the player's modals other than the menu is on screen (everything
     * `AmpModals` draws apart from the menu). The widget's menu host closes when the menu
     * closes, unless this says a dialog the menu opened is showing.
     */
    fun showingAModal(): Boolean =
        presetPicker != null ||
            trackInfo != null ||
            choiceSheet != null ||
            bookmarkSheet != null ||
            namePrompt != null ||
            prompt != null ||
            aboutOpen

    /**
     * Leaving the app ends a visit that came from outside it, so a later Done inside the
     * app does not leave the app.
     */
    fun leftTheApp() {
        arrivedAt = null
    }

    /**
     * Whether leaving [route] ends a visit that came from outside the app. A true answer
     * clears the visit, so it is given once.
     */
    fun leavingEndsTheVisit(route: String): Boolean {
        if (arrivedAt != route) return false
        arrivedAt = null
        return true
    }

    var selectedRows by mutableStateOf(setOf<Int>())
    var playlistScroll by mutableIntStateOf(0) // first visible row index
    var openMenu by mutableStateOf<String?>(null) // expanded bottom-bar menu id, e.g. "pl.menu.add"

    // the skin library window
    var skinManagerOpen by mutableStateOf(false)

    var skinEntries by mutableStateOf(emptyList<SkinEntry>())
    var skinScroll by mutableIntStateOf(0)

    /** Where the skin manager floats, in virtual px from screen center. */
    var skinManagerOffset: androidx.compose.ui.unit.IntOffset by placedOf(WindowStore.SKINS)

    /** How many rows the skin manager shows; null until the listener resizes it. */
    var skinRows: Int? by sizeOf(WindowStore.SKINS)

    // the media library window
    var libraryOpen by mutableStateOf(false)
    var libraryScroll by mutableIntStateOf(0)
    var libraryOffset: androidx.compose.ui.unit.IntOffset by placedOf(WindowStore.LIBRARY)

    /** How many rows the library shows; null fills the space there is. */
    var libraryRows: Int? by sizeOf(WindowStore.LIBRARY)

    /** How many 25px steps wider than its narrowest the library is drawn. */
    var libraryCols: Int by colsOf(WindowStore.LIBRARY)

    /**
     * Where the playlist floats, and how tall it is. A null offset means the layout places
     * it: docked under the stack. A null size fills what is left.
     */
    var plOffset: androidx.compose.ui.unit.IntOffset? by offsetOf(WindowStore.PLAYLIST)
    var plSegments: Int? by sizeOf(WindowStore.PLAYLIST)

    /**
     * Where the player, the equalizer and the plug-in window float. Null means the layout
     * places them in the classic stack, until the listener drags one.
     */
    var mainOffset: androidx.compose.ui.unit.IntOffset? by offsetOf(WindowStore.MAIN)
    var eqOffset: androidx.compose.ui.unit.IntOffset? by offsetOf(WindowStore.EQ)
    var milkdropOffset: androidx.compose.ui.unit.IntOffset? by offsetOf(WindowStore.MILKDROP)

    /** How tall the visual is, in resize steps; null is its default height. */
    var milkdropSteps: Int? by sizeOf(WindowStore.MILKDROP)

    /** Steps wider than 275, like the playlist's; Winamp's width index. */
    var milkdropCols: Int by colsOf(WindowStore.MILKDROP)

    /** The skin browser's width index. */
    var skinCols: Int by colsOf(WindowStore.SKINS)

    /** The lowest row a window may reach: the screen minus the gesture bar. */
    var safeBottom by mutableIntStateOf(0)

    /**
     * Every visible window's rectangle in screen space, for docking. Written by the
     * windows as they lay out; read only during a drag.
     */
    val windowRects = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.unit.IntRect>()

    /**
     * The height of each window's drag handle, which has to stay reachable. A group drag
     * clamps by whichever member meets the edge first, and handle heights differ.
     */
    val windowHandles = androidx.compose.runtime.mutableStateMapOf<String, Int>()

    /**
     * What a window may dock against: every other window on screen, except the ones it is
     * carrying, whose rectangles lag a frame behind during the drag.
     */
    fun neighboursOf(
        id: String,
        carrying: Set<String> = emptySet(),
    ): List<androidx.compose.ui.unit.IntRect> =
        windowRects.entries.filter { it.key != id && it.key !in carrying }.map { it.value }

    /** The screen size in virtual px; published by the layout pass. */
    var screenW by mutableIntStateOf(0)
    var screenH by mutableIntStateOf(0)

    /**
     * Whether the layout holds every window in one stack; published by the layout pass.
     * While it does, [windowRects] are the stack's and no window's own place changes.
     */
    var stackLocked by mutableStateOf(false)

    /** The floating stack, bottom to top; the last one is drawn on top. */
    var windowOrder by mutableStateOf(listOf("main", "eq", "milkdrop", "pl", "library", "skins"))

    /** A window's current offset, computed from the rectangle it published. */
    fun offsetOfWindow(id: String): androidx.compose.ui.unit.IntOffset? {
        val rect = windowRects[id] ?: return null
        return androidx.compose.ui.unit.IntOffset(
            rect.left - (screenW - rect.width) / 2,
            rect.top - (screenH - rect.height) / 2,
        )
    }

    /** Puts a window at [moved]; used for the carried windows of a group drag. */
    fun placeWindow(
        id: String,
        moved: androidx.compose.ui.unit.IntOffset,
    ) {
        placements[id]?.offset = moved
    }

    /**
     * Opens or closes a window. An opening window is raised to the front, so it is not
     * hidden behind another. Closing leaves the stack order alone.
     */
    fun setWindowOpen(
        id: String,
        open: Boolean,
        setOpen: (Boolean) -> Unit,
    ) {
        setOpen(open)
        if (open) raiseWindow(id)
    }

    /** Brings a window to the front; does nothing when it is already there. */
    fun raiseWindow(id: String) {
        if (windowOrder.lastOrNull() == id || id !in windowOrder) return
        windowOrder = windowOrder.filterNot { it == id } + id
    }

    /** The selected track row at the library's leaf; -1 for none. */
    var librarySelected by mutableIntStateOf(-1)

    /** The row under a finger, for the pressed highlight; -1 for none. */
    var libraryPressedRow by mutableIntStateOf(-1)

    // modals, rendered outside the skin canvases
    var activeMenu by mutableStateOf<AmpMenu?>(null)
    var namePrompt by mutableStateOf<NamePrompt?>(null)

    /** Winamp's preset dialogs: a titled list to load from or delete from. */
    var presetPicker by mutableStateOf<PresetPicker?>(null)

    /** A question with a few answers; see [ChoiceSheet]. */
    var choiceSheet by mutableStateOf<ChoiceSheet?>(null)

    /** Winamp's Stop after current track; the backend acts on it. */
    var stopAfterCurrent by mutableStateOf(false)

    /** Winamp's Alt+3: the info box for one track. */
    var trackInfo by mutableStateOf<nl.mattix.andamp.core.model.TrackInfo?>(null)

    /**
     * The last track that played in this session, for the main menu's View file info. It
     * is not stored across launches, and it is kept when the playlist is emptied.
     */
    var lastPlayed by mutableStateOf<nl.mattix.andamp.core.model.Track?>(null)

    /** Winamp's Preferences > Bookmarks; null while it is not shown. */
    var bookmarkSheet by mutableStateOf<BookmarkSheet?>(null)

    /** A prompt for the listener; null while there is none. */
    var prompt by mutableStateOf<AmpPrompt?>(null)

    /** A request for music access, waiting for the screen to ask; see [LibraryPrompt]. */
    var libraryAsk by mutableStateOf<LibraryAsk?>(null)

    /**
     * Winamp's Always On Top, as the clutter bar draws it: its A is lit while the floating
     * window is switched on. Mirrored here because draw code reads this state only.
     */
    var alwaysOnTop by mutableStateOf(false)

    // interaction transients
    var pressedWidget by mutableStateOf<String?>(null)

    /**
     * The first tap of a double tap on a playlist row. It is kept here and not in the
     * widget, because the first tap also raises the window, which recomposes the widget.
     */
    var plTapRow by mutableIntStateOf(-1)
    var plTapAt by mutableLongStateOf(0L)
    var seekPreview by mutableStateOf<Float?>(null) // 0..1 while dragging posbar
    var timeRemaining by mutableStateOf(false) // time display shows remaining

    // animation drivers

    /**
     * A list that has been thrown, and how to carry it. There is one at a time, shared by
     * the playlist, the skin library and the media library; the thrown list supplies
     * [Flung.carry].
     */
    var flung by mutableStateOf<Flung?>(null)

    var marqueeStep by mutableIntStateOf(0)

    /**
     * How far a finger has pushed the title, in virtual pixels. The clock steps the title
     * in characters ([marqueeStep]) and the finger moves it in pixels; the drawing adds
     * the two.
     */
    var marqueePixels by mutableIntStateOf(0)

    /** Whether a finger is on the title, which stops the clock from stepping it. */
    var marqueeHeld by mutableStateOf(false)
    var blinkOn by mutableStateOf(true) // pause blink phase
    var visFrame by mutableLongStateOf(0L) // bumped per vis frame
    var visMode by mutableStateOf(VisMode.Analyzer)

    // visualizer scratch data: plain arrays, invalidated via visFrame
    val visLevels = FloatArray(19)
    val visPeaks = FloatArray(19)
    val visPeakHold = IntArray(19)
    val visPeakVel = FloatArray(19)
    val visWave = IntArray(WAVE_COLUMNS)

    /**
     * What the marquee shows instead of the track while a slider is held, as in Winamp.
     * The EQ band wording is from Winamp 2.8 (`EQ: 6KHZ: +0.0 DB`); the volume, balance
     * and preamp wording follows webamp's marqueeUtils.
     */
    val marqueeOverride: String?
        get() =
            when (val widget = pressedWidget) {
                null -> null
                "main.volume" -> "VOLUME: $volume%"
                "main.balance" -> balanceText()
                "eq.preamp" -> "EQ: PREAMP: ${formatDb(preamp)} DB"
                else -> bandText(widget)
            }

    private fun balanceText(): String =
        when {
            balance == 0 -> "BALANCE: CENTER"
            balance < 0 -> "BALANCE: ${-balance}% LEFT"
            else -> "BALANCE: $balance% RIGHT"
        }

    private fun bandText(widget: String): String? {
        if (!widget.startsWith("eq.band")) return null
        val band = widget.removePrefix("eq.band").toIntOrNull() ?: return null
        val label = EQ_BAND_LABELS.getOrNull(band) ?: return null
        return "EQ: ${label}HZ: ${formatDb(eqBands[band])} DB"
    }

    companion object {
        const val WAVE_COLUMNS = 75
        const val EQ_BANDS = 10

        /** Band names as the skin prints them under the sliders. */
        val EQ_BAND_LABELS = listOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")

        /** Signed, one decimal, like Winamp's readout, from the dB mapping the DSP uses. */
        fun formatDb(sliderValue: Int): String {
            val db = EqOps.eqDb(sliderValue)
            val sign = if (db < 0f) "-" else "+"
            val magnitude = kotlin.math.abs(db)
            val tenths = kotlin.math.round(magnitude * 10).toInt()
            return "$sign${tenths / 10}.${tenths % 10}"
        }
    }

    /** Null when the playlist has been emptied (REM > ALL). */
    val currentTrack: Track? get() = playlist.getOrNull(currentIndex)

    /**
     * The bitrate of the frame going through the decoder, when the backend reports it, so
     * the readout follows a VBR file. Null falls back to the track's own bitrate.
     */
    var streamBitrateKbps by mutableStateOf<Int?>(null)

    /** The rate of what the decoder is producing, when the backend reports it; see [BackendState.streamSampleRateKhz]. */
    var streamSampleRateKhz by mutableStateOf<Int?>(null)

    /** A source is being connected to and is not yet playing; see [BackendState.connecting]. */
    var connecting by mutableStateOf(false)

    /**
     * Which sources this install can reach, for labeling the rows it cannot play; see
     * [rowLabel]. While it is null no row is labeled. [LibrarySources] owns the facts and
     * keeps this copy in step.
     */
    var reach: SourceReach? by mutableStateOf(null)

    /** The playing station's headers; null when no stream is playing. */
    var station by mutableStateOf<nl.mattix.andamp.core.model.StationHeaders?>(null)

    /** What the kbps readout shows: the live bitrate, or the track's. */
    val bitrateKbps: Int? get() = streamBitrateKbps ?: currentTrack?.bitrateKbps

    /** What the kHz readout shows: the rate the player reports, or the track's. */
    val sampleRateKhz: Int? get() = streamSampleRateKhz ?: currentTrack?.sampleRateKhz

    /** The rate the player reports for [track], which it does only for the current track. */
    fun liveSampleRateKhz(track: Track): Int? = streamSampleRateKhz?.takeIf { track == currentTrack }

    /** Fraction 0..1 of the current track that has elapsed (or seek preview while dragging). */
    val positionFraction: Float
        get() {
            val track = currentTrack ?: return 0f
            // a station has no position, and a stored playlist can hold a length for one
            if (track.isStream) return 0f
            val duration = track.durationMs
            // no length: 0/0 is NaN, which survives coerceIn and makes the posbar's
            // roundToInt throw
            if (duration <= 0) return 0f
            return seekPreview ?: (currentTimeSec * 1000f / duration).coerceIn(0f, 1f)
        }
}

/**
 * The text of a playlist row.
 *
 * A row whose source is absent says so and names the source; that label comes first. A
 * station being connected to shows `[Connecting]` and its address, as Winamp does; this
 * applies only to the current row. Any other row shows the track's display name.
 */
fun WinampState.rowLabel(
    track: nl.mattix.andamp.core.model.Track,
    index: Int,
): String {
    val known = reach
    val source = SourceForRows.of(track)
    val absent = known?.absence(source)
    return when {
        known != null && absent != null -> "[${absent.words}: ${known.named(source).label}] ${track.displayName}"
        connecting && index == currentIndex && track.isStream -> "[Connecting] ${track.uri.orEmpty()}"
        else -> track.displayName
    }
}
