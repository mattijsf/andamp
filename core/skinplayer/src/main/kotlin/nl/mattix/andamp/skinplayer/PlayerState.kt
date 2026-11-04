// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.skinplayer

/** Whether the player plays. [CONNECTING] is playing that has not sounded yet, as a station being reached. */
enum class Transport { STOPPED, PLAYING, PAUSED, CONNECTING }

/** One entry of the playlist: what a skin shows for it. [tags] by lowercase field name (artist, album, ...). */
data class Entry(
    val title: String,
    val lengthMs: Long,
    val tags: Map<String, String> = emptyMap(),
)

/**
 * What the decoder reports of the song playing, each null when the player cannot say: the bitrate,
 * the sample rate, the channels (1 lights a skin's mono lamp, 2 or more its stereo lamp) and the
 * name of what decodes it.
 */
data class StreamInfo(
    val bitrateKbps: Int? = null,
    val sampleRateKhz: Int? = null,
    val channels: Int? = null,
    val decoder: String = "",
)

/**
 * The player as a skin sees it at one moment. Replaced whole on every change, and [version] moves
 * with it, so a skin that kept the version knows whether anything changed. Readable from any
 * thread. The position is not in it: it moves all the time, and [SkinPlayer.positionMs] reads it.
 *
 * Units are Winamp 2's, as Andamp keeps them: [volume] 0..100, [balance] -100 (left) .. 100
 * (right), [preamp] and each of the ten [eqBands] 0..63 with [EQ_CENTER] at 0 dB.
 */
data class PlayerState(
    val version: Long = 0,
    val transport: Transport = Transport.STOPPED,
    val playlist: List<Entry> = emptyList(),
    val currentIndex: Int = 0,
    val stream: StreamInfo = StreamInfo(),
    val volume: Int = 78,
    val balance: Int = 0,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
    val crossfade: Boolean = false,
    val eqOn: Boolean = true,
    val eqAuto: Boolean = false,
    val preamp: Int = EQ_CENTER,
    val eqBands: List<Int> = List(EQ_BANDS) { EQ_CENTER },
) {
    /** The entry playing or to play, or null with an empty playlist. */
    val current: Entry? get() = playlist.getOrNull(currentIndex)

    companion object {
        const val VOLUME_MAX = 100
        const val BALANCE_MAX = 100
        const val EQ_BANDS = 10
        const val EQ_MAX = 63
        const val EQ_CENTER = 32
    }
}
