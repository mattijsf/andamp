// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.remotewindow

/** Where an app that lends a surface finds the player's [IRemoteWindow]. */
object RemoteWindowLink {
    /** The action of the player's service. */
    const val ACTION = "nl.mattix.andamp.window.BIND"

    /** The player's package. */
    const val PLAYER = "nl.mattix.andamp"
}
