// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.player

/**
 * A transport press arriving from outside the player's own windows, such as the home screen
 * widget.
 *
 * A media key event is not used for this: it reaches playback through the running
 * MediaSession, which belongs to one backend, and so bypasses [PlayerFacade] and the rules
 * applied there. These five verbs are carried instead and turned into calls by
 * [PlayerFacade.obey].
 */
enum class TransportCommand {
    PLAY,
    PAUSE,
    STOP,
    NEXT,
    PREVIOUS,
}
