// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.content.Context
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.WinampState

/**
 * What the home screen widget draws, small enough to keep on disk. The widget holds no state of its
 * own: the launcher can wake it after the app's process has gone, so the song, its position and the
 * flags the window shows are a handful of fields in preferences.
 */
data class WidgetSnapshot(
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    val positionSec: Int = 0,
    val transport: Transport = Transport.Stopped,
    /**
     * Whether the clock counts down. A preference of the player's window, mirrored so the widget's
     * readout agrees with it.
     */
    val timeRemaining: Boolean = false,
    /** Where the balance thumb sits, -100..100. Shown only: the widget offers no target over it. */
    val balance: Int = 0,
    val bitrateKbps: Int? = null,
    val sampleRateKhz: Int? = null,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
    val volume: Int = DEFAULT_VOLUME,
    val stream: Boolean = false,
    /**
     * Whether the position can be moved: the backend can seek and this is not a stream. The widget
     * covers the bar in tap targets only when it is true.
     */
    val seekable: Boolean = false,
    /** One-based place of the song in the player's queue, which the marquee starts with. */
    val queueNumber: Int = 1,
    /**
     * When this was written, on the clock that counts since boot. A player that dies mid-song sends
     * no correction, so the snapshot carries its age and is read as stale when too old; see
     * [asKnown].
     */
    val writtenAt: Long = 0,
) {
    /**
     * The snapshot as far as it can still be believed.
     *
     * A claim to be playing older than [STALE_AFTER_MS] is read as Stopped: it was written by a
     * process that has gone. A negative age means the phone rebooted and reads the same, and so
     * does any snapshot read in a process with no player ([playerHere] false). Everything else in
     * it is kept.
     */
    fun asKnown(
        now: Long,
        playerHere: Boolean = true,
    ): WidgetSnapshot {
        if (transport != Transport.Playing) return this
        val age = now - writtenAt
        return if (playerHere && age in 0..STALE_AFTER_MS) this else copy(transport = Transport.Stopped)
    }

    /** The widget in words, for a screen reader: transport and track. */
    fun spoken(): String {
        val doing =
            when (transport) {
                Transport.Playing -> "playing"
                Transport.Paused -> "paused"
                Transport.Stopped -> "stopped"
            }
        val what = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
        return if (what.isBlank()) "Andamp, $doing" else "Andamp, $doing: $what"
    }

    /**
     * The snapshot as the state the window's draw functions read. The widget renders through the
     * player's own `drawMainWindow`, which reads a [WinampState]; this builds one whose playlist
     * holds the shown track at its queue position.
     */
    fun toState(): WinampState =
        WinampState().also { s ->
            // the marquee reads "index + 1." off the playlist, so the shown song sits at its real
            // place; the rows before it are the same track repeated and are never drawn
            s.playlist =
                List(queueNumber.coerceAtLeast(1)) {
                    Track(
                        id = WIDGET_TRACK_ID,
                        artist = artist,
                        title = title,
                        durationMs = durationMs,
                        bitrateKbps = bitrateKbps,
                        sampleRateKhz = sampleRateKhz,
                        isStream = stream,
                    )
                }
            s.currentIndex = queueNumber.coerceAtLeast(1) - 1
            s.transport = transport
            s.timeRemaining = timeRemaining
            s.balance = balance
            s.currentTimeSec = positionSec
            // the title scrolls a character a second, so the position in seconds is the marquee's
            // step
            s.marqueeStep = positionSec
            s.shuffle = shuffle
            s.repeat = repeat
            s.volume = volume
            // the widget is one window on a launcher, so the buttons that report
            // on the app's other windows have nothing to report
            s.eqVisible = false
            s.plVisible = false
        }

    companion object {
        /** The volume shown before any snapshot is written. */
        const val DEFAULT_VOLUME = 78

        /**
         * How long a claim to be playing is believed. Well past the slowest refresh rate, Low,
         * which steps every five seconds.
         */
        const val STALE_AFTER_MS = 60_000L

        private const val WIDGET_TRACK_ID = "widget"

        private const val PREFS = "widget"

        fun read(context: Context): WidgetSnapshot {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return WidgetSnapshot(
                title = p.getString("title", "") ?: "",
                artist = p.getString("artist", "") ?: "",
                durationMs = p.getLong("durationMs", 0),
                positionSec = p.getInt("positionSec", 0),
                transport = runCatching { Transport.valueOf(p.getString("transport", "") ?: "") }.getOrDefault(Transport.Stopped),
                timeRemaining = p.getBoolean("timeRemaining", false),
                balance = p.getInt("balance", 0),
                bitrateKbps = p.getInt("bitrateKbps", 0).takeIf { it > 0 },
                sampleRateKhz = p.getInt("sampleRateKhz", 0).takeIf { it > 0 },
                shuffle = p.getBoolean("shuffle", false),
                repeat = p.getBoolean("repeat", false),
                volume = p.getInt("volume", DEFAULT_VOLUME),
                stream = p.getBoolean("stream", false),
                seekable = p.getBoolean("seekable", false),
                queueNumber = p.getInt("queueNumber", 1),
                writtenAt = p.getLong("writtenAt", 0),
            )
        }

        fun write(
            context: Context,
            snapshot: WidgetSnapshot,
        ) {
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("title", snapshot.title)
                .putString("artist", snapshot.artist)
                .putLong("durationMs", snapshot.durationMs)
                .putInt("positionSec", snapshot.positionSec)
                .putString("transport", snapshot.transport.name)
                .putBoolean("timeRemaining", snapshot.timeRemaining)
                .putInt("balance", snapshot.balance)
                .putInt("bitrateKbps", snapshot.bitrateKbps ?: 0)
                .putInt("sampleRateKhz", snapshot.sampleRateKhz ?: 0)
                .putBoolean("shuffle", snapshot.shuffle)
                .putBoolean("repeat", snapshot.repeat)
                .putInt("volume", snapshot.volume)
                .putBoolean("stream", snapshot.stream)
                .putBoolean("seekable", snapshot.seekable)
                .putInt("queueNumber", snapshot.queueNumber)
                .putLong("writtenAt", android.os.SystemClock.elapsedRealtime())
                .apply()
        }
    }
}
