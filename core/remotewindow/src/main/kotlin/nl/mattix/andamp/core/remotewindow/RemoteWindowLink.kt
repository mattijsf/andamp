// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.remotewindow

/** How the player and the app that lends it a surface find each other. */
object RemoteWindowLink {
    /** The action of the player's service, which hands out an [IRemoteWindow]. */
    const val ACTION = "nl.mattix.andamp.window.BIND"

    /** What the id of the app that lends a surface has after the id of its player. */
    private const val LENDER = ".androidauto"

    /** The id of the app that lends a surface to the player installed as [player]. */
    fun lenderOf(player: String): String = player + LENDER

    /** The id of the player that the app installed as [lender] lends its surface to. */
    fun playerOf(lender: String): String = lender.removeSuffix(LENDER)
}
