// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import nl.mattix.andamp.ui.prefs.Notice

/** The store this build is handed out by: Google Play, which takes the tips. */
internal object Store {
    /** The store's side of the tip jar. */
    fun till(context: Context): Till? = PlayTill(context)

    /** No page outside the app: the support page sells the store's tips. */
    val supportPage: String? = null

    /** What this build carries for the store, for the licenses page. */
    val notices =
        listOf(
            Notice(
                name = "Google Play Billing Library",
                licence = "Android SDK License",
                what = "Takes the tips on the Support Andamp page, through Google Play.",
                url = "https://developer.android.com/google/play/billing",
                groups = listOf("com.android.billingclient"),
                obligation =
                    "This library is not open source. Andamp's license carries an additional permission " +
                        "to combine Andamp with it and to distribute the result.",
            ),
        )
}
