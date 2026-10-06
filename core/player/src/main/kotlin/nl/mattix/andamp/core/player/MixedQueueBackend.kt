// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.EffectSpec
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.VolumeMode
import nl.mattix.andamp.core.playback.AudioTap
import nl.mattix.andamp.core.playback.PeakReading
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.playback.QueuePatch
import nl.mattix.andamp.core.playback.TransportRules
import nl.mattix.andamp.core.playback.atRest
import kotlin.random.Random

/**
 * One playlist over several players, each row played by the player that can open it.
 *
 * The queue is cut into runs: consecutive rows one player can open. Each player is handed its
 * run as its queue and keeps its own progression inside it, so playback stays gapless within a
 * run. When a run finishes, the next one starts on the player it belongs to, and
 * [TransportRules] decides what "next" means across that boundary.
 *
 * A queue that is all one player's is one run and is handed over whole: that player's shuffle,
 * repeat and advance are used as they are. Under shuffle a mixed queue makes every row its own
 * run, because the order is decided here.
 *
 * A row no player can open, because its source is not installed or its player cannot be built,
 * is stepped over. A press that finds nothing to step onto raises
 * [BackendNotice.NothingPlayableHere].
 *
 * [Lane]s are handed in by the caller, each with a predicate for the rows it opens and a way
 * to build its player. The first lane is the phone's own player. It is built at once, and the
 * phone's volume, the effects and the audio tap are read from it. Later lanes can be added and
 * removed while this plays; see [addLane] and [removeLane].
 *
 * `PlaybackBackendContractTest` runs against this once with one lane and once with rows that
 * alternate between two.
 */
@Suppress("TooManyFunctions") // PlaybackBackend's own members, plus the handover between players
class MixedQueueBackend(
    lanes: List<Lane>,
    tracks: List<Track>,
    startIndex: Int,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
) : PlaybackBackend {
    /**
     * One player, and which rows are its.
     *
     * The player is built on first need, because building one can be expensive (a session
     * with an online service) or impossible (nobody signed in). When [make] returns null it is
     * asked again the next time a player is needed.
     */
    class Lane(
        internal val claims: (Track) -> Boolean,
        private val make: () -> PlaybackBackend?,
        /** How a built player is put away. The default releases it. */
        private val letGo: (PlaybackBackend) -> Unit = { it.release() },
    ) {
        // written on the main thread, and read by the visualizers' render threads through
        // the audio tap
        @Volatile
        internal var built: PlaybackBackend? = null
            private set

        internal fun player(): PlaybackBackend? = built ?: make()?.also { built = it }

        internal fun drop() {
            val gone = built ?: return
            built = null
            gone.stop()
            letGo(gone)
        }
    }

    /** Rows [start] until [end] are in [player]'s hands; [whole] when that is every row. */
    private class Run(
        val lane: Lane,
        val player: PlaybackBackend,
        var start: Int,
        var end: Int,
        var whole: Boolean,
    ) {
        /** Whether the player of the row after this run has been opened; see [stageIfNear]. */
        var staged = false

        operator fun contains(index: Int) = index in start until end

        val size get() = end - start

        /** Whether this run is already [other] holding [span]. */
        fun holds(
            other: PlaybackBackend,
            span: IntRange,
        ) = player === other && start == span.first && end == span.last + 1
    }

    /**
     * Every lane, the phone's first. Replaced whole and never edited in place, because the
     * visualizers' render threads read it through the audio tap while the main thread adds
     * and removes lanes.
     */
    @Volatile
    private var lanes: List<Lane> = lanes

    private var queue: List<Track> = tracks
    private var shuffle = false

    /**
     * The rows played under shuffle while the order is decided here, by id, oldest first and
     * the one playing last. Previous goes back along it, the way the music came. It holds
     * [TRAIL] rows at most and is dropped when shuffle is turned off.
     */
    private val trail = ArrayDeque<String>()
    private var repeat = false
    private var stopAfterCurrent = false

    /** The level the listener set, which the slider shows in [VolumeMode.APP]; see [volumeNow]. */
    private var volume = BackendState().volumeFraction

    /** Whose volume the slider moves. */
    private var volumeMode = VolumeMode.DEVICE

    /**
     * The last call for each listener setting (equalizer, rack, volume and so on), kept so
     * that a player built later is given the same settings.
     */
    private val told = LinkedHashMap<String, (PlaybackBackend) -> Unit>()

    // written on the main thread, and read by the visualizers' render threads through the
    // audio tap
    @Volatile
    private var run: Run? = null
    private var watching: Job? = null
    private var followingThePhone: Job? = null

    /**
     * The row of the run last heard playing, or -1. It tells the end of a run from a row that
     * never played.
     *
     * Heard means a position above zero while Playing. A player reports Playing as soon as it
     * is asked, and a file it cannot read stops a moment later the way a finished one does, so
     * the transport alone would count a failed last row as the end of the run.
     */
    private var lastHeard = -1

    /**
     * Whether the listener's last instruction was to play. A player that stops while this
     * holds has run out or given up, which is how the end of a run is told from a stop.
     *
     * It is a field and is not read off the published state, because a publish between the
     * player stopping and the watcher hearing of it would copy the stop out. A fade is a stop
     * that was asked for and clears it too.
     */
    private var meantToPlay = false

    /**
     * Whether the player in hands has reported anything but Stopped since it was last asked to
     * play.
     *
     * A player in another process answers a press a moment later and reports its earlier
     * state until then: Stopped, for one that has just been handed its queue. Only a Stopped
     * that follows another transport clears [meantToPlay].
     */
    private var answered = false

    /** Records an instruction to play that the player in hands has yet to answer. */
    private fun meanToPlay() {
        meantToPlay = true
        answered = false
    }

    /** Runs that ended or gave up since a row was last heard; see [gaveUp]. */
    private var silentEnds = 0

    private val published =
        MutableStateFlow(
            BackendState(queue = tracks, currentIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))),
        )

    override val state: StateFlow<BackendState> = published

    init {
        // the first lane is the phone's player; the effects, the audio tap and the phone's
        // volume are read from it before anything plays
        val first = lanes.firstOrNull()?.let(::playerOf)
        if (first != null) {
            published.value = published.value.copy(volumeFraction = volumeNow())
            followThePhone(first)
        }
    }

    override val capabilities: Capabilities
        get() {
            val hands = lanes.mapNotNull { it.built }
            val now = run?.player ?: hands.firstOrNull()
            return Capabilities(
                canSeek = now?.capabilities?.canSeek ?: true,
                canEditQueue = true,
                // true when any built player has it, so that a control does not appear and
                // disappear at each handover
                hasEqualizer = hands.any { it.capabilities.hasEqualizer },
                hasBalance = hands.any { it.capabilities.hasBalance },
                hasDsp = hands.any { it.capabilities.hasDsp },
                canAttenuate = hands.any { it.capabilities.canAttenuate },
            )
        }

    private val primary: PlaybackBackend? get() = run?.player ?: lanes.firstNotNullOfOrNull { it.built }

    /** The phone's own player, which reports the phone's volume. */
    private val phone: PlaybackBackend? get() = lanes.firstOrNull()?.built

    override val audioTap: AudioTap? =
        if (lanes.isEmpty()) {
            null
        } else {
            // The tap of whichever player is playing, looked up per read. A handover is a
            // jump in the sample count, as a track change is. Read from the visualizers'
            // render threads, which is why the fields behind it are volatile.
            object : AudioTap {
                private val tap get() = primary?.audioTap

                override val sampleRateHz get() = tap?.sampleRateHz ?: 0
                override val writtenSamples get() = tap?.writtenSamples ?: 0L
                override val peaks get() = tap?.peaks ?: PeakReading.NONE

                override fun readAt(
                    endSample: Long,
                    out: FloatArray,
                ) = tap?.readAt(endSample, out) ?: false
            }
        }

    // the first lane describes the rack: it is the one that finds the plug-ins
    override val effects: List<EffectSpec> get() = lanes.firstOrNull()?.built?.effects ?: super.effects

    override val effectsFlow: StateFlow<List<EffectSpec>> =
        lanes.firstOrNull()?.built?.effectsFlow ?: super.effectsFlow

    // --- the queue ---

    override fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
    ) {
        val after = TransportRules.setQueue(global(), tracks, startIndex)
        queue = tracks
        if (after.transport == Transport.Stopped) {
            letRunGo()
            published.value = after.copy(queue = queue, volumeFraction = volumeNow(), notice = null)
            return
        }
        // the current track survived the edit and goes on playing in the run it now sits in
        reshape(after.currentIndex)
    }

    override fun enqueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val r = run
        // decided before the rows are added, against the end they are added to
        val joins = r != null && reachesTheEnd(r) && tracks.all { laneFor(it) === r.lane }
        queue = queue + tracks
        if (r != null && joins) {
            // still one unbroken stretch of one player's rows, so the player appends; a new
            // queue would prepare the current track again
            r.player.enqueue(tracks)
            r.end = queue.size
            r.staged = false
            publish()
            return
        }
        if (r != null) reshape(cursor) else publish()
    }

    override fun patchTracks(patched: List<Track>) {
        queue = QueuePatch.apply(queue, patched)
        run?.player?.patchTracks(patched)
        publish()
    }

    // --- the transport ---

    override fun play() {
        if (queue.isEmpty()) return
        silentEnds = 0
        val r = run
        if (r != null && cursor in r) {
            meanToPlay()
            // a start from stopped has to be heard again; a resume from pause keeps the row
            // last heard
            if (published.value.transport != Transport.Paused) lastHeard = -1
            r.player.play()
            publish()
        } else {
            startAt(cursor)
        }
    }

    override fun pause() {
        run?.player?.pause()
        publish()
    }

    override fun stop() {
        meantToPlay = false
        val r = run
        if (r == null) {
            published.value = TransportRules.stop(global())
            return
        }
        r.player.stop()
        publish()
    }

    override fun stopWithFadeout() {
        val r = run ?: return stop()
        // the stop the fade ends in was asked for
        meantToPlay = false
        r.player.stopWithFadeout()
        publish()
    }

    override fun next() = step(TransportRules.next(global(), random::nextInt), forward = true) { it.next() }

    override fun previous() {
        // a player holding the whole queue goes back through its own shuffle
        val back = if (shuffle && run?.whole != true) heardBefore() else null
        val target = if (back != null) TransportRules.jumpTo(global(), back) else TransportRules.previous(global())
        step(target, forward = false) { it.previous() }
    }

    /**
     * The row heard before this one under shuffle, with the trail cut back to it; null when
     * the trail holds no earlier row that is still in the queue.
     */
    private fun heardBefore(): Int? {
        while (trail.size > 1) {
            trail.removeLast()
            val at = queue.indexOfFirst { it.id == trail.last() }
            if (at >= 0) return at
        }
        return null
    }

    override fun playAt(index: Int) {
        if (queue.isEmpty()) return
        silentEnds = 0
        val r = run
        if (r != null && r.whole) {
            meanToPlay()
            r.player.playAt(index)
            publish()
        } else {
            startAt(index.coerceIn(queue.indices))
        }
    }

    override fun seekTo(positionMs: Long) {
        // a seek while stopped attaches the row's player, so that play starts from the
        // position sought to
        val r = run ?: attachAt(cursor, Walk())
        if (r == null) {
            published.value = TransportRules.seekTo(global(), positionMs)
            return
        }
        r.player.seekTo(positionMs)
        publish()
    }

    override fun setShuffle(enabled: Boolean) {
        shuffle = enabled
        if (!enabled) trail.clear()
        if (run != null) reshape(cursor) else publish()
    }

    override fun setRepeat(enabled: Boolean) {
        repeat = enabled
        run?.takeIf { it.whole }?.player?.setRepeat(enabled)
        publish()
    }

    override fun setStopAfterCurrent(on: Boolean) {
        stopAfterCurrent = on
        tell("stopAfterCurrent") { it.setStopAfterCurrent(on) }
    }

    // --- the listener's settings, which every player gets ---

    override fun setVolume(fraction: Float) {
        volume = fraction.coerceIn(0f, 1f)
        tell("volume") { it.setVolume(volume) }
        publish()
    }

    override fun setVolumeMode(mode: VolumeMode) {
        volumeMode = mode
        tell("volumeMode") { it.setVolumeMode(mode) }
        if (mode == VolumeMode.APP) {
            // The phone's player reports where the slider lands when the mode becomes APP.
            // Every other player is set to the same level.
            phone?.state?.value?.let { volume = it.volumeFraction }
            tell("volume") { it.setVolume(volume) }
        }
        publish()
    }

    override fun setEqualizer(settings: EqSettings) = tell("equalizer") { it.setEqualizer(settings) }

    override fun setBalance(balance: Float) = tell("balance") { it.setBalance(balance) }

    override fun setDsp(rack: RackSettings) = tell("dsp") { it.setDsp(rack) }

    override fun setPlugins(sources: List<String>) = tell("plugins") { it.setPlugins(sources) }

    override fun keepAlive() = lanes.forEach { it.built?.keepAlive() }

    override fun teardown() = lanes.forEach { it.built?.teardown() }

    override fun release() {
        followingThePhone?.cancel()
        letRunGo()
        lanes.forEach { it.drop() }
    }

    /**
     * Adds a source that was not there when this was built. Its rows were stepped over and are
     * this lane's from the next time one is reached.
     *
     * Nothing is built here: a lane builds its player the first time one of its rows needs
     * it. A lane that is already present is not added twice.
     */
    fun addLane(lane: Lane) {
        if (lanes.any { it === lane }) return
        lanes = lanes + lane
    }

    /**
     * Removes a source: its player is put away and its rows are stepped over. If one of them
     * was playing, playback stops there.
     *
     * The first lane is the phone's and is never removed: the phone's volume, the rack and the
     * audio tap are read from it.
     */
    fun removeLane(lane: Lane) {
        if (lane === lanes.firstOrNull() || lanes.none { it === lane }) return
        lanes = lanes.filterNot { it === lane }
        forget(lane)
    }

    /**
     * Puts one lane's player away, for example when its account signed out. Its rows stay in
     * the queue and are stepped over until the player can be built again.
     */
    fun forget(lane: Lane) {
        if (run?.lane === lane) {
            letRunGo()
            published.value = TransportRules.stop(global())
        }
        lane.drop()
    }

    // --- runs ---

    private val cursor: Int get() = published.value.currentIndex

    private fun global() = published.value.copy(queue = queue, shuffle = shuffle, repeat = repeat)

    private fun laneFor(track: Track): Lane? = lanes.firstOrNull { it.claims(track) }

    /** The lane's player, built if it can be, and given every stored setting when it is new. */
    private fun playerOf(lane: Lane): PlaybackBackend? {
        val fresh = lane.built == null
        val player = lane.player() ?: return null
        if (fresh) told.values.forEach { it(player) }
        return player
    }

    private fun tell(
        key: String,
        act: (PlaybackBackend) -> Unit,
    ) {
        told[key] = act
        lanes.forEach { lane -> lane.built?.let(act) }
    }

    /**
     * The level the slider shows. In [VolumeMode.DEVICE] that is the phone's volume, which
     * the phone's player reports. In [VolumeMode.APP] it is the listener's own setting, which
     * every player is told.
     */
    private fun volumeNow(): Float =
        if (volumeMode == VolumeMode.DEVICE) phone?.state?.value?.volumeFraction ?: volume else volume

    /**
     * Follows the phone's volume, which the rocker, the system panel or another app can move,
     * while the slider is in [VolumeMode.DEVICE].
     */
    private fun followThePhone(player: PlaybackBackend) {
        followingThePhone =
            scope.launch {
                player.state
                    .map { it.volumeFraction }
                    .distinctUntilChanged()
                    .collect { fraction ->
                        val shown = published.value
                        if (volumeMode == VolumeMode.DEVICE && shown.volumeFraction != fraction) {
                            published.value = shown.copy(volumeFraction = fraction)
                        }
                    }
            }
    }

    /**
     * One look along the queue for something to play, asking each lane for its player at most
     * once, because asking can be a disk read and a native call.
     *
     * [passOver] is a lane whose player has just reported that nothing will play from it. Its
     * rows are stepped over as if it had no player.
     */
    private inner class Walk(
        private val passOver: Lane? = null,
    ) {
        private val answers = HashMap<Lane, PlaybackBackend?>()

        fun player(lane: Lane): PlaybackBackend? {
            if (lane === passOver) return null
            if (!answers.containsKey(lane)) answers[lane] = playerOf(lane)
            return answers[lane]
        }
    }

    /**
     * Plays from [index], stepping over rows no player can open: forward the way a track
     * ending moves, shuffle and repeat included, or backward the way the previous button does.
     */
    private fun startAt(
        index: Int,
        forward: Boolean = true,
    ) {
        val walk = Walk()
        if (!playFirstFrom(index, forward, walk)) nothingFrom(index, walk)
    }

    /** Plays the first row from [index] on that [walk] finds a player for; false when there is none. */
    private fun playFirstFrom(
        index: Int,
        forward: Boolean,
        walk: Walk,
    ): Boolean {
        for (at in rowsFrom(index, forward)) {
            val r = attachAt(at, walk) ?: continue
            meanToPlay()
            lastHeard = -1
            r.player.playAt(at - r.start)
            // going back does not add to the way back
            if (shuffle && forward) remember(at)
            publish()
            return true
        }
        return false
    }

    private fun remember(at: Int) {
        val id = queue[at].id
        if (trail.lastOrNull() == id) return
        trail.addLast(id)
        if (trail.size > TRAIL) trail.removeFirst()
    }

    /**
     * The rows a walk from [index] tries, in order: [index] itself, then onward.
     *
     * Forward under shuffle it is every other row once, in a random order, so that a row that
     * can play is always reached.
     */
    private fun rowsFrom(
        index: Int,
        forward: Boolean,
    ): Sequence<Int> =
        sequence {
            yield(index)
            if (forward && shuffle) {
                yieldAll(queue.indices.filter { it != index }.shuffled(random))
                return@sequence
            }
            var at = index
            repeat(queue.size - 1) {
                at = following(at, forward) ?: return@sequence
                yield(at)
            }
        }

    private fun following(
        at: Int,
        forward: Boolean,
    ): Int? {
        if (!forward) return (at - 1 + queue.size) % queue.size
        val after = TransportRules.trackEnded(global().copy(currentIndex = at, transport = Transport.Playing), random::nextInt)
        return after.currentIndex.takeIf { after.transport == Transport.Playing }
    }

    /**
     * Nothing to step onto: stops at [index], and raises [BackendNotice.NothingPlayableHere]
     * when no row anywhere can play. The notice is raised again on every such press.
     */
    private fun nothingFrom(
        index: Int,
        walk: Walk,
    ) {
        letRunGo()
        // per lane, with the answers the walk already has
        val none = lanes.none { lane -> queue.any { laneFor(it) === lane } && walk.player(lane) != null }
        val stopped = TransportRules.stop(global().copy(currentIndex = index)).copy(volumeFraction = volumeNow())
        published.value = if (none) stopped.raising(BackendNotice.NothingPlayableHere) else stopped.copy(notice = null)
    }

    /** The row's run, in its player's hands; null when no player can open the row. */
    private fun attachAt(
        at: Int,
        walk: Walk,
    ): Run? {
        val lane = queue.getOrNull(at)?.let(::laneFor) ?: return null
        val player = walk.player(lane) ?: return null
        val span = spanAround(at, lane)
        val r = run
        if (r?.holds(player, span) == true) return r
        if (r != null && r.player !== player) r.player.stop()
        // between two runs nothing is playing, so the rows go back to their resting form
        queue = queue.map { it.atRest() }
        val whole = span.first == 0 && span.last == queue.lastIndex
        player.setShuffle(whole && shuffle)
        player.setRepeat(whole && repeat)
        player.setQueue(queue.subList(span.first, span.last + 1), at - span.first)
        return Run(lane, player, span.first, span.last + 1, whole).also { attached ->
            run = attached
            lastHeard = -1
            watch(attached)
        }
    }

    /** The rows around [at] that [lane] can play in one go: all of them, or the unbroken stretch. */
    private fun spanAround(
        at: Int,
        lane: Lane,
    ): IntRange {
        if (queue.all { laneFor(it) === lane }) return queue.indices
        if (shuffle) return at..at
        var first = at
        while (first > 0 && laneFor(queue[first - 1]) === lane) first--
        var last = at
        while (last < queue.lastIndex && laneFor(queue[last + 1]) === lane) last++
        return first..last
    }

    /** Whether rows added to the end of the queue would join [r]'s stretch. */
    private fun reachesTheEnd(r: Run) = r.whole || (r.end == queue.size && !shuffle)

    /**
     * The queue changed shape under a row that is still in a player's hands. The player is
     * handed its new stretch with the row in it, only when the rows differ from the queue it
     * already holds.
     */
    private fun reshape(at: Int) {
        val r = run ?: return publish()
        val span = spanAround(at, r.lane)
        val whole = span.first == 0 && span.last == queue.lastIndex
        r.player.setShuffle(whole && shuffle)
        r.player.setRepeat(whole && repeat)
        val rows = queue.subList(span.first, span.last + 1)
        if (r.player.state.value.queue
                .map { it.id } != rows.map { it.id }
        ) {
            r.player.setQueue(rows, at - span.first)
        }
        r.start = span.first
        r.end = span.last + 1
        r.whole = whole
        publish()
    }

    private fun step(
        target: BackendState,
        forward: Boolean,
        inHands: (PlaybackBackend) -> Unit,
    ) {
        if (queue.isEmpty()) return
        silentEnds = 0
        val r = run
        when {
            r != null && r.whole -> {
                if (target.transport == Transport.Playing) meanToPlay() else meantToPlay = false
                inHands(r.player)
                publish()
            }

            target.transport == Transport.Playing -> {
                startAt(target.currentIndex, forward)
            }

            else -> {
                park(target.currentIndex)
            }
        }
    }

    /** Moves the cursor without playing, which is what a skip does while stopped. */
    private fun park(index: Int) {
        meantToPlay = false
        val r = run
        if (r != null && index in r) {
            r.player.setQueue(queue.subList(r.start, r.end), index - r.start)
            publish()
            return
        }
        letRunGo()
        published.value = global().copy(currentIndex = index, positionMs = 0, transport = Transport.Stopped)
    }

    private fun letRunGo() {
        run?.player?.stop()
        watching?.cancel()
        watching = null
        run = null
        meantToPlay = false
        // nothing is playing, so the rows go back to their resting form
        queue = queue.map { it.atRest() }
    }

    private fun watch(r: Run) {
        watching?.cancel()
        watching = scope.launch { r.player.state.collect { heard(r, it) } }
    }

    /** A state the player in hands reported: a tick, a track change, the end of its stretch or a source giving up. */
    private fun heard(
        r: Run,
        heard: BackendState,
    ) {
        if (run !== r) return
        if (!r.whole && !r.staged) stageIfNear(r, heard)
        when {
            !endedOnItsOwn(r, heard) -> publish()
            heard.notice != null -> refused(r)
            else -> runEnded(r)
        }
    }

    /**
     * Whether the player in hands stopped without being asked to: at the end of its stretch,
     * or because its source gave up.
     *
     * [meantToPlay] says it was not asked. The end of the stretch is its last row having been
     * heard playing. Any other stop, such as a row it could not open or a stop after the
     * current track, is published as it is.
     */
    private fun endedOnItsOwn(
        r: Run,
        heard: BackendState,
    ): Boolean =
        !r.whole &&
            meantToPlay &&
            heard.transport == Transport.Stopped &&
            (heard.notice == BackendNotice.SourceCannotPlay || (heard.notice == null && lastHeard == r.size - 1))

    /**
     * Opens the player of the row after a run when the run's last row is within [LEAD_MS] of
     * its end, so that the handover does not wait for the player to be built.
     *
     * Only the player is opened, not the row: what follows can still change before this run
     * ends. Not under shuffle, where what follows is undecided, and not under
     * stop-after-current.
     */
    private fun stageIfNear(
        r: Run,
        heard: BackendState,
    ) {
        if (shuffle || stopAfterCurrent) return
        if (heard.transport != Transport.Playing || heard.currentIndex != r.size - 1) return
        val length = heard.currentTrack?.durationMs?.takeIf { it > 0 } ?: return
        if (length - heard.positionMs > LEAD_MS) return
        r.staged = true
        val ending = global().copy(currentIndex = r.end - 1, transport = Transport.Playing)
        val after = TransportRules.trackEnded(ending, pickShuffled = { 0 })
        if (after.transport != Transport.Playing) return
        queue
            .getOrNull(after.currentIndex)
            ?.let(::laneFor)
            ?.takeIf { it !== r.lane }
            ?.let(::playerOf)
    }

    /** A run played to its end: starts whatever follows, on whichever player that is. */
    private fun runEnded(r: Run) {
        val ended = global().copy(currentIndex = r.end - 1, transport = Transport.Playing)
        val after = TransportRules.trackEnded(ended, random::nextInt, stopAfterCurrent)
        if (after.transport != Transport.Playing) {
            letRunGo()
            published.value = after.copy(volumeFraction = volumeNow())
            return
        }
        if (!gaveUp(ended)) startAt(after.currentIndex)
    }

    /**
     * The player in hands reported that nothing will play from its source, and stopped.
     *
     * Rows another player opens still play, so the walk goes on past this lane's rows. The
     * notice is published only when nothing else is left.
     */
    private fun refused(r: Run) {
        val ended = global().copy(currentIndex = r.end - 1, transport = Transport.Playing)
        if (gaveUp(ended)) return
        val next = following(r.end - 1, forward = true)
        if (next != null && playFirstFrom(next, forward = true, Walk(passOver = r.lane))) return
        // nothing from any other player is left, so the player's notice is published
        meantToPlay = false
        publish()
    }

    /**
     * Counts one more run that ended or gave up, and stops with
     * [BackendNotice.SourceCannotPlay] once as many as the queue has rows have passed without
     * a row being heard.
     *
     * This bounds a queue of players that each give up, which under repeat would otherwise be
     * walked round without end.
     */
    private fun gaveUp(ended: BackendState): Boolean {
        silentEnds++
        if (silentEnds < queue.size) return false
        letRunGo()
        published.value =
            TransportRules
                .stop(ended)
                .copy(volumeFraction = volumeNow())
                .raising(BackendNotice.SourceCannotPlay)
        return true
    }

    /** Publishes the composite state, from the player in hands when there is one. */
    private fun publish() {
        val r = run
        if (r == null) {
            published.value = global().copy(volumeFraction = volumeNow())
            return
        }
        val heard = r.player.state.value
        if (endedOnItsOwn(r, heard)) {
            // The watcher decides what follows the end of a run. Publishing the player's
            // stop here would show it as a stop, with the player's own cursor.
            published.value = published.value.copy(volumeFraction = volumeNow())
            return
        }
        // what the player learned about its rows (a length, a bitrate, a station's song)
        // goes back into the queue
        if (heard.queue.size == r.size) queue = queue.subList(0, r.start) + heard.queue + queue.subList(r.end, queue.size)
        if (heard.transport == Transport.Playing && heard.positionMs > 0) {
            // a position above zero while Playing counts as heard
            lastHeard = heard.currentIndex
            silentEnds = 0
        }
        if (heard.transport != Transport.Stopped) answered = true
        if (heard.transport == Transport.Stopped && answered) meantToPlay = false
        // the copy keeps the player's notice as it was raised
        published.value =
            heard.copy(
                queue = queue,
                currentIndex = r.start + heard.currentIndex.coerceIn(0, (r.size - 1).coerceAtLeast(0)),
                shuffle = shuffle,
                repeat = repeat,
                volumeFraction = volumeNow(),
            )
    }

    private companion object {
        /** How long before a run's end the next player is opened, in milliseconds. */
        const val LEAD_MS = 15_000L

        /** How many rows back Previous can go under shuffle. */
        const val TRAIL = 256
    }
}
