// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink

/** The player this app lends the car's surface to: the Andamp its own id is named after. */
internal object Player {
    /**
     * The intent that binds to the player's window, or null when the player on this phone
     * lends none: it is not installed, or it is from before it could.
     */
    fun reach(context: Context): Intent? =
        Intent(RemoteWindowLink.ACTION)
            .setPackage(RemoteWindowLink.playerOf(context.packageName))
            .takeIf { context.packageManager.resolveService(it, 0) != null }

    /** The [RemoteWindowLink.PROTOCOL] the player on this phone speaks, by its manifest. */
    fun protocol(context: Context): Int {
        val player = RemoteWindowLink.playerOf(context.packageName)
        val info = runCatching { context.packageManager.getApplicationInfo(player, PackageManager.GET_META_DATA) }.getOrNull()
        return RemoteWindowLink.protocolOf(info)
    }
}
