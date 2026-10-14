// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.remotewindow

import android.content.pm.ApplicationInfo

/** How the player and the app that lends it a surface find each other. */
object RemoteWindowLink {
    /** The action of the player's service, which hands out an [IRemoteWindow]. */
    const val ACTION = "nl.mattix.andamp.window.BIND"

    /**
     * The action of the player's activity that opens its Android Auto settings, where the
     * settings of the app that lends a surface live too. A player from before it has none.
     */
    const val SETTINGS = "nl.mattix.andamp.window.SETTINGS"

    /** What the id of the app that lends a surface has after the id of its player. */
    private const val LENDER = ".androidauto"

    /** The id of the app that lends a surface to the player installed as [player]. */
    fun lenderOf(player: String): String = player + LENDER

    /** The id of the player that the app installed as [lender] lends its surface to. */
    fun playerOf(lender: String): String = lender.removeSuffix(LENDER)

    /**
     * Which [IRemoteWindow] this build speaks. It goes up by one with every change to that
     * interface, which only ever adds calls, so a player and a lender of different releases
     * work together and can tell whether each does all the other asks:
     *
     * 1. `show`, `keepTo`, `tap`, `hide`, `drag` and `fling`.
     * 2. `listOrBack` and `watch`, for the lender's button and its icon.
     */
    const val PROTOCOL = 2

    /**
     * The name of the meta-data entry, on the application of the player and of the lender, that
     * says which [PROTOCOL] it speaks. An app without the entry speaks the first.
     */
    const val PROTOCOL_KEY = "nl.mattix.andamp.window.PROTOCOL"

    /** The protocol of an app that does not say. */
    const val FIRST_PROTOCOL = 1

    /** The protocol the app [app] describes speaks, read with its meta-data; the first when it does not say. */
    fun protocolOf(app: ApplicationInfo?): Int = app?.metaData?.getInt(PROTOCOL_KEY, FIRST_PROTOCOL) ?: FIRST_PROTOCOL
}
