// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

/**
 * How the listener asked the lists to look ([nl.mattix.andamp.state.ListStyleStore]), as a classic
 * skin's playlist takes it: in the skin's TEXT.BMP letters or not, and how many pixels taller
 * than Winamp's each row is.
 */
internal data class WalListStyle(
    val skinFont: Boolean = false,
    val rowExtra: Int = 0,
)
