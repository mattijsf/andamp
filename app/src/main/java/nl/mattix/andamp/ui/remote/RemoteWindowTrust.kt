// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink

/**
 * Who may borrow the main window.
 *
 * The window shows what is playing and takes taps, so it is lent to two kinds of caller only.
 * One is signed with the key this app is signed with, which includes this app itself. The
 * other is the app Android Auto talks to that belongs with this player, known by its id
 * ([RemoteWindowLink.lenderOf]) whatever key signed it: an Andamp from Google Play, from the
 * releases page or from another catalog each comes signed with a different key, and any of
 * them may be installed beside any of the others. One app at a time holds an id on a phone,
 * so another app can take that one's place only on a phone where it is not installed.
 */
internal class RemoteWindowTrust(
    private val context: Context,
) {
    /** Whether the app running as [uid] may borrow the window. */
    fun trusts(uid: Int): Boolean = ownSigner(uid) || companion(uid)

    /** Whether the app running as [uid] is signed with the key this app is signed with. */
    internal fun ownSigner(uid: Int): Boolean =
        context.packageManager.checkSignatures(Process.myUid(), uid) == PackageManager.SIGNATURE_MATCH

    /** Whether the app running as [uid] is the app Android Auto talks to that belongs with this player. */
    internal fun companion(uid: Int): Boolean =
        context.packageManager
            .getPackagesForUid(uid)
            .orEmpty()
            .contains(RemoteWindowLink.lenderOf(context.packageName))
}
