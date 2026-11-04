// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.skinplayer

/**
 * A player that plays nothing, for demos and tests: every command takes effect at once, and the
 * position runs on [clock] while it plays. A capability left out of [capabilities] makes its
 * commands do nothing. [stream] is what it says of every song, as a demo shows a bitrate.
 */
class FakeSkinPlayer(
    playlist: List<Entry> = emptyList(),
    override val capabilities: Capabilities = Capabilities(),
    private val clock: () -> Long = System::currentTimeMillis,
    stream: StreamInfo = StreamInfo(),
) : SkinPlayer {
    @Volatile
    override var state: PlayerState = PlayerState(playlist = playlist, stream = stream)
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** The position at [since], and when the clock started counting from it (null while not playing). */
    private var at = 0L
    private var since: Long? = null

    /** How many times [requestOpen] was called, and with what. */
    val openRequests = ArrayList<Boolean>()

    override fun positionMs(): Long = at + (since?.let { clock() - it } ?: 0L)

    override fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun change(f: (PlayerState) -> PlayerState) {
        val next = f(state).copy(version = state.version)
        if (next == state) return
        state = next.copy(version = state.version + 1)
        listeners.forEach { it() }
    }

    private fun hold() {
        at = positionMs()
        since = null
    }

    override fun play() {
        if (state.playlist.isEmpty()) return
        if (since == null) since = clock()
        change { it.copy(transport = Transport.PLAYING) }
    }

    override fun pause() {
        if (state.transport != Transport.PLAYING) return
        hold()
        change { it.copy(transport = Transport.PAUSED) }
    }

    override fun stop() {
        hold()
        at = 0
        change { it.copy(transport = Transport.STOPPED) }
    }

    override fun next() = step(1)

    override fun previous() = step(-1)

    private fun step(by: Int) {
        val n = state.playlist.size
        if (n == 0 || !capabilities.playlist) return
        at = 0
        if (since != null) since = clock()
        change { it.copy(currentIndex = (it.currentIndex + by).mod(n)) }
    }

    override fun playTrack(index: Int) {
        if (!capabilities.playlist || index !in state.playlist.indices) return
        at = 0
        since = clock()
        change { it.copy(currentIndex = index, transport = Transport.PLAYING) }
    }

    override fun seekTo(ms: Long) {
        if (!capabilities.seek) return
        val length = state.current?.lengthMs ?: return
        at = ms.coerceIn(0, length)
        if (since != null) since = clock()
        change { it.copy(version = it.version) }
    }

    override fun setVolume(volume: Int) = change { it.copy(volume = volume.coerceIn(0, PlayerState.VOLUME_MAX)) }

    override fun setBalance(balance: Int) =
        change { it.copy(balance = balance.coerceIn(-PlayerState.BALANCE_MAX, PlayerState.BALANCE_MAX)) }

    override fun setShuffle(on: Boolean) = change { it.copy(shuffle = on) }

    override fun setRepeat(on: Boolean) = change { it.copy(repeat = on) }

    override fun setCrossfade(on: Boolean) {
        if (capabilities.crossfade) change { it.copy(crossfade = on) }
    }

    override fun setEqEnabled(on: Boolean) {
        if (capabilities.eq) change { it.copy(eqOn = on) }
    }

    override fun setEqAuto(on: Boolean) {
        if (capabilities.eq) change { it.copy(eqAuto = on) }
    }

    override fun setEqBand(
        band: Int,
        value: Int,
    ) {
        if (!capabilities.eq || band !in 0 until PlayerState.EQ_BANDS) return
        change { s -> s.copy(eqBands = s.eqBands.toMutableList().also { it[band] = value.coerceIn(0, PlayerState.EQ_MAX) }) }
    }

    override fun setPreamp(value: Int) {
        if (capabilities.eq) change { it.copy(preamp = value.coerceIn(0, PlayerState.EQ_MAX)) }
    }

    override fun resetEq() {
        if (!capabilities.eq) return
        change { it.copy(preamp = PlayerState.EQ_CENTER, eqBands = List(PlayerState.EQ_BANDS) { PlayerState.EQ_CENTER }) }
    }

    override fun requestOpen(andPlay: Boolean) {
        openRequests += andPlay
    }
}
