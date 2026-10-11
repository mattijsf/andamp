// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

/**
 * The bitrate as the main window's kbps field shows it. The field holds three characters,
 * and the label follows it directly.
 *
 * Up to 999 the number is shown as it is. Above that Winamp counts in larger units:
 * hundreds with an `H` from 1000 (1411 is `14H`), and ten-thousands with a `C` from 10000.
 * The value is cut, not rounded. Transcribed from `draw_bitmixrate` in Winamp's
 * `draw_main.cpp`.
 */
object KbpsReadout {
    fun of(kbps: Int): String =
        when {
            kbps >= IN_TEN_THOUSANDS -> "${kbps / IN_TEN_THOUSANDS % HUNDRED}C"
            kbps >= IN_HUNDREDS -> "${kbps / HUNDRED}H"
            else -> kbps.toString()
        }

    private const val HUNDRED = 100
    private const val IN_HUNDREDS = 1_000
    private const val IN_TEN_THOUSANDS = 10_000
}
