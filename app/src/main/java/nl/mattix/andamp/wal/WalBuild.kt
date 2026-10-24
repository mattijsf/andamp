// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.BuildConfig

/** How long a modern skin may take to load before it counts as one that does not open. */
internal object WalBuild {
    /** The time a release build gives a skin to load. */
    const val BUDGET_MS = 5_000L

    /** The time a debug build gives, which loads a large skin several times slower than a release build. */
    const val DEBUG_BUDGET_MS = 15_000L

    /** The time this build gives a skin to start, for an import and for the player. */
    val START_BUDGET_MS: Long = if (BuildConfig.DEBUG) DEBUG_BUDGET_MS else BUDGET_MS
}
