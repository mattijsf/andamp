// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import nl.mattix.andamp.ui.prefs.Notice

/** The store this build is handed out by: none. It is the APK installed without Google Play. */
internal object Store {
    /** No store takes tips. */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant") // the same shape as the build a store hands out
    fun till(context: Context): Till? = null

    /** Where the support page sends the listener instead. */
    @Suppress("MayBeConst") // nullable, as in the build a store hands out
    val supportPage: String? = "https://andamp.nl/#donate"

    /** Nothing is carried for a store. */
    val notices = emptyList<Notice>()
}
