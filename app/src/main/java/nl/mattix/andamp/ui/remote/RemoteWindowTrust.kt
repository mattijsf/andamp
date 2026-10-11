// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Who may borrow the main window.
 *
 * The window shows what is playing and takes taps, so it is lent only to an app signed with
 * the key this app is signed with.
 */
internal class RemoteWindowTrust(
    private val context: Context,
) {
    /** Whether the app running as [uid] may borrow the window. */
    fun trusts(uid: Int): Boolean = context.packageManager.checkSignatures(Process.myUid(), uid) == PackageManager.SIGNATURE_MATCH
}
