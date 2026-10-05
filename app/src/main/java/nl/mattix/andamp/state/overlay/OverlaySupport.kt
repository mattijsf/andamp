// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.overlay

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Whether the phone's Android can show the floating player.
 *
 * Its windows are sized from the display's own measurements and placed without fitting around
 * the system bars. Android offers both from version 11.
 */
object OverlaySupport {
    /** Whether a phone running [sdk] can show it. */
    fun on(sdk: Int): Boolean = sdk >= Build.VERSION_CODES.R

    /** Whether this phone can. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val here: Boolean get() = on(Build.VERSION.SDK_INT)
}
