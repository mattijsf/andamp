// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.BuildConfig

/**
 * Whether this build offers modern skins to the listener: the switch in Preferences > Player, and
 * the offer to turn them on when a modern skin is picked while they are off. Debug builds only.
 */
internal object WalBeta {
    val offered: Boolean = BuildConfig.DEBUG
}
