// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import android.content.Context
import androidx.compose.material3.ColorScheme

/**
 * A palette that can be changed between asks, the way a wallpaper is, and can
 * be absent, as on a phone below Android 12.
 */
class FakePalette(
    var scheme: ColorScheme?,
) : (Context, Boolean) -> ColorScheme? {
    /** How often the palette was asked for. */
    var asked = 0
        private set

    override fun invoke(
        context: Context,
        dark: Boolean,
    ): ColorScheme? {
        asked++
        return scheme
    }
}
