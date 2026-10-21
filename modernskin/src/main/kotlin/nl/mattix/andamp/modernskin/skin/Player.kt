// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/** One playlist entry. */
data class Track(
    val title: String,
    val lengthMs: Int,
)

/** The player as a modern skin sees it. Positions and lengths are milliseconds; volume is 0..255. */
interface Player {
    enum class Status { STOPPED, PLAYING, PAUSED }

    val status: Status
    val positionMs: Int
    val lengthMs: Int
    val title: String
    val bitrateKbps: Int
    val sampleRateKhz: Int
    val channels: Int

    /** The current file's tags by lowercase field name (artist, album, ...); a file without tags has none. */
    val tags: Map<String, String> get() = emptyMap()

    /** The input plug-in's name for the current file, as Winamp's `getDecoderName` reports it; empty when unknown. */
    val decoderName: String get() = ""
    var volume: Int
    var shuffle: Boolean
    var repeat: Boolean
    var crossfade: Boolean
    var eqEnabled: Boolean
    val eqBands: IntArray
    var eqPreamp: Int

    fun play()

    fun pause()

    fun stop()

    fun next()

    fun previous()

    fun eject()

    fun seekTo(ms: Int)

    fun setEqBand(
        band: Int,
        value: Int,
    )

    /** The playlist, and which entry is current; a player without one has just the current song. */
    val tracks: List<Track> get() = listOf(Track(title, lengthMs))
    val trackIndex: Int get() = 0

    /** Makes entry [index] current and plays it. */
    fun playTrack(index: Int) = play()

    /** Spectrum levels 0..1, 75 bands like Winamp's vis, or empty when nothing plays. */
    fun spectrum(): FloatArray

    /** Left and right VU 0..255. */
    fun vu(): Pair<Int, Int>
}

/** Services the app supplies to the skin: image sizes, text measuring, a log and a redraw request. */
interface SkinHost {
    fun imageSize(file: String): Pair<Int, Int>?

    fun textWidth(
        text: String,
        font: FontDef?,
        size: Int,
    ): Int

    fun textWidth(
        text: String,
        font: FontDef?,
        size: Int,
        bold: Boolean,
    ): Int = textWidth(text, font, size)

    fun log(message: String)

    fun invalidate()

    /** ARGB of one pixel of a skin image, or null when it cannot be read. */
    fun pixel(
        file: String,
        x: Int,
        y: Int,
    ): Int? = null

    /**
     * Whether one of the app's own windows is open: "pl" (playlist), "ml" (library), "eq",
     * "video" or "vis". Null leaves it to the skin's container. Buttons that toggle these light up.
     */
    fun windowOpen(kind: String): Boolean? = null
}

/** A player that plays nothing, for tests and for a first look at a skin. */
class FakePlayer : Player {
    /** The clock positions and the demo spectrum follow; a test gives it the runtime's. */
    var clock: () -> Long = System::currentTimeMillis

    override var status = Player.Status.STOPPED
    private var startedAt = 0L
    private var pausedAt = 0
    override val positionMs: Int
        get() =
            when (status) {
                Player.Status.PLAYING -> ((clock() - startedAt).toInt() + pausedAt).coerceAtMost(lengthMs)
                Player.Status.PAUSED -> pausedAt
                Player.Status.STOPPED -> 0
            }

    /** Set to show several entries; empty means the playlist is the current song alone. */
    var playlist: List<Track> = emptyList()
    private var index = 0
    override val tracks: List<Track> get() = playlist.ifEmpty { listOf(Track(title, lengthMs)) }
    override val trackIndex: Int get() = index.coerceIn(0, tracks.size - 1)

    override fun playTrack(index: Int) {
        val t = tracks.getOrNull(index) ?: return
        this.index = index
        title = t.title
        lengthMs = t.lengthMs
        status = Player.Status.STOPPED
        play()
    }

    override var lengthMs = 215_000
    override var title = "Andamp - Modern skin demo"
    override var decoderName = ""
    override var bitrateKbps = 192
    override var sampleRateKhz = 44
    override var channels = 2
    override var volume = 200
    override var shuffle = false
    override var repeat = false
    override var crossfade = false
    override var eqEnabled = false
    override val eqBands = IntArray(10) { 128 }
    override var eqPreamp = 128

    override fun play() {
        if (status == Player.Status.PLAYING) return
        if (status == Player.Status.STOPPED) pausedAt = 0
        startedAt = clock()
        status = Player.Status.PLAYING
    }

    override fun pause() {
        when (status) {
            Player.Status.PLAYING -> {
                pausedAt = positionMs
                status = Player.Status.PAUSED
            }

            Player.Status.PAUSED -> {
                play()
            }

            Player.Status.STOPPED -> {
                Unit
            }
        }
    }

    override fun stop() {
        status = Player.Status.STOPPED
        pausedAt = 0
    }

    override fun next() {
        seekTo(0)
    }

    override fun previous() {
        seekTo(0)
    }

    override fun eject() = Unit

    override fun seekTo(ms: Int) {
        pausedAt = ms.coerceIn(0, lengthMs)
        startedAt = clock()
    }

    override fun setEqBand(
        band: Int,
        value: Int,
    ) {
        if (band in eqBands.indices) eqBands[band] = value
    }

    override fun spectrum(): FloatArray {
        if (status != Player.Status.PLAYING) return FloatArray(0)
        val t = clock() / 60.0
        return FloatArray(75) { i -> (0.5 + 0.5 * Math.sin(t + i * 0.3)).toFloat() * (1f - i / 90f) }
    }

    override fun vu(): Pair<Int, Int> = if (status == Player.Status.PLAYING) 160 to 150 else 0 to 0
}
