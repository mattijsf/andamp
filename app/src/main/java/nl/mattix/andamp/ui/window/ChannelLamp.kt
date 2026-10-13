// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

/**
 * Which of the main window's mono and stereo lamps is lit.
 *
 * One is lit unless playback is stopped: mono for one channel, stereo for two or more.
 * A player that does not know the count lights stereo.
 */
enum class ChannelLamp {
    NONE,
    MONO,
    STEREO,
    ;

    companion object {
        fun of(
            channels: Int?,
            stopped: Boolean,
        ): ChannelLamp =
            when {
                stopped -> NONE
                channels == 1 -> MONO
                else -> STEREO
            }
    }
}
