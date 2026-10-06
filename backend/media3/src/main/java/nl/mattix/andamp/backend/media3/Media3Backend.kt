// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.StationHeaders
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.VolumeMode
import nl.mattix.andamp.core.network.SystemNetworkWatch
import nl.mattix.andamp.core.playback.AudioTap
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.PcmRingBuffer
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.playback.StreamReconnect
import nl.mattix.andamp.core.playback.withStationsAtRest

/**
 * Audio playback through Media3/ExoPlayer, with Winamp's transport semantics:
 * play while playing restarts the track, pause toggles, next and previous wrap
 * around the queue, the end of the queue without repeat stops, and shuffle
 * uses the player's shuffle order, for a track that ends and for next and
 * previous alike.
 *
 * Callers see [BackendState] and [Track], not Media3 types. Transport intent
 * is tracked here and not derived from `isPlaying`, so buffering still reads
 * as playing, as in Winamp.
 */
@Suppress("LongParameterList", "TooManyFunctions") // one parameter per collaborator; it implements PlaybackBackend
class Media3Backend(
    tracks: List<Track>,
    private val scope: CoroutineScope,
    private val player: Player,
    override val audioTap: AudioTap? = null,
    private val eqProcessor: EqAudioProcessor? = null,
    private val balanceProcessor: BalanceAudioProcessor? = null,
    private val dspProcessor: DspAudioProcessor? = null,
    /**
     * Opens a track's bytes for the kbps readout: Media3 reports one figure
     * per file, and Winamp's readout follows the frame being played. Null
     * leaves the readout on the file's average.
     */
    private val openTrack: ((String) -> java.io.InputStream?)? = null,
    /** Where the frame walk runs; tests pass an immediate context. */
    private val scanContext: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
    /** The row to start on, for a restored playlist. */
    startIndex: Int = 0,
    /**
     * Only for [keepAlive] and [teardown]: the session service is started and
     * stopped against it. Null in tests that build a player directly.
     */
    private val host: Context? = null,
    /**
     * Whether there is a network to reconnect a dropped station over; see
     * [StationRedial]. The default assumes there is one, and retries then run
     * on the clock alone.
     */
    network: NetworkWatch = NetworkWatch.Assumed,
    /** The clock a reconnect's budget is kept on. */
    clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) : PlaybackBackend {
    constructor(context: Context, tracks: List<Track>, scope: CoroutineScope, startIndex: Int = 0) : this(
        context,
        tracks,
        scope,
        PcmRingBuffer(),
        EqAudioProcessor(),
        BalanceAudioProcessor(),
        DspAudioProcessor(),
        startIndex,
    )

    @Suppress("LongParameterList") // one processor per stage of the chain, plus where to start
    private constructor(
        context: Context,
        tracks: List<Track>,
        scope: CoroutineScope,
        ring: PcmRingBuffer,
        eq: EqAudioProcessor,
        balance: BalanceAudioProcessor,
        dsp: DspAudioProcessor,
        startIndex: Int,
    ) : this(
        tracks,
        scope,
        ExoPlayer
            .Builder(context, tappingRenderersFactory(context, ring, eq, balance, dsp))
            .setMediaSourceFactory(streamingSources(context))
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true, // handle audio focus
                // the slider is the phone's media volume, which Media3 moves
                // only with device volume control enabled
            ).setDeviceVolumeControlEnabled(true)
            // hold a partial wakelock while playing. A foreground service keeps
            // the process alive but does not keep the CPU awake, which matters
            // while the player buffers and between two tracks. LOCAL here,
            // because NETWORK also holds a Wi-Fi lock and the setting is not
            // per item; wakeFor switches to NETWORK for a stream
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // headphones coming out pause the music; the transport follows
            // through onPlayWhenReadyChanged like any other outside pause
            .setHandleAudioBecomingNoisy(true)
            .build(),
        ring,
        eq,
        balance,
        dsp,
        TrackBytes(context),
        kotlinx.coroutines.Dispatchers.IO,
        startIndex,
        context.applicationContext,
        SystemNetworkWatch(context),
    )

    private val initialIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
    private val _state = MutableStateFlow(BackendState(queue = tracks, currentIndex = initialIndex))
    override val state: StateFlow<BackendState> = _state

    /**
     * The player the MediaSession drives: ExoPlayer, with stop redirected to
     * [stop].
     *
     * The notification, a headset, Android Auto and the home screen widget's
     * transport reach playback through the session. Play, pause and skip move
     * playWhenReady, which [listener] mirrors into the transport. Stop needs
     * its own path: [stop] pauses a file and seeks it to zero, and sets the
     * transport to Stopped. ExoPlayer.stop called directly would halt playback
     * without moving playWhenReady, and the state would go on saying Playing.
     */
    internal val playerForSession: Player =
        object : ForwardingPlayer(player) {
            override fun stop() = this@Media3Backend.stop()
        }

    override val capabilities =
        Capabilities(
            canSeek = true,
            canEditQueue = true,
            hasEqualizer = eqProcessor != null,
            hasBalance = balanceProcessor != null,
            hasDsp = dspProcessor != null,
            // the app can attenuate through ExoPlayer.volume, so both volume modes are offered
            canAttenuate = true,
        )

    /** Whose volume the slider moves; see [setVolumeMode]. */
    private var volumeMode = VolumeMode.DEVICE

    /** Whether anything has been heard from the item now loaded; see [refreshConnecting]. */
    private var heardThisItem = false

    /**
     * Whether the player is reaching for a source and not yet hearing it.
     *
     * Derived from three facts of the player. Buffering alone is not enough:
     * a stopped item buffers when the cursor lands on it. Wanting to play
     * alone is not enough: it is true throughout playback. Not having heard
     * the item yet makes this the first reach, and not a stall in the middle
     * of a live stream.
     */
    private fun refreshConnecting() {
        // a station being reconnected is reaching too, the wait between tries
        // included
        val reaching =
            player.playWhenReady &&
                (redial.pending || (player.playbackState == Player.STATE_BUFFERING && !heardThisItem))
        if (reaching != _state.value.connecting) {
            _state.value = _state.value.copy(connecting = reaching)
        }
    }

    /**
     * Reconnects a station that dropped, while the listener still wants it;
     * see [StationRedial].
     *
     * Declared before [listener] and the init block, because ExoPlayer calls
     * the listener from inside the first `setMediaItems`, and the listener asks
     * this whether a reconnect is under way.
     */
    private val redial =
        StationRedial(
            player = player,
            scope = scope,
            network = { network },
            policy = StreamReconnect(clock),
            gaveUp = { stationLost() },
            changed = { redialMoved() },
        )

    /**
     * A reconnect began, ended or was canceled. The position poll is stopped
     * while a station is off the air, and starts again when it is back.
     */
    private fun redialMoved() {
        if (!redial.pending && _state.value.transport == Transport.Playing) startPoll()
        refreshConnecting()
    }

    private var positionPoll: Job? = null
    private var fade: Job? = null

    /** The current track's frames, and which track they were walked from. */
    private var frames: Mp3Frames? = null
    private var framesFor: String? = null
    private var scanJob: Job? = null

    private val listener =
        object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                // the frame table and the station's headers belong to the
                // previous item
                frames = null
                framesFor = null
                heardThisItem = false
                // a reconnect belongs to the row it was for, and a skip from
                // the notification reaches the player without passing through
                // this class's verbs. A playlist change is excepted: replacing
                // the playing item's metadata (a station's song, tags arriving)
                // is reported as one, and canceling then would give a station
                // that keeps dropping a fresh retry budget on every song change
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) redial.cancel()
                wakeFor(player.currentMediaItemIndex)
                _state.value =
                    _state.value
                        .copy(
                            currentIndex = player.currentMediaItemIndex,
                            positionMs = 0,
                            streamBitrateKbps = null,
                            station = null,
                        )
                        // the previous station's row goes back to its own name
                        .withStationsAtRest()
                refreshConnecting()
            }

            override fun onTracksChanged(tracks: Tracks) {
                patchFormat(tracks)
            }

            // mirrored like the transport: the notification can change these
            // two, and the app's own buttons show the state
            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                if (enabled != _state.value.shuffle) _state.value = _state.value.copy(shuffle = enabled)
            }

            override fun onRepeatModeChanged(mode: Int) {
                val on = mode != Player.REPEAT_MODE_OFF
                if (on != _state.value.repeat) _state.value = _state.value.copy(repeat = on)
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                patchNowPlaying(mediaMetadata.title?.toString())
            }

            // in-band stream metadata: Shoutcast's StreamTitle and headers, and
            // HLS's ID3 frames
            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                // Shoutcast sends one string; HLS carries ID3 frames in its
                // segments, with title and artist in separate fields
                var title: String? = null
                var artist: String? = null
                for (i in 0 until metadata.length()) {
                    when (val entry = metadata.get(i)) {
                        // the station's own headers, sent on connecting
                        is androidx.media3.extractor.metadata.icy.IcyHeaders -> {
                            rememberStation(entry)
                        }

                        is androidx.media3.extractor.metadata.icy.IcyInfo -> {
                            patchNowPlaying(entry.title)
                        }

                        is androidx.media3.extractor.metadata.id3.TextInformationFrame -> {
                            when (entry.id) {
                                "TIT2" -> title = entry.values.firstOrNull()
                                "TPE1" -> artist = entry.values.firstOrNull()
                                else -> Unit
                            }
                        }

                        else -> {
                            Unit
                        }
                    }
                }
                if (title != null) patchNowPlaying(title, artist)
            }

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) {
                // mirror external control (media notification, headset buttons) into
                // the transport; this class's own calls set the same state, so this is idempotent
                if (!playWhenReady) redial.cancel()
                refreshConnecting()
                val s = _state.value
                if (playWhenReady && s.transport != Transport.Playing) {
                    _state.value = s.copy(transport = Transport.Playing)
                    startPoll()
                } else if (!playWhenReady && s.transport == Transport.Playing) {
                    stopPoll()
                    _state.value = s.copy(transport = Transport.Paused, positionMs = player.currentPosition)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.w("Media3Backend", "Playback error", error)
                if (StationRedial.heals(error) && dropped()) return
                // unplayable source: stop
                stopPoll()
                _state.value = _state.value.copy(transport = Transport.Stopped, positionMs = 0).withStationsAtRest()
            }

            override fun onDeviceVolumeChanged(
                volume: Int,
                muted: Boolean,
            ) {
                // the volume keys, the system panel and other apps all arrive
                // here, and the slider follows them
                publishVolume()
            }

            override fun onDeviceInfoChanged(deviceInfo: androidx.media3.common.DeviceInfo) {
                // the first value arrives here: Media3 fetches the device's
                // range on a background thread, and until then there is
                // nothing to place the slider on
                publishVolume()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // a station has no end: reaching one means the connection dropped
                if (playbackState == Player.STATE_ENDED && dropped()) return
                if (playbackState == Player.STATE_ENDED) {
                    // queue finished without repeat: stop at the first row
                    player.pause()
                    player.seekTo(0, 0)
                    stopPoll()
                    _state.value =
                        _state.value.copy(transport = Transport.Stopped, positionMs = 0, currentIndex = 0).withStationsAtRest()
                } else if (playbackState == Player.STATE_READY) {
                    // heard, which holds for this item however much buffering
                    // follows
                    if (player.playWhenReady) heardThisItem = true
                    patchDuration()
                    patchFormat(player.currentTracks)
                }
                refreshConnecting()
            }
        }

    /**
     * A station the listener is playing lost its connection: hands it to
     * [redial] and returns true, or returns false when this is not that case.
     *
     * Only for a stream, and only while the transport is Playing and the
     * player wants to play. The transport stays Playing throughout.
     */
    private fun dropped(): Boolean {
        val wanted = _state.value.transport == Transport.Playing && player.playWhenReady
        if (!wanted || !streamAt(player.currentMediaItemIndex)) return false
        stopPoll()
        heardThisItem = false
        redial.dropped()
        return true
    }

    /**
     * The station did not come back within the reconnect's budget: the
     * transport stops, and the listener is told why.
     *
     * The player is stopped as well (idle, with playWhenReady false), so
     * nothing prepares it again; the next play reconnects through [wake].
     */
    private fun stationLost() {
        stopPoll()
        _state.value =
            _state.value
                .copy(transport = Transport.Stopped, positionMs = 0)
                .withStationsAtRest()
                .raising(BackendNotice.StationLost)
        player.stop()
        player.pause()
        refreshConnecting()
    }

    init {
        player.addListener(listener)
        player.setMediaItems(tracks.map(::mediaItem), initialIndex, 0)
        player.prepare()
    }

    override fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
    ) {
        val s = _state.value
        val survivingIndex = s.currentTrack?.id?.let { id -> tracks.indexOfFirst { it.id == id } } ?: -1
        redial.cancel()
        // the new queue goes into the state before the player gets it:
        // ExoPlayer calls this class's listeners from inside setMediaItems,
        // and they read the state
        if (s.transport != Transport.Stopped && survivingIndex >= 0) {
            _state.value = s.copy(queue = tracks, currentIndex = survivingIndex)
            player.setMediaItems(tracks.map(::mediaItem), survivingIndex, player.currentPosition)
            player.prepare()
        } else {
            val start = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
            _state.value = s.copy(transport = Transport.Stopped, positionMs = 0, currentIndex = start, queue = tracks)
            player.setMediaItems(tracks.map(::mediaItem), start, 0)
            player.prepare()
            player.pause()
            stopPoll()
        }
    }

    override fun enqueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        // an append does not re-prepare: the player keeps its position and
        // its transport
        player.addMediaItems(tracks.map(::mediaItem))
        val s = _state.value
        _state.value = s.copy(queue = s.queue + tracks)
    }

    /**
     * Prepares the player when it has fallen idle.
     *
     * An unreadable source (a lapsed permission, a file that moved, a stream
     * that failed) takes ExoPlayer to IDLE, and from IDLE it does not play
     * until it is prepared again.
     */
    private fun wake() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    override fun play() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        abandonFade()
        redial.cancel()
        wake()
        when (s.transport) {
            // play while playing restarts the track, like Winamp
            Transport.Playing -> player.seekTo(player.currentMediaItemIndex, 0)

            Transport.Paused, Transport.Stopped -> Unit
        }
        player.play()
        _state.value = _state.value.copy(transport = Transport.Playing, positionMs = player.currentPosition, notice = null)
        startPoll()
    }

    override fun pause() {
        abandonFade()
        redial.cancel()
        when (_state.value.transport) {
            Transport.Playing -> {
                player.pause()
                stopPoll()
                _state.value = _state.value.copy(transport = Transport.Paused, positionMs = player.currentPosition)
            }

            Transport.Paused -> {
                // the player is idle when the pause came during a reconnect
                wake()
                player.play()
                _state.value = _state.value.copy(transport = Transport.Playing)
                startPoll()
            }

            Transport.Stopped -> {}
        }
    }

    /**
     * Winamp's stop: back to the beginning for a file, and disconnected for a
     * station.
     *
     * A stream has no beginning to rewind to, and pausing one holds the
     * connection open. Stopping the player leaves it idle, so the next play
     * reconnects through [wake].
     */
    override fun stop() {
        abandonFade()
        redial.cancel()
        if (_state.value.currentTrack?.isStream == true) {
            player.stop()
        } else {
            player.pause()
            player.seekTo(player.currentMediaItemIndex, 0)
        }
        stopPoll()
        _state.value = _state.value.copy(transport = Transport.Stopped, positionMs = 0).withStationsAtRest()
        refreshConnecting()
    }

    /**
     * Walks the player's own gain to zero, then stops. The listener's volume
     * is not touched.
     */
    override fun stopWithFadeout() {
        fade?.cancel()
        fade =
            scope.launch {
                val steps = (FADE_MS / FADE_STEP_MS).toInt()
                for (step in steps - 1 downTo 0) {
                    player.volume = step.toFloat() / steps
                    delay(FADE_STEP_MS)
                }
                // finished, so the stop below has no fade to abandon
                fade = null
                stop()
                player.volume = 1f
            }
    }

    /**
     * Any verb that starts or stops the music cancels a fade under way and
     * restores the gain, so the fade's closing stop does not land on what the
     * listener started meanwhile.
     */
    private fun abandonFade() {
        val running = fade ?: return
        fade = null
        running.cancel()
        player.volume = 1f
    }

    /**
     * ExoPlayer's `pauseAtEndOfMediaItems`: it holds at the end of the item
     * instead of advancing. It is not on the `Player` interface, so this does
     * nothing for a player that is not an ExoPlayer.
     */
    override fun setStopAfterCurrent(on: Boolean) {
        (player as? ExoPlayer)?.pauseAtEndOfMediaItems = on
    }

    override fun next() = step(neighbour(forward = true))

    override fun previous() = step(neighbour(forward = false))

    /**
     * The row a skip lands on: the one below or above, and under shuffle the one the
     * player's shuffle order has next or had before.
     *
     * That order is the one playback moves through by itself, so Next plays what would
     * have come next and Previous goes back the way it came. Past either end of it a skip
     * wraps to the other end, as it wraps around the queue without shuffle.
     */
    private fun neighbour(forward: Boolean): Int {
        val at = _state.value.currentIndex
        val timeline = player.currentTimeline
        if (!player.shuffleModeEnabled || timeline.isEmpty) return if (forward) at + 1 else at - 1
        // asked without the repeat mode, which would answer with this same row for repeat-one
        val to =
            if (forward) {
                timeline.getNextWindowIndex(at, Player.REPEAT_MODE_OFF, true)
            } else {
                timeline.getPreviousWindowIndex(at, Player.REPEAT_MODE_OFF, true)
            }
        return when {
            to != C.INDEX_UNSET -> to
            forward -> timeline.getFirstWindowIndex(true)
            else -> timeline.getLastWindowIndex(true)
        }
    }

    /**
     * What the skip buttons do depends on the transport.
     *
     * Paused, they play the item they land on. Stopped, they only move the
     * cursor and nothing is fetched, so no connection is opened to a station.
     * Playing, they carry on playing.
     */
    private fun step(to: Int) {
        val s = _state.value
        val size = s.queue.size
        if (size == 0) return
        abandonFade()
        redial.cancel()
        val at = ((to % size) + size) % size
        if (s.transport == Transport.Stopped) {
            // no wake(): preparing would open the source
            _state.value = s.copy(currentIndex = at, positionMs = 0)
            player.seekTo(at, 0)
            return
        }
        wake()
        player.seekTo(at, 0)
        if (s.transport == Transport.Paused) player.play()
    }

    override fun playAt(index: Int) {
        if (index !in _state.value.queue.indices) return
        abandonFade()
        redial.cancel()
        wake()
        player.seekTo(index, 0)
        player.play()
        _state.value = _state.value.copy(transport = Transport.Playing, currentIndex = index, positionMs = 0, notice = null)
        startPoll()
    }

    override fun seekTo(positionMs: Long) {
        val duration = _state.value.currentTrack?.durationMs ?: return
        val clamped = positionMs.coerceIn(0, duration)
        redial.cancel()
        player.seekTo(clamped)
        // the kbps readout follows the frame at the position, so a seek
        // updates it at once
        _state.value =
            _state.value.copy(
                positionMs = clamped,
                streamBitrateKbps = frames?.kbpsAt(clamped) ?: _state.value.streamBitrateKbps,
            )
    }

    override fun setVolume(fraction: Float) {
        if (volumeMode == VolumeMode.APP) {
            // the app's own gain, 0..1; the phone's volume stays where it is
            player.volume = fraction.coerceIn(0f, 1f)
            _state.value = _state.value.copy(volumeFraction = player.volume)
            return
        }
        val info = player.deviceInfo
        if (info.maxVolume <= info.minVolume) return // no range to map onto yet; see publishVolume
        player.setDeviceVolume(DeviceVolume.stepFor(fraction, info.minVolume, info.maxVolume), 0)
        // no read-back here: the device answers through onDeviceVolumeChanged,
        // so one path owns the slider
    }

    /**
     * Switching modes keeps what is heard, and lets the slider move.
     *
     * What comes out is the device's volume times the app's gain, and a switch
     * preserves that product.
     *
     * Into [VolumeMode.APP]: the gain is 1 and the slider goes to the top. The
     * device volume is not touched.
     *
     * Back to [VolumeMode.DEVICE]: the app's attenuation is folded into the
     * device volume, which the slider then shows again. The fold rounds to one
     * of the device's volume steps, so the loudness can shift slightly.
     */
    override fun setVolumeMode(mode: VolumeMode) {
        if (mode == volumeMode) return
        volumeMode = mode
        if (mode == VolumeMode.DEVICE) {
            val heard = deviceFraction() * player.volume
            player.volume = 1f
            setVolume(heard)
        } else {
            player.volume = 1f
        }
        publishVolume()
    }

    /** Where the phone's own media volume sits, 0..1, or 1 when it has no range yet. */
    private fun deviceFraction(): Float {
        val info = player.deviceInfo
        if (info.maxVolume <= info.minVolume) return 1f
        if (player.isDeviceMuted) return 0f
        return DeviceVolume.fractionFor(player.deviceVolume, info.minVolume, info.maxVolume)
    }

    /**
     * Publishes the phone's media volume as the slider's fraction.
     *
     * Media3 fetches the device's volume on a background thread, so right
     * after the player is built there is no range yet. Nothing is published
     * then, and the listener publishes when the range arrives.
     */
    private fun publishVolume() {
        // in APP mode the slider shows the app's gain, and the device's volume
        // does not move it
        if (volumeMode == VolumeMode.APP) {
            _state.value = _state.value.copy(volumeFraction = player.volume)
            return
        }
        val info = player.deviceInfo
        if (info.maxVolume <= info.minVolume) return
        val fraction =
            if (player.isDeviceMuted) {
                0f
            } else {
                DeviceVolume.fractionFor(player.deviceVolume, info.minVolume, info.maxVolume)
            }
        _state.value = _state.value.copy(volumeFraction = fraction)
    }

    /**
     * Applies metadata that arrived later to both places that hold it: the
     * queue, which the app's own windows read, and the media items, which the
     * notification and the lock screen read.
     *
     * Replacing an item whose uri has not changed is a metadata edit; Media3
     * keeps the source it already holds, so playback is not disturbed.
     *
     * When [BackendPlayer] holds the session it reads the queue, and the media
     * items are updated all the same.
     */
    override fun patchTracks(patched: List<Track>) {
        val s = _state.value
        val queue =
            nl.mattix.andamp.core.playback.QueuePatch
                .apply(s.queue, patched)
        _state.value = s.copy(queue = queue)
        val byId = patched.associateBy { it.id }
        queue.forEachIndexed { at, entry ->
            if (entry.id in byId) {
                runCatching { player.replaceMediaItem(at, mediaItem(entry)) }
            }
        }
    }

    override fun setShuffle(enabled: Boolean) {
        player.shuffleModeEnabled = enabled
        _state.value = _state.value.copy(shuffle = enabled)
    }

    override fun setRepeat(enabled: Boolean) {
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        _state.value = _state.value.copy(repeat = enabled)
    }

    override fun setBalance(balance: Float) {
        balanceProcessor?.update(balance)
    }

    /**
     * What the rack can run. Before anything plays, the plug-ins are loaded
     * for a default format; a stream's own format is loaded when the
     * processor is configured for it.
     */
    override val effects: List<nl.mattix.andamp.core.model.EffectSpec>
        get() = offered.value

    /**
     * What the rack can run, as a flow: it grows when a plug-in's script
     * finishes loading.
     *
     * The processor is built before this backend, so it is told here where to
     * publish.
     */
    private val offered =
        kotlinx.coroutines.flow
            .MutableStateFlow(nl.mattix.andamp.core.model.BuiltInEffects.all)
            .also { flow -> dspProcessor?.publishCatalogue { flow.value = it } }

    override val effectsFlow get() = offered

    override val shippedPluginIds get() =
        nl.mattix.andamp.backend.media3.dsp.BuiltInStages
            .shippedPluginIds()

    override fun setPlugins(sources: List<String>) {
        dspProcessor?.setPlugins(sources)
    }

    override fun setDsp(rack: nl.mattix.andamp.core.model.RackSettings) {
        dspProcessor?.update(rack)
    }

    override fun setEqualizer(settings: nl.mattix.andamp.core.model.EqSettings) {
        eqProcessor?.update(settings)
    }

    /** Starts the session service, which outlives the app's windows. */
    override fun keepAlive() {
        host?.let { Media3PlaybackHost.ensureService(it) }
    }

    /**
     * Keeps the Wi-Fi radio awake for a stream, and only for a stream.
     *
     * `setWakeMode` is one setting for the whole player, and NETWORK adds a
     * WifiLock to the wakelock, so the mode follows the current item.
     *
     * The answer is read from the player's own item and not from [_state]:
     * this is called from `onMediaItemTransition`, which ExoPlayer fires from
     * inside `setMediaItems`. The item carries whether it is a stream from the
     * moment it is built ([mediaItem]).
     */
    private fun wakeFor(index: Int) {
        val mode = if (streamAt(index)) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL
        wakeMode?.invoke(mode) ?: (player as? ExoPlayer)?.setWakeMode(mode)
    }

    /** Whether the player's item at [index] is a station, by the tag [mediaItem] put on it. */
    private fun streamAt(index: Int): Boolean {
        val item = if (index in 0 until player.mediaItemCount) player.getMediaItemAt(index) else null
        return item?.localConfiguration?.tag == STREAM
    }

    /**
     * Where a test reads the wake mode [wakeFor] chose, since ExoPlayer does
     * not report it. Null, as it is in the app, passes the mode to the player;
     * only an ExoPlayer has a wake mode.
     *
     * Nullable, not a lambda with a default: ExoPlayer fires the first item
     * transition while this class is still being constructed, before this
     * property is initialized, and [wakeFor] then finds null.
     */
    internal var wakeMode: ((Int) -> Unit)? = null

    /** Main menu > Exit: stops the session service, and the media notification with it. */
    override fun teardown() {
        redial.cancel()
        host?.let { Media3PlaybackHost.stopService(it) }
    }

    override fun release() {
        redial.release()
        stopPoll()
        // a running fade ends in a stop and a scan in a state update; both are
        // canceled before the player is released
        fade?.cancel()
        scanJob?.cancel()
        player.release()
    }

    /**
     * Keeps what a station said about itself, for the file info box.
     *
     * Cleared on an item transition, because the headers belong to one
     * connection.
     */
    private fun rememberStation(headers: androidx.media3.extractor.metadata.icy.IcyHeaders) {
        val station =
            StationHeaders(
                name = headers.name?.takeIf { it.isNotBlank() },
                genre = headers.genre?.takeIf { it.isNotBlank() },
                url = headers.url?.takeIf { it.isNotBlank() },
                bitrateKbps = headers.bitrate.takeIf { it > 0 }?.div(BITS_PER_KBIT_ICY),
            )
        if (station.isEmpty) return
        _state.value = _state.value.copy(station = station)
    }

    /**
     * Applies what a station says it is playing to the entry that is playing
     * it.
     *
     * Shoutcast sends a new `StreamTitle` for every song. The entry takes the
     * song's artist and title; the station's own name stays in
     * [Track.defaultName] and returns when the station stops.
     *
     * Streams only: a local file's tags were read when it was added, and the
     * decoder's metadata does not replace them.
     */
    private fun patchNowPlaying(
        raw: String?,
        artist: String? = null,
    ) {
        val s = _state.value
        val track = s.currentTrack ?: return
        val now = IcyTitle.applyTo(track, raw, artist) ?: return
        val queue = s.queue.toMutableList()
        queue[s.currentIndex] = now
        _state.value = s.copy(queue = queue)
        // the notification reads the media item, not the queue. Replacing an
        // item whose uri has not changed is a metadata edit, and Media3 keeps
        // the source open. A player that refuses the edit leaves the queue's
        // title in place.
        runCatching { player.replaceMediaItem(s.currentIndex, mediaItem(now)) }
    }

    /**
     * The player learns real durations on prepare; patch them into the queue.
     *
     * A live stream is excepted: HLS reports the length of its sliding
     * window, and a length would make the position bar seekable. A stream's
     * entry keeps the length it has.
     */
    private fun patchDuration() {
        val s = _state.value
        val duration =
            lengthToShow(
                live = player.isCurrentMediaItemLive || s.currentTrack?.isStream == true,
                reported = player.duration,
            ) ?: return
        val track = s.currentTrack ?: return
        if (track.durationMs == duration) return
        val queue = s.queue.toMutableList()
        queue[s.currentIndex] = track.copy(durationMs = duration)
        _state.value = s.copy(queue = queue)
    }

    /**
     * The kbps/kHz readouts, from the stream and not from tags: see
     * [StreamFormat]. Patched into the queue entry, as the duration is.
     */
    private fun patchFormat(tracks: Tracks) {
        val format = audioFormatOf(tracks) ?: return
        val s = _state.value
        val track = s.currentTrack ?: return
        indexFrames(track, format.sampleMimeType)
        val patched =
            StreamFormat.patch(
                track,
                bitrateBps = format.averageBitrate.orElse(format.peakBitrate),
                sampleRateHz = format.sampleRate,
                mimeType = format.sampleMimeType,
            )
        if (patched == track) return
        val queue = s.queue.toMutableList()
        queue[s.currentIndex] = patched
        _state.value = s.copy(queue = queue)
    }

    /**
     * The audio format the player is reading: the selected audio group when
     * there is one, or else the first audio group, which still describes the
     * file.
     */
    private fun audioFormatOf(tracks: Tracks): Format? {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val group = audio.firstOrNull { it.isSelected } ?: audio.firstOrNull() ?: return null
        val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0
        return group.getTrackFormat(index)
    }

    private fun Int.orElse(fallback: Int) = if (this > 0) this else fallback

    /**
     * Walks the current track's frames in the background, so the readout can
     * follow the bitrate.
     *
     * Only for mp3, and once per track: a file that is not layer III, or that
     * cannot be opened, keeps the average from [StreamFormat].
     */
    private fun indexFrames(
        track: Track,
        mimeType: String?,
    ) {
        if (mimeType != StreamFormat.MPEG_AUDIO || framesFor == track.id) return
        val open = openTrack ?: return
        val uri = track.uri ?: return
        framesFor = track.id
        frames = null
        scanJob?.cancel()
        scanJob =
            scope.launch {
                val walked =
                    withContext(scanContext) {
                        @Suppress("TooGenericExceptionCaught") // an unreadable file leaves the readout blank
                        try {
                            open(uri)?.use { Mp3Frames.scan(it) }
                        } catch (e: Exception) {
                            android.util.Log.i("Media3Backend", "Could not walk $uri", e)
                            null
                        }
                    }
                if (framesFor != track.id) return@launch
                frames = walked
                _state.value = _state.value.copy(streamBitrateKbps = walked?.kbpsAt(player.currentPosition))
            }
    }

    private fun startPoll() {
        if (positionPoll?.isActive == true) return
        positionPoll =
            scope.launch {
                while (true) {
                    delay(POLL_MS)
                    if (_state.value.transport == Transport.Playing) {
                        // a live window's position is relative to its edge, and
                        // is negative while the player catches up to it
                        val at = player.currentPosition.coerceAtLeast(0)
                        _state.value = _state.value.copy(positionMs = at, streamBitrateKbps = frames?.kbpsAt(at))
                    }
                }
            }
    }

    private fun stopPoll() {
        positionPoll?.cancel()
        positionPoll = null
    }

    private fun mediaItem(track: Track): MediaItem =
        MediaItem
            .Builder()
            .setUri(track.uri.orEmpty())
            .setMediaId(track.id)
            // shown in the media notification and on the lock screen
            .setMediaMetadata(track.toMediaMetadata())
            // whether this row is a stream, carried by the item so [wakeFor]
            // does not read the queue
            .setTag(if (track.isStream) STREAM else FILE)
            .build()

    private companion object {
        /** The tags [mediaItem] puts on a row, read back by [wakeFor]. */
        const val STREAM = "andamp.stream"
        const val FILE = "andamp.file"

        const val POLL_MS = 250L

        /** `IcyHeaders.bitrate` is in bits per second; the state holds kbit/s. */
        const val BITS_PER_KBIT_ICY = 1_000

        /** The length of the fade before a stop, and of one step of it, in milliseconds. */
        const val FADE_MS = 900L
        const val FADE_STEP_MS = 30L

        /**
         * A renderers factory whose audio sink runs [audioChain], the list a
         * local file and a backend's own samples both go through.
         */
        @Suppress("LongParameterList") // one parameter per stage of the audio chain
        fun tappingRenderersFactory(
            context: Context,
            ring: PcmRingBuffer,
            eq: EqAudioProcessor,
            balance: BalanceAudioProcessor,
            dsp: DspAudioProcessor,
        ): DefaultRenderersFactory =
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink
                        .Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(audioChain(eq, balance, dsp, ring).toTypedArray())
                        .build()
            }
    }
}

/** Reads a track's own bytes, for the frame-by-frame kbps readout. */
private class TrackBytes(
    private val context: Context,
) : (String) -> java.io.InputStream? {
    override fun invoke(uri: String): java.io.InputStream? =
        runCatching {
            if (uri.startsWith(ASSET_SCHEME)) {
                context.assets.open(uri.removePrefix(ASSET_SCHEME))
            } else {
                context.contentResolver.openInputStream(android.net.Uri.parse(uri))
            }
        }.getOrNull()

    private companion object {
        const val ASSET_SCHEME = "asset:///"
    }
}

/**
 * Sources that ask a station what it is playing.
 *
 * Shoutcast sends its in-band `StreamTitle` only to a client that asks for it
 * with the `Icy-MetaData` header, which ExoPlayer's default HTTP source does
 * not send. Redirects across protocols are allowed because many station URLs
 * redirect before the audio starts.
 */
private fun streamingSources(context: Context) =
    androidx.media3.exoplayer.source
        .DefaultMediaSourceFactory(
            androidx.media3.datasource
                .DefaultDataSource
                .Factory(
                    context,
                    androidx.media3.datasource
                        .DefaultHttpDataSource
                        .Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1")),
                ),
        )

/**
 * The length a queue entry should carry, or null to leave it alone.
 *
 * A live window's length is not a track's length. A separate function so the
 * rule can be tested.
 */
internal fun lengthToShow(
    live: Boolean,
    reported: Long,
): Long? =
    when {
        live -> null
        reported == C.TIME_UNSET -> null
        reported <= 0 -> null
        else -> reported
    }
