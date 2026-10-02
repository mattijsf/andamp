// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.VolumeMode
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.PcmProvider
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.playback.QueuePatch
import nl.mattix.andamp.core.playback.StreamReconnect
import nl.mattix.andamp.core.playback.TransportRules
import nl.mattix.andamp.core.playback.withStationsAtRest
import nl.mattix.andamp.pack.common.audio.Ending
import nl.mattix.andamp.pack.common.audio.StreamAudio
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * A music server's HTTP streams as a playback backend: Winamp's queue and rules above, one
 * decoded track at a time below.
 *
 * The queue, the cursor, shuffle, repeat and the skip buttons follow [TransportRules]. This
 * class applies a rule, compares the state before and after, and starts, moves or releases
 * the one track that difference asks for.
 *
 * It is given a way of opening a track ([open]) and does not open one itself, so the
 * contract suite runs against a fake with no server and no `MediaCodec`, and every server
 * pack can share it; see [StreamPlayback].
 *
 * One stream lasts across a track change the listener did not ask for. The output ends its
 * pipe when a provider ends, and the player drops what it still holds when a pipe ends. So
 * the output is handed a [Stream], which lasts until audio is meant to be thrown away, and
 * the tracks are fed into it one after another. A skip, a pick, a seek or a stop discards,
 * and starts a stream of its own.
 *
 * The next track is opened before this one ends, when the end is near; see [plan]. Opening
 * early is the network work only: the codec waits for its `DecoderTurn`. The current row
 * changes when the new track's audio reaches the output, not when its decoder opens.
 *
 * The position comes from the bytes that have crossed, not from a clock; see [Heard].
 *
 * A song whose connection drops is picked up where it was heard. A track that ended with
 * [Ending.Dropped], or that finished well before the row's length, keeps its row, keeps
 * the transport Playing with `connecting` set, and is opened again at the byte-counted
 * position the output had read up to, on [StreamReconnect]'s schedule; see [StreamResume]
 * and [dropped]. A track that ended with [Ending.Broke] is skipped at once.
 */
@Suppress("TooManyFunctions") // it implements PlaybackBackend and translates it into one track's verbs
internal class StreamBackend(
    tracks: List<Track>,
    private val scope: CoroutineScope,
    /**
     * One queue row's audio, not yet started, or null when there is none to be had.
     *
     * It is given the row and not only its address, because what the server is asked for
     * can depend on the file. It is called each time a row is about to play, so an account
     * that changed on the pack's own screen is used from the next open on.
     */
    private val open: (track: Track, positionMs: Long) -> StreamAudio?,
    /**
     * Where the samples are heard. Null drives the transport and the state with nothing
     * rendering, as the JVM tests do, and turns off the capabilities that need an output.
     */
    private val out: AudioOut? = null,
    startIndex: Int = 0,
    private val random: Random = Random.Default,
    /**
     * Whether there is a network to pick a dropped song up over; see [StreamResume]. The
     * default assumes one, and retries then run on the clock alone.
     */
    network: NetworkWatch = NetworkWatch.Assumed,
    /** The clock the resume budget is kept on, in milliseconds. */
    clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) : PlaybackBackend {
    private val _state =
        MutableStateFlow(
            BackendState(
                queue = tracks,
                currentIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)),
            ),
        )
    override val state: StateFlow<BackendState> = _state

    /**
     * What this backend can do. The equalizer, the balance and the rack are offered only
     * when there is an [out] to apply them.
     */
    override val capabilities =
        if (out != null) RENDERING else RENDERING.copy(hasEqualizer = false, hasBalance = false, hasDsp = false)

    /** The output's tap, when there is an output. */
    override val audioTap get() = out?.tap

    /**
     * What the output is reading, from the last discard to the next one; null
     * while nothing is.
     */
    private var stream: Stream? = null

    /**
     * The track the listener is hearing, or null when there is none. With none, a seek has
     * nothing to act on and a track has to be opened afresh.
     */
    private var playing: Heard? = null

    /** The track behind [playing] in the same stream, opened and not yet heard; see [plan]. */
    private var upcoming: Upcoming? = null

    /** The track a plan was last made for, so it is made once a track; see [plan]. */
    private var plannedFor: Heard? = null

    /** Winamp's Stop after current track, as the menu last set it; see [ended]. */
    private var stopAfterCurrent = false

    /** Whose volume the slider is; see [setVolumeMode]. */
    private var volumeMode = VolumeMode.DEVICE

    /** Paused by a call or a prompt and not by the listener, so that its end resumes the music. */
    private var pausedForNow = false

    /** How many tracks in a row would not play; any audio at all clears it. See [broke]. */
    private var failures = 0

    /** A song that dropped, waiting to be picked up again; see [dropped]. */
    private val resume =
        StreamResume(
            scope = scope,
            network = network,
            policy = StreamReconnect(clock, random),
            redial = ::redial,
            gaveUp = { broke(lost = true) },
            changed = ::reconnecting,
        )

    init {
        // the output reports a call, a prompt, another app or unplugged headphones; what
        // that means for the transport is decided in interrupted()
        out?.setInterruptions(::interrupted)
    }

    override fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
    ) = obey { TransportRules.setQueue(it, tracks, startIndex) }

    override fun enqueue(tracks: List<Track>) = obey { TransportRules.enqueue(it, tracks) }

    override fun patchTracks(patched: List<Track>) {
        _state.value = _state.value.copy(queue = QueuePatch.apply(_state.value.queue, patched))
    }

    override fun play() = obey(TransportRules::play)

    override fun pause() = obey(TransportRules::pause)

    override fun stop() = obey(TransportRules::stop)

    override fun next() = obey(TransportRules::next)

    override fun previous() = obey(TransportRules::previous)

    override fun playAt(index: Int) = obey { TransportRules.playAt(it, index) }

    /**
     * Move inside the track.
     *
     * The position is published before the decoder has read from the new place, so the
     * readout follows a drag at once. [Heard] is told where its count starts again from at
     * the same time.
     */
    override fun seekTo(positionMs: Long) {
        val waiting = resume.pending
        resume.cancel()
        val before = _state.value
        val after = TransportRules.seekTo(before, positionMs)
        _state.value = after
        // a song waiting to be picked up after a drop has nothing open to move, so it is
        // opened at the new place
        if (waiting && after.transport == Transport.Playing) {
            reload(after)
            return
        }
        val heard = live()
        // with nothing decoding there is nothing to move: the position in the state is
        // where the next track opens
        if (after.positionMs == before.positionMs || heard == null) return
        // a decoder that has finished has no thread left to seek, and a stream the server
        // produces as it sends it has nothing to seek in; both are opened again at the new
        // place. See mustReopen()
        if (mustReopen(heard)) {
            reload(after)
            if (after.transport == Transport.Paused) out?.pause()
            return
        }
        heard.from(after.positionMs)
        heard.audio.seekTo(after.positionMs)
        // the track behind this one was opened for an end that is no longer near
        dropPlan()
        // drops what was decoded for the old position, which would otherwise be heard first
        out?.discard()
    }

    override fun setVolume(fraction: Float) {
        _state.value = _state.value.copy(volumeFraction = fraction.coerceIn(0f, 1f))
        applyGain()
    }

    /**
     * Whose volume the slider moves. In [VolumeMode.DEVICE] the slider is the phone's media
     * volume, so no gain is applied here; applying both would multiply them.
     */
    override fun setVolumeMode(mode: VolumeMode) {
        volumeMode = mode
        applyGain()
    }

    /** Shuffle and repeat decide what follows, so a track already opened to follow may be the wrong one. */
    override fun setShuffle(enabled: Boolean) {
        _state.value = _state.value.copy(shuffle = enabled)
        dropPlan()
    }

    override fun setRepeat(enabled: Boolean) {
        _state.value = _state.value.copy(repeat = enabled)
        dropPlan()
    }

    /**
     * Winamp's Stop after current track. It is read at the end of every track, because
     * this backend owns the advance. A track that would not play does not count: the queue
     * still moves past it.
     */
    override fun setStopAfterCurrent(on: Boolean) {
        stopAfterCurrent = on
        dropPlan()
    }

    override fun setEqualizer(settings: EqSettings) {
        out?.setEqualizer(settings)
    }

    override fun setBalance(balance: Float) {
        out?.setBalance(balance)
    }

    override fun setDsp(rack: RackSettings) {
        out?.setDsp(rack)
    }

    override fun setPlugins(sources: List<String>) {
        out?.setPlugins(sources)
    }

    override fun release() {
        resume.cancel()
        out?.setInterruptions(null)
        out?.stop()
        letGo()
        endStream()
    }

    /**
     * Applies one of Winamp's rules and then acts on the difference between the state
     * before and after, not on the verb that was called.
     *
     * A rule that landed on a new track opens one, and a pause that toggled back into
     * playing resumes. A track that ends by itself goes through [ended].
     */
    private fun obey(rule: (BackendState) -> BackendState) {
        // any press ends the wait to pick up a dropped song; the rule below opens the row
        // again where its result is Playing
        resume.cancel()
        val before = _state.value
        val ruled = rule(before)
        // a new press to play is a new attempt, so the last notice about the source is
        // cleared
        val after =
            if (ruled.transport == Transport.Playing && before.transport != Transport.Playing) {
                ruled.copy(notice = null)
            } else {
                ruled
            }
        _state.value = after
        val moved = movedTrack(before, after)
        // a press of the listener's own replaces a pause made for a call, so the call's end
        // does not undo it; a queue edit is not such a press
        if (moved || after.transport != before.transport) pausedForNow = false
        // an edited queue may have a different row after this one, or none
        if (after.queue != before.queue) dropPlan()
        act(before, after, moved)
    }

    /** Carries out the difference [obey] found. */
    private fun act(
        before: BackendState,
        after: BackendState,
        moved: Boolean,
    ) {
        when {
            after.transport != Transport.Playing -> quiet(after)

            moved || before.transport == Transport.Stopped || playing == null -> reload(after)

            restarted(before, after) -> restart(from = before.transport)

            before.transport == Transport.Paused -> resume()

            // playing on as it was, with the queue edited around the track
            else -> Unit
        }
    }

    /**
     * Paused or stopped.
     *
     * A pause keeps the decoder and what it has decoded: nothing drains the pipe, the
     * decoder's write blocks, and it stops by itself. A stop releases the track, and the
     * next play opens it again.
     */
    private fun quiet(after: BackendState) {
        resume.silent()
        if (after.transport == Transport.Paused) {
            out?.pause()
        } else {
            letGo()
            endStream()
            out?.stop()
        }
    }

    /**
     * The row [after] is on, opened and decoding from [after]'s position, in a stream of
     * its own.
     *
     * Reached only for something the listener did (a skip, a pick, a play that has to open
     * again), so whatever is still waiting in the output is discarded. A track that ends by
     * itself continues in its stream; see [ended].
     */
    private fun reload(after: BackendState) {
        letGo()
        endStream()
        out?.discard()
        val audio = after.currentTrack?.let { open(it, after.positionMs) }
        val fresh = Stream().also { stream = it }
        // the output starts draining before the decoder starts: the pipe is bounded, and
        // the decoder blocks on a full one
        out?.start(fresh)
        out?.resume()
        playing = audio?.let { hear(it, after.positionMs, fresh) }
        // A row with no stream behind it is treated as one whose stream broke. It is
        // launched and not called, because this is inside obey() and broke() applies
        // another rule: called directly, a queue of unplayable rows would recurse once per
        // row.
        if (playing == null) scope.launch { broke() }
    }

    /**
     * [track] opened from [positionMs], behind whatever [into] is already
     * reading, and started; null when there is no audio to be had for it.
     */
    private fun listen(
        track: Track?,
        positionMs: Long,
        into: Stream,
        resumed: Boolean = false,
    ): Heard? = track?.let { open(it, positionMs) }?.let { hear(it, positionMs, into, resumed) }

    /** [audio], already opened, put behind whatever [into] is reading, and started. */
    private fun hear(
        audio: StreamAudio,
        positionMs: Long,
        into: Stream,
        resumed: Boolean = false,
    ): Heard {
        val heard = Heard(audio, positionMs, resumed)
        into.follow(heard)
        audio.start()
        return heard
    }

    /**
     * Back to the top of what is already open: a seek, not a second fetch from the server.
     * From a pause the output is resumed as well.
     */
    private fun restart(from: Transport) {
        val heard = live() ?: return
        // a decoder that has finished, or a stream that cannot be sought, is opened again
        // from the top; see seekTo()
        if (mustReopen(heard)) {
            reload(_state.value)
            return
        }
        heard.from(0)
        heard.audio.seekTo(0)
        dropPlan()
        out?.discard()
        if (from == Transport.Paused) resume()
    }

    /** Draining again, from what the pipe still holds; see [quiet]. */
    private fun resume() {
        if (playing == null) return
        stream?.let { out?.start(it) }
        out?.resume()
    }

    /**
     * Releases the track playing and the one behind it.
     *
     * The stream they were in is left open: a track that broke is followed in the same
     * one; see [advance] and [endStream].
     *
     * Releasing a decoder waits, bounded, for its thread to stop, and that wait happens
     * here on the backend's dispatcher, so that the track opened next finds the codec
     * released. A decoder that has already ended is joined at once.
     */
    private fun letGo() {
        plannedFor = null
        val ahead = upcoming
        val heard = playing
        upcoming = null
        playing = null
        stream?.forget()
        ahead?.heard?.audio?.release()
        heard?.audio?.release()
    }

    /** The output's reading ends here: the next thing to play starts a stream of its own. */
    private fun endStream() {
        stream?.close()
        stream = null
    }

    /**
     * Whether moving inside [heard] means opening it again.
     *
     * A decoder that has finished has no thread left to seek. A stream the server
     * transcodes as it sends it is a 200 with no byte ranges, so `MediaExtractor` cannot
     * seek in it. Both are moved by a new request: the pack puts the offset in the URL,
     * and the count starts from there.
     */
    private fun mustReopen(heard: Heard): Boolean = heard.audio.ending != null || !heard.audio.seekable

    /** The one playing, unless it has already been decoded to its end. */
    private fun live(): Heard? = playing?.takeUnless { it.over }

    /** The slider's level in [VolumeMode.APP], and full gain in [VolumeMode.DEVICE]. */
    private fun applyGain() {
        out?.setVolume(if (volumeMode == VolumeMode.APP) _state.value.volumeFraction else 1f)
    }

    /**
     * Whether the rule landed on another track, and not on the same one at another row of
     * an edited queue.
     *
     * Compared by id, because an edit that keeps the playing track moves its row and not
     * the music. A changed row with the same id counts only when the queue is unchanged,
     * which is a skip onto a second copy of the same track.
     */
    private fun movedTrack(
        before: BackendState,
        after: BackendState,
    ): Boolean {
        if (before.currentTrack?.id != after.currentTrack?.id) return true
        if (before.currentIndex == after.currentIndex) return false
        return before.queue.map { it.id } == after.queue.map { it.id }
    }

    /** Back to the top of the same track: play pressed while playing, or the same row picked again. */
    private fun restarted(
        before: BackendState,
        after: BackendState,
    ) = after.positionMs == 0L && before.positionMs != 0L

    /**
     * Opens what follows [heard] once its end is near, behind it in the same stream.
     *
     * Near is within [AHEAD_MS] of the length the server reported. A row with no length
     * has no near end; what follows it is opened when it ends.
     *
     * A plan is made once per track, by Winamp's own advance: the shuffle pick is made
     * here, and with stop after current nothing is planned. A seek, an edited queue,
     * shuffle, repeat or stop after current drops the plan, and it is made again; see
     * [dropPlan].
     */
    private fun plan(
        heard: Heard,
        positionMs: Long,
    ) {
        val now = _state.value
        val length = now.currentTrack?.durationMs ?: 0
        val near = length > 0 && length - positionMs <= AHEAD_MS
        if (!near || plannedFor === heard || upcoming != null) return
        plannedFor = heard
        // asked first without making a shuffle pick, so a plan that comes to nothing uses
        // none
        val onward = TransportRules.trackEnded(now, pickShuffled = { 0 }, stopAfterCurrent = stopAfterCurrent)
        if (onward.transport != Transport.Playing) return
        ahead(TransportRules.trackEnded(now, random::nextInt, stopAfterCurrent = stopAfterCurrent))
    }

    /** The row [after] lands on, opened behind the one playing; false when it has no audio to be had. */
    private fun ahead(after: BackendState): Boolean {
        val track = after.currentTrack
        val heard = stream?.let { listen(track, 0, it) } ?: return false
        upcoming = Upcoming(heard, after.currentIndex, checkNotNull(track))
        return true
    }

    /**
     * Drops the plan: the track opened to follow is released, unless the output has
     * already begun reading it. In that case it is what is playing, and the readout
     * catches up when its audio arrives.
     */
    private fun dropPlan() {
        plannedFor = null
        val ahead = upcoming ?: return
        if (stream?.unqueue(ahead.heard) != true) return
        upcoming = null
        ahead.heard.audio.release()
    }

    /**
     * The track behind the one playing has been reached and is now the current one: the
     * old decoder is released and the state lands on its row. The row is found by id when
     * the queue has been edited since the track was opened.
     */
    private fun landOn(ahead: Upcoming): BackendState {
        val finished = playing
        playing = ahead.heard
        upcoming = null
        plannedFor = null
        finished?.audio?.release()
        val now = _state.value
        val queue = now.queue
        val index =
            when {
                queue.getOrNull(ahead.index)?.id == ahead.track.id -> ahead.index
                queue.any { it.id == ahead.track.id } -> queue.indexOfFirst { it.id == ahead.track.id }
                else -> ahead.index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
            }
        return now.copy(currentIndex = index, positionMs = 0).withStationsAtRest()
    }

    /**
     * A provider has no more to give.
     *
     * Called on the backend's dispatcher. A provider the queue has already moved past is
     * ignored.
     *
     * The track behind the one playing can end before a sample of it was heard. Its row
     * then becomes the current one at once, and the ending is handled as that row's.
     *
     * A track opened early that broke is skipped like any other. One whose connection was
     * lost while it waited is a drop like any other, picked up from its top; see [cutOff].
     */
    private fun finished(
        heard: Heard,
        ending: Ending?,
    ) {
        val ahead = upcoming
        if (ahead != null && heard === ahead.heard) _state.value = landOn(ahead)
        if (heard !== playing) return
        when {
            ending is Ending.Broke -> broke()
            upcoming == null && cutOff(heard, ending) -> dropped(heard)
            else -> ended()
        }
    }

    /**
     * Whether [heard] stopped because the connection was lost, and not because the song
     * was over.
     *
     * [Ending.Dropped] says so. A finish can mean it too: the platform's extractor usually
     * reports a connection lost under it as the end of the file, so a finish more than
     * [AHEAD_MS] before the row's length counts as a drop.
     *
     * Two early finishes are not drops. One is a track that was itself a pick-up and gave
     * no audio: the row's length was wrong, and the file ends where it was opened. The
     * other is a finish with the next track already in the stream, which the caller checks.
     */
    private fun cutOff(
        heard: Heard,
        ending: Ending?,
    ): Boolean {
        if (ending is Ending.Dropped) return true
        if (ending != Ending.Finished || (heard.resumed && !heard.gave)) return false
        val length = _state.value.currentTrack?.durationMs ?: 0
        return length > 0 && heard.position < length - AHEAD_MS
    }

    /**
     * The playing track's connection was lost: the row stays, the transport stays Playing,
     * and the track is opened again at the place the listener had heard up to.
     *
     * That place is the byte count ([Heard.position]) and not the decoder's position,
     * which is ahead by what its pipe held. For a stream that cannot be sought, whose
     * offset a server may honor only in whole seconds, the position is rounded up to the
     * next second, which leaves a gap of under a second and repeats nothing. Nothing is
     * discarded.
     *
     * The output is paused for the wait, and the stream parks its reader; see
     * [Stream.park].
     *
     * A track that drops while paused is only released. The play that ends the pause opens
     * it again from the same place.
     */
    private fun dropped(heard: Heard) {
        val now = _state.value
        val length = now.currentTrack?.durationMs ?: 0
        val reached = heard.position
        val from = if (heard.audio.seekable) reached else (reached + MS_PER_SECOND - 1) / MS_PER_SECOND * MS_PER_SECOND
        letGo()
        _state.value = now.copy(positionMs = if (length > 0) from.coerceAtMost(length) else from)
        if (now.transport != Transport.Playing) return
        out?.pause()
        resume.dropped()
    }

    /**
     * One try at picking the dropped song up: the same row, opened at the position
     * [dropped] left in the state, behind whatever the stream still holds.
     *
     * Its first bytes end the wait; see [at]. A try that drops again comes back through
     * [finished]. A row that has no audio any more (the account signed out meanwhile) is
     * skipped.
     */
    private fun redial() {
        val now = _state.value
        val into = stream
        if (now.transport != Transport.Playing || into == null || playing != null) return
        playing = listen(now.currentTrack, now.positionMs, into, resumed = true)
        out?.resume()
        if (playing == null) {
            resume.cancel()
            scope.launch { broke() }
        }
    }

    /** A resume began or ended: `connecting` follows, and the stream parks or wakes its reader. */
    private fun reconnecting() {
        val waiting = resume.pending
        stream?.park(waiting)
        if (_state.value.connecting != waiting) _state.value = _state.value.copy(connecting = waiting)
    }

    /**
     * The track was decoded to its end.
     *
     * What follows is [TransportRules]' decision, and it continues in the same stream.
     * When the next track was opened already, the output reads on into it and the row
     * changes when its audio arrives; see [at]. Otherwise it is opened now, without a
     * discard.
     */
    private fun ended() {
        // not playing, or the next track is already in the stream: nothing to decide here
        if (_state.value.transport != Transport.Playing || upcoming != null) {
            if (upcoming == null) letGo()
            return
        }
        val after = TransportRules.trackEnded(_state.value, random::nextInt, stopAfterCurrent = stopAfterCurrent)
        if (after.transport != Transport.Playing) {
            _state.value = after
            letGo()
            endStream()
            out?.stop()
        } else if (!ahead(after)) {
            // a row with no stream behind it: no audio will arrive to move the row, so it
            // moves now and is skipped
            _state.value = after
            broke()
        }
    }

    /**
     * A track that would not play: on to the next.
     *
     * The failures in a row are counted. When they reach the size of the queue, or there
     * is no next track, playback stops with [BackendNotice.SourceCannotPlay]. Any audio
     * clears the count; see [at].
     *
     * [lost] is a song given up on after its connection dropped and did not come back
     * within the budget. It is skipped as well, and [BackendNotice.ServerLost] is raised
     * at once, and not again while that notice stands.
     */
    private fun broke(lost: Boolean = false) {
        letGo()
        // a failure that arrives when nothing is playing is ignored
        if (_state.value.transport != Transport.Playing) return
        failures++
        val said = _state.value.notice == BackendNotice.ServerLost
        // asked without making a shuffle pick; under shuffle there is always a next track,
        // so the count is what ends it
        val onward = TransportRules.trackEnded(_state.value, pickShuffled = { 0 })
        if (onward.transport == Transport.Playing && failures < _state.value.queue.size) {
            val after = TransportRules.trackEnded(_state.value, random::nextInt)
            advance(if (lost && !said) after.raising(BackendNotice.ServerLost) else after)
            // the output was paused for the wait that ended here
            if (lost) out?.resume()
        } else {
            val notice = if (lost) BackendNotice.ServerLost else BackendNotice.SourceCannotPlay
            obey { TransportRules.stop(it).let { stopped -> if (said) stopped else stopped.raising(notice) } }
        }
    }

    /**
     * Past a track that would not play, in the stream it was in.
     *
     * Nothing is discarded: what is still waiting in the stream is the end of the last
     * track that did play. The row moves at once.
     */
    private fun advance(after: BackendState) {
        _state.value = after
        playing = stream?.let { listen(after.currentTrack, after.positionMs, it) }
        // launched for the same reason as in reload()
        if (playing == null) scope.launch { broke() }
    }

    /**
     * Where the music is, from the bytes that have crossed.
     *
     * Ignored unless the transport is Playing and the provider is the current one or the
     * one opened behind it: a late read from a track already left must not move the
     * position.
     *
     * The first bytes of the track behind the one playing are when its row becomes the
     * current one.
     */
    private fun at(
        heard: Heard,
        positionMs: Long,
    ) {
        if (_state.value.transport != Transport.Playing) return
        val ahead = upcoming?.takeIf { heard === it.heard }
        if (heard !== playing && ahead == null) return
        // audio ends a wait to pick a song up; told first, so that the state below is no
        // longer connecting
        resume.sounding()
        // audio clears the failure count and any notice
        val now = if (ahead != null) landOn(ahead) else _state.value
        _state.value = now.copy(positionMs = positionMs, notice = null)
        failures = 0
        plan(heard, positionMs)
    }

    /**
     * The output lost the device, or got it back.
     *
     * Only a pause for a moment is undone when the moment ends. Anything the listener
     * pressed in between wins; see [obey].
     */
    private fun interrupted(interruption: AudioOut.Interruption) {
        val playing = _state.value.transport == Transport.Playing
        when (interruption) {
            AudioOut.Interruption.PAUSE -> {
                // for good: an earlier pause for a moment is not resumed either
                pausedForNow = false
                if (playing) obey(TransportRules::pause)
            }

            AudioOut.Interruption.PAUSE_FOR_NOW -> {
                if (playing) {
                    obey(TransportRules::pause)
                    pausedForNow = true
                }
            }

            AudioOut.Interruption.RESUME -> {
                val ours = pausedForNow && _state.value.transport == Transport.Paused
                pausedForNow = false
                if (ours) obey(TransportRules::play)
            }
        }
    }

    /** The track behind the one playing: open, queued in the stream, and the row it was chosen from. */
    private class Upcoming(
        val heard: Heard,
        val index: Int,
        val track: Track,
    )

    /**
     * What the output reads for as long as nothing is to be thrown away: one track, then
     * the one after it.
     *
     * It answers -1 only when it has been closed (a stop, or a discard that starts a
     * stream of its own), so the output's pipe does not end between tracks.
     *
     * The swap happens on the reading thread, in the read that found the last track
     * exhausted, when the next one was already handed over. When the next one is not there
     * yet, a read waits up to [PATIENCE_MS] for it.
     *
     * The tracks are [Heard]s, so each one counts its own bytes and notices its own ending.
     */
    private inner class Stream : PcmProvider {
        private val lock = ReentrantLock()

        /** Signalled when a track is handed over, or the stream is closed. */
        private val handed = lock.newCondition()

        /** What is read now. Under [lock]. */
        private var current: Heard? = null

        /** What is read once [current] has no more. Under [lock]. */
        private var queued: Heard? = null

        /** No more will be read from this stream at all. Under [lock]. */
        private var closed = false

        /** A dropped song is being waited on: a reader with nothing to read waits until it is handed something. */
        private var parked = false

        /** [heard] is read next: now, if nothing is being read, or once what is has no more. */
        fun follow(heard: Heard) =
            lock.withLock {
                if (current == null) current = heard else queued = heard
                handed.signalAll()
            }

        /** Takes [heard] back out, if it has not begun to be read; answers whether it had not. */
        fun unqueue(heard: Heard): Boolean = lock.withLock { (queued === heard).also { if (it) queued = null } }

        /** Nothing is read until something is handed over again; see [advance]. */
        fun forget() =
            lock.withLock {
                current = null
                queued = null
            }

        /**
         * While [waiting], a read with nothing to read waits until a track is handed over,
         * the stream is closed or the park ends, and not [PATIENCE_MS] at a time. The wait
         * to pick up a dropped song can last minutes.
         */
        fun park(waiting: Boolean) =
            lock.withLock {
                parked = waiting
                if (!waiting) handed.signalAll()
            }

        fun close() =
            lock.withLock {
                closed = true
                current = null
                queued = null
                handed.signalAll()
            }

        override fun read(into: ByteArray): Int {
            var source = waitForOne() ?: return nothing()
            while (true) {
                val read = source.read(into)
                if (read >= 0) return read
                source = onFrom(source) ?: return nothing()
            }
        }

        /** Only the track being read has anything decoded ahead. */
        override fun discard() {
            lock.withLock { current }?.discard()
        }

        /** What there is to read, having waited a moment for something when there was not. */
        private fun waitForOne(): Heard? =
            lock.withLock {
                while (current == null && !closed && parked) awaitHanded()
                if (current == null && !closed) {
                    try {
                        handed.await(PATIENCE_MS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // the interrupt flag is set again for the thread's owner
                        Thread.currentThread().interrupt()
                    }
                }
                current.takeUnless { closed }
            }

        /** Waits, under [lock], until [handed] is signalled; an interrupt ends a park like a signal. */
        private fun awaitHanded() {
            try {
                handed.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                parked = false
            }
        }

        /** [exhausted] has no more: the one behind it takes its place, and is answered. */
        private fun onFrom(exhausted: Heard): Heard? =
            lock.withLock {
                if (current === exhausted) {
                    current = queued
                    queued = null
                }
                current.takeUnless { closed }
            }

        /** The end of the stream once it is closed, and a wait between tracks before then. */
        private fun nothing(): Int = lock.withLock { if (closed) NO_MORE else NOTHING_YET }
    }

    /**
     * One track's samples on their way out, counted.
     *
     * The position is the count: at 44.1 kHz stereo 16-bit a byte is a fixed length of
     * time, so the bytes handed over since the last seek give the position. A clock would
     * run on while a slow server or a stalled decoder produced nothing. The count is per
     * track.
     *
     * The end of a track is noticed here too: when a read answers -1,
     * [StreamAudio.ending] is read and handed to the backend once.
     *
     * It is read on whichever thread drains the output, and what it decides is launched on
     * [scope], the backend's dispatcher. The count is guarded by a lock, because a seek
     * resets it while a read may be in flight.
     */
    private inner class Heard(
        val audio: StreamAudio,
        from: Long,
        /** Opened to pick up a song whose connection dropped; see [cutOff]. */
        val resumed: Boolean = false,
    ) : PcmProvider {
        private val lock = Any()

        /** Where the count starts from: the top of the track, or the last seek. */
        private var base = from

        /** What has crossed since [base]. */
        private var bytes = 0L

        /** Whether anything has crossed at all, since the track was opened. */
        private var any = false

        /** Where the bytes that have crossed put the music; what a dropped song is picked up from. */
        val position: Long get() = synchronized(lock) { reached() }

        /** Whether this track has handed over any audio at all. */
        val gave: Boolean get() = synchronized(lock) { any }

        /** Whether the ending has been handed over, so it is handed over once. */
        private var told = false

        /** Whether this track has been read to its end. */
        val over: Boolean get() = synchronized(lock) { told }

        /** The count starts again from [positionMs]: a seek has moved the music. */
        fun from(positionMs: Long) {
            synchronized(lock) {
                base = positionMs
                bytes = 0
            }
        }

        override fun read(into: ByteArray): Int {
            // a provider that throws is treated as one with nothing more to give: an
            // exception escaping this thread would end the process
            val read = runCatching { audio.read(into) }.getOrDefault(NO_MORE)
            if (read < 0) finish()
            if (read > 0) moved(crossed(read))
            return read
        }

        override fun discard() = audio.discard()

        /** [read] bytes have crossed, and this is where that puts the music. */
        private fun crossed(read: Int): Long =
            synchronized(lock) {
                bytes += read
                any = true
                reached()
            }

        /** The bytes as a position. Under [lock]. */
        private fun reached(): Long = base + bytes * MS_PER_SECOND / BYTES_PER_SECOND

        private fun finish() {
            val first = synchronized(lock) { (!told).also { told = true } }
            if (!first) return
            val ending = audio.ending
            scope.launch { finished(this@Heard, ending) }
        }

        private fun moved(positionMs: Long) {
            scope.launch { at(this@Heard, positionMs) }
        }
    }

    internal companion object {
        /**
         * What this backend can do when it has an output, available without building one.
         * A pack needs it for its descriptor, which the player asks for as soon as it
         * binds.
         */
        val RENDERING =
            Capabilities(
                // MediaExtractor seeks to the nearest sync point; a transcode it cannot
                // seek in is opened again at the new place
                canSeek = true,
                canEditQueue = true,
                hasEqualizer = true,
                hasBalance = true,
                hasDsp = true,
                canAttenuate = true,
            )

        /** What [PcmProvider.read] says when there will be no more. */
        const val NO_MORE = -1

        /** What [PcmProvider.read] says when there was nothing in time. */
        const val NOTHING_YET = 0

        /**
         * How near the end of a track the one after it is opened; see [plan]. Also how
         * much earlier than the row's length a finish has to come to count as a lost
         * connection; see [cutOff].
         */
        const val AHEAD_MS = 5_000L

        /**
         * How long a read of a [Stream] waits between tracks before answering
         * [NOTHING_YET]. A track handed over ends the wait at once.
         */
        const val PATIENCE_MS = 20L

        const val MS_PER_SECOND = 1_000L

        const val NANOS_PER_MILLI = 1_000_000L

        /** One second of [PcmProvider]'s format: 44.1 kHz, stereo, 16-bit. */
        const val BYTES_PER_SECOND = PcmProvider.SAMPLE_RATE_HZ.toLong() * PcmProvider.BYTES_PER_FRAME
    }
}
