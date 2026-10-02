// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.state.MusicSource
import nl.mattix.andamp.state.RealOscilloscope
import nl.mattix.andamp.state.RealSpectrum
import nl.mattix.andamp.state.SkinLibrary
import nl.mattix.andamp.state.SourceForRows
import nl.mattix.andamp.state.SourceSkins
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.takesSkin

/**
 * Keeps the home screen's copy of the player in step with the real one.
 *
 * The launcher can wake the widget after this process has gone, so the player pushes: every state
 * that changes what the window looks like is written to disk and the widget is asked to redraw.
 * Position is rounded to the second before it counts as a change, the rate the clock readout shows.
 *
 * It also keeps the widget's skin in step: see [followSources].
 */
class WidgetOps(
    private val context: Context,
    private val facade: PlayerFacade,
    private val scope: CoroutineScope,
    /** Whether a source takes a skin of its own; a test names one no pack declares. */
    private val skinnable: (MusicSource) -> Boolean = ::takesSkin,
    /** Where the skin library is read and written; a test keeps that on the thread it drives. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Hands over the window state whose clock direction and balance the widget draws too. It is UI
     * state and not the backend's, so it does not arrive with [PlayerFacade.state]. With no window
     * followed, the last values stand.
     */
    fun follow(state: WinampState) {
        followed = state
        if (started) watch(state)
    }

    private fun watch(state: WinampState) {
        following?.cancel()
        following = scope.launch { snapshotFlow { Drawn(state.timeRemaining, state.balance) }.collect { drawn.value = it } }
    }

    /** The player's own window state that the widget draws too. */
    private data class Drawn(
        val timeRemaining: Boolean = false,
        /** -100..100, shown by the balance thumb and set only in the player. */
        val balance: Int = 0,
    )

    private val drawn = MutableStateFlow(Drawn())

    /**
     * Idempotent, and undone by [stop]. The scope outlives every window, so nothing else cancels
     * these collectors. Called again after a stop, this starts the mirror over.
     */
    fun start() {
        if (started) return
        started = true
        // the sliders reach playback through the attached player
        WidgetControl.attach(facade)
        followed?.let { watch(it) }
        running += scope.launch { analyzer() }
        // immediate, like the player's own follower: see followSources
        running += scope.launch(Dispatchers.Main.immediate) { followSources() }
        running +=
            scope.launch {
                facade.state
                    .combine(drawn) { s, window -> s to window }
                    .map { (s, window) ->
                        val track = s.currentTrack
                        WidgetSnapshot(
                            title = track?.title.orEmpty(),
                            artist = track?.artist.orEmpty(),
                            durationMs = track?.durationMs ?: 0,
                            // the readout counts in seconds, and Low steps it further
                            positionSec = stepped(s.positionMs, WidgetSettings.read(context).refresh),
                            transport = s.transport,
                            timeRemaining = window.timeRemaining,
                            // one way only: the widget shows the balance and takes no presses on it
                            balance = window.balance,
                            bitrateKbps = s.streamBitrateKbps ?: track?.bitrateKbps,
                            sampleRateKhz = s.streamSampleRateKhz ?: track?.sampleRateKhz,
                            shuffle = s.shuffle,
                            repeat = s.repeat,
                            volume = (s.volumeFraction * VOLUME_RANGE).toInt(),
                            stream = track?.isStream == true,
                            // a stream has no position to move to, and a backend
                            // that cannot seek would swallow the press
                            seekable = facade.capabilities.canSeek && track?.isStream != true,
                            queueNumber = s.currentIndex + 1,
                        )
                    }
                    // a stopped player has no clock to run, and a VBR bitrate can change several
                    // times a second: a change of bitrate alone is not sent, it rides along with
                    // the next change
                    .distinctUntilChanged { old, new ->
                        old == new ||
                            old.copy(bitrateKbps = new.bitrateKbps) == new ||
                            (new.transport == Transport.Stopped && stoppedLooksSame(old, new))
                    }.combine(WidgetSignals.screenOn(context)) { snapshot, screenOn -> snapshot to screenOn }
                    .collect { (snapshot, screenOn) ->
                        // nothing is written or rendered while the screen is off or no widget is
                        // placed; the screen coming back on re-emits the last snapshot
                        if (!screenOn || !MainWindowWidget.hasInstances(context)) return@collect
                        WidgetSnapshot.write(context, snapshot)
                        MainWindowWidget.refresh(context)
                    }
            }
    }

    /**
     * Keeps a short loop of the visualizer fresh, in bursts.
     *
     * The launcher animates the frames itself, but producing them costs an FFT per frame. So the
     * audio is followed only for the length of one loop and then left alone for
     * [WidgetRefresh.burstMs]; the loop repeats in between.
     *
     * It follows the audio at [FRAME_MS], the rate the fall of the bars is tuned for, and keeps
     * every third frame.
     */
    private suspend fun analyzer() {
        val scratch = WinampState()
        combine(
            facade.state,
            WidgetSignals.screenOn(context),
            WidgetSignals.settings(context),
        ) { playback, screenOn, settings ->
            val tap = facade.audioTap
            val wanted =
                settings.mode != VisMode.Off &&
                    wantsFrames(
                        playback.transport,
                        hasAudio = tap != null,
                        screenOn = screenOn,
                        // no bursts while no widget is placed
                        placed = MainWindowWidget.hasInstances(context),
                    )
            if (wanted && tap != null) Following(settings.mode, tap, settings.refresh.burstMs) else null
        }.distinctUntilChanged()
            // the burst loop is cancelled when its reason goes away, so nothing polls while nothing
            // is playing
            .collectLatest { following ->
                if (following == null) {
                    if (WidgetVisFrames.frames.isNotEmpty()) {
                        WidgetVisFrames.clear()
                        MainWindowWidget.refresh(context)
                    }
                    return@collectLatest
                }
                val follower = Follower(following.mode, following.tap)
                var listening = 0
                while (currentCoroutineContext().isActive) {
                    val captured = ArrayList<VisFrame>(WidgetVisFrames.COUNT)
                    repeat(WidgetVisFrames.COUNT * KEEP_EVERY) { step ->
                        follower.step(scratch)
                        if (step % KEEP_EVERY == 0) captured += VisFrame.of(scratch, dropCaps = true)
                        delay(FRAME_MS)
                    }
                    // a loop of silence would stay dark for the whole gap, and the start of a song
                    // is often quiet: listen again, up to KEEP_LISTENING times
                    if (captured.none { it.hasSignal } && listening < KEEP_LISTENING) {
                        listening++
                        continue
                    }
                    listening = 0
                    WidgetVisFrames.offer(captured)
                    MainWindowWidget.refresh(context)
                    delay(following.gap)
                }
            }
    }

    /**
     * Keeps the skin the widget wears in step with the source of the current track.
     *
     * The player's window does this for itself, but a widget press can start this process with no
     * window open. So this applies the same rule, [SourceSkins.wornFor], through
     * [SkinLibrary.wear].
     *
     * Only a change of source is acted on, so a skin the listener picks while a source's is on
     * stays until a track from another source. [SkinLibrary.choices] is read on the main thread
     * before the write, so a pick made meanwhile wins and this write is turned down.
     */
    private suspend fun followSources() {
        facade.state
            .map { it.currentTrack?.let(SourceForRows::of) }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { source ->
                val asked = SkinLibrary.choices
                val worn =
                    withContext(io) {
                        val skin = SourceSkins(context).wornFor(source, skinnable)
                        SkinLibrary(context).wear(skin, asked)
                    }
                // turned down: the pick that overtook it refreshes the widget itself
                if (worn != null) MainWindowWidget.refresh(context)
            }
    }

    /**
     * Cancels everything [start] launched and detaches the player. The collectors live on a scope
     * that outlives every window, so they are cancelled here.
     */
    fun stop() {
        if (!started) return
        started = false
        following?.cancel()
        following = null
        running.forEach { it.cancel() }
        running.clear()
        WidgetControl.detach()
        WidgetVisFrames.clear()
        // the last picture is sent from here: the launcher holds whatever it was sent last,
        // including the animated analyzer, and nothing else writes after this stops
        WidgetSnapshot.write(context, WidgetSnapshot.read(context).copy(transport = Transport.Stopped, positionSec = 0))
        MainWindowWidget.refresh(context)
    }

    private var started = false

    /** What start() launched, so stop() can take it back. */
    private val running = mutableListOf<kotlinx.coroutines.Job>()

    private var following: kotlinx.coroutines.Job? = null

    /** The window handed over by [follow], re-watched whenever this starts again. */
    private var followed: WinampState? = null

    /**
     * The reason the analyzer is running, as one value: a change of mode, tap or gap restarts the
     * burst.
     */
    private data class Following(
        val mode: VisMode,
        val tap: nl.mattix.andamp.core.playback.AudioTap,
        val gap: Long,
    )

    /** Whichever of the two signals the chosen picture is drawn from. */
    private class Follower(
        val mode: VisMode,
        tap: nl.mattix.andamp.core.playback.AudioTap,
    ) {
        private val spectrum = if (mode == VisMode.Analyzer) RealSpectrum(tap, FRAME_MS) else null
        private val wave = if (mode == VisMode.Oscilloscope) RealOscilloscope(tap, FRAME_MS) else null

        fun step(state: WinampState) {
            spectrum?.step(state)
            wave?.step(state)
        }
    }

    private fun stoppedLooksSame(
        old: WidgetSnapshot,
        new: WidgetSnapshot,
    ) = old.copy(positionSec = 0) == new.copy(positionSec = 0) && old.transport == Transport.Stopped

    internal companion object {
        /**
         * Whether following the audio could be seen by anyone: something is playing with an audio
         * tap, the screen is on and a widget is placed. `AppWidgetProvider` is not told whether the
         * widget is visible, so nothing finer is known.
         */
        internal fun wantsFrames(
            transport: Transport,
            hasAudio: Boolean,
            screenOn: Boolean,
            placed: Boolean,
        ) = transport == Transport.Playing && hasAudio && screenOn && placed

        /**
         * The position the widget shows, coarsened to the chosen rate: on Low the clock steps in
         * fives.
         */
        internal fun stepped(
            positionMs: Long,
            refresh: WidgetRefresh,
        ): Int {
            val step = (refresh.windowMs / MS_PER_SEC).coerceAtLeast(1)
            return ((positionMs / MS_PER_SEC) / step * step).toInt()
        }

        const val MS_PER_SEC = 1_000L
        const val VOLUME_RANGE = 100

        /** The rate SpectrumPhysics' fall was tuned at; stepping slower is slow motion. */
        const val FRAME_MS = 16L

        /** Keep one frame in three, so the loop plays back at the launcher's flip rate. */
        const val KEEP_EVERY = 3

        /** How many silent loops to listen past before showing one: about two seconds. */
        const val KEEP_LISTENING = 5
    }
}
