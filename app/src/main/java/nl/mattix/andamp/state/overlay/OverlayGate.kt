// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.overlay

/**
 * Whether the floating player should be on screen. It shows when all four hold:
 *
 * - the listener asked for it,
 * - Android granted the overlay permission, which can be revoked in system settings while
 *   the app runs,
 * - Andamp itself is not in front,
 * - the overlay's close button has not been used since the listener last opened Andamp.
 *
 * The close button does not change the [wanted] preference; [returnedToApp] clears it.
 */
data class OverlayGate(
    val wanted: Boolean = false,
    val permitted: Boolean = false,
    val appInFront: Boolean = true,
    val dismissed: Boolean = false,
) {
    val showing: Boolean get() = wanted && permitted && !appInFront && !dismissed

    /** The X on the floating player: hidden until the listener comes back to the app. */
    fun dismiss() = copy(dismissed = true)

    /** Opening Andamp, also through the media notification, clears the dismissal. */
    fun returnedToApp() = copy(appInFront = true, dismissed = false)

    fun leftApp() = copy(appInFront = false)
}
