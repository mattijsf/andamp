// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.res.Resources
import nl.mattix.andamp.R
import nl.mattix.andamp.ui.prefs.Notice

/** The store this build is handed out by: Google Play, which takes the tips. */
internal object Store {
    /** The store's side of the tip jar. */
    fun till(context: Context): Till? = PlayTill(context)

    /** No page outside the app: the support page sells the store's tips. */
    val supportPage: String? = null

    /** What this build carries for the store, for the licenses page, worded from [res]. */
    fun notices(res: Resources): List<Notice> =
        listOf(
            Notice(
                name = "Google Play Billing Library",
                licence = "Android SDK License",
                what = res.getString(R.string.prefs_notice_play_billing_what),
                url = "https://developer.android.com/google/play/billing",
                groups = listOf("com.android.billingclient"),
                obligation = res.getString(R.string.prefs_notice_play_billing_obligation),
            ),
        )
}
