// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.overlay

import androidx.compose.ui.unit.IntRect

/**
 * When the floating player's invisible touch window may move, and where to.
 *
 * The overlay is two windows: a picture that never resizes, and a union-sized feeler that catches
 * gestures and forwards them. The feeler follows the union under these rules:
 *
 * - It never moves under a finger. A window that moves mid-gesture reports the same finger
 *   somewhere else, so a move asked for during a touch waits for the release.
 * - A modal swaps the roles. Menus are Compose popups inside the picture, which is untouchable, so
 *   while one is up the picture takes the touches and the feeler is parked, then put back where it
 *   was.
 *
 * Nothing here calls `WindowManager`; [PlayerOverlay] carries out the commands.
 */
class TouchWindowPlan {
    sealed interface Command {
        /** Put the feeler here. */
        data class Move(
            val to: IntRect,
        ) : Command

        /** Let the picture take touches itself, because a modal is up. */
        data class PictureTakesTouches(
            val on: Boolean,
        ) : Command
    }

    private var touching = false
    private var modal = false

    /** Where the feeler wants to be once it is allowed to move. */
    private var deferred: IntRect? = null

    /** The last geometry actually asked of the window manager. */
    var applied: IntRect? = null
        private set

    /** The union moved or resized: follow it, or remember to. */
    fun wants(to: IntRect): List<Command> = moveOrDefer(to)

    /** A finger arrived or left. */
    fun touching(down: Boolean): List<Command> {
        touching = down
        if (down) return emptyList()
        val target = deferred ?: return emptyList()
        deferred = null
        return moveOrDefer(target)
    }

    /**
     * A menu, a prompt or a sheet came up or went away. The feeler is parked at [PARKED] while it
     * is up, and returns to where it was.
     */
    fun modal(on: Boolean): List<Command> {
        if (on == modal) return emptyList()
        modal = on
        if (on) {
            // where to come back to, before parking overwrites it
            deferred = applied
            return listOf(Command.PictureTakesTouches(true)) + move(PARKED)
        }
        val back = deferred
        deferred = null
        return listOf(Command.PictureTakesTouches(false)) + (back?.let { move(it) } ?: emptyList())
    }

    private fun moveOrDefer(to: IntRect): List<Command> {
        if (touching || modal) {
            deferred = to
            return emptyList()
        }
        return move(to)
    }

    private fun move(to: IntRect): List<Command> {
        if (to == applied) return emptyList()
        applied = to
        return listOf(Command.Move(to))
    }

    companion object {
        /** Where the feeler waits while a modal is up: one pixel in the display's corner. */
        val PARKED = IntRect(0, 0, 1, 1)
    }
}
