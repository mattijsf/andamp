// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.content.Context
import nl.mattix.andamp.state.VisMode

/**
 * How big the player is allowed to be in the cell it was given. A cell is almost never a whole
 * number of players wide, so either the art stops short of the edge or it leaves the whole-pixel
 * grid; the listener chooses.
 */
enum class WidgetSize(
    val label: String,
    val summary: String,
) {
    /**
     * The exact scale in place of the whole one below it, so the art reaches the edge of the cell.
     * Still uniform, but off the whole-pixel grid of ENGINEERING.md's Pixel rule 2.
     */
    FILL(
        label = "Fill",
        summary = "Grows to the edge of the space. The art is scaled to an in-between size, so it looks slightly softer.",
    ),

    /** The largest whole-number scale that fits. */
    LARGEST(
        label = "Largest",
        summary = "The largest size that keeps the art perfectly sharp. Leaves a little space around it.",
    ),
}

/**
 * How hard the home screen widget works. A widget is not told whether anyone is looking at it, so
 * the listener chooses the rate. Each summary says what the rate costs and what it shows, in terms
 * a listener can see.
 */
enum class WidgetRefresh(
    /** The step of the clock the widget shows. */
    val windowMs: Long,
    /** How long a loop of the analyzer plays before the audio is followed again. */
    val burstMs: Long,
    val label: String,
    val summary: String,
    /**
     * The summary shown when the visualizer is off and the clock is all that moves. Medium and High
     * step the clock the same, so their text is the same.
     */
    val timeOnly: String,
) {
    /** The clock steps every five seconds and the analyzer keeps one loop for half a minute. */
    LOW(
        windowMs = 5_000,
        burstMs = 30_000,
        label = "Low",
        summary = "Kindest to the battery. The time moves in jumps, and the visualizer catches up with the song now and then.",
        timeOnly = "Kindest to the battery. The time moves in jumps rather than ticking.",
    ),

    /** A ticking clock and a loop replaced every five seconds. */
    MEDIUM(
        windowMs = 1_000,
        burstMs = 5_000,
        label = "Medium",
        summary = "A fair balance. The time ticks along, and the visualizer catches up with the song every few seconds.",
        timeOnly = "The time ticks along, second by second.",
    ),

    /**
     * The clock cannot go faster than the second it displays, so this buys the analyzer alone: a
     * loop replaced every two seconds.
     */
    HIGH(
        windowMs = 1_000,
        burstMs = 2_000,
        label = "High",
        summary = "Uses the most battery. The time ticks, and the visualizer catches up with the song as often as a widget can.",
        timeOnly = "The time ticks along, second by second.",
    ),
}

/**
 * What the listener chose for the widget, and the defaults. The visualizer is one [VisMode], which
 * already carries Off alongside the two pictures.
 */
data class WidgetSettings(
    val mode: VisMode = VisMode.Analyzer,
    val refresh: WidgetRefresh = WidgetRefresh.MEDIUM,
    val size: WidgetSize = WidgetSize.LARGEST,
    /**
     * Whether only the logo opens the app, not the whole picture. On is for a listener who lands in
     * the app when a finger misses a button.
     */
    val openOnLogo: Boolean = false,
    /**
     * Whether the volume slider takes presses. Off by default: it is the smallest control on the
     * widget and sits beside the seek bar.
     */
    val volumeControl: Boolean = false,
) {
    companion object {
        private const val PREFS = "widget"
        private const val KEY_MODE = "settings.vismode"
        private const val KEY_SIZE = "settings.size"
        private const val KEY_LOGO = "settings.openonlogo"
        private const val KEY_VOLUME = "settings.volumecontrol"
        private const val KEY_REFRESH = "settings.refresh"

        /** The preferences file these live in, which [WidgetSignals.settings] listens to. */
        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun read(context: Context): WidgetSettings {
            val p = prefs(context)
            val stored = p.getString(KEY_REFRESH, null)
            val mode = p.getString(KEY_MODE, null)
            return WidgetSettings(
                mode = VisMode.entries.firstOrNull { it.name == mode } ?: VisMode.Analyzer,
                // an unreadable choice is the default: this is read on a broadcast, where throwing
                // would stop the widget drawing
                refresh = WidgetRefresh.entries.firstOrNull { it.name == stored } ?: WidgetRefresh.MEDIUM,
                size =
                    WidgetSize.entries.firstOrNull { it.name == p.getString(KEY_SIZE, null) }
                        ?: WidgetSize.LARGEST,
                openOnLogo = p.getBoolean(KEY_LOGO, false),
                volumeControl = p.getBoolean(KEY_VOLUME, false),
            )
        }

        /** What the preferences screen calls: saves the choice and redraws every placed widget. */
        fun update(
            context: Context,
            settings: WidgetSettings,
        ) {
            write(context, settings)
            MainWindowWidget.refresh(context)
        }

        fun write(
            context: Context,
            settings: WidgetSettings,
        ) {
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, settings.mode.name)
                .putString(KEY_REFRESH, settings.refresh.name)
                .putString(KEY_SIZE, settings.size.name)
                .putBoolean(KEY_LOGO, settings.openOnLogo)
                .putBoolean(KEY_VOLUME, settings.volumeControl)
                .apply()
        }
    }
}
