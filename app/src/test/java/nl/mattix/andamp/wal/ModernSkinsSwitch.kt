// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context

/**
 * Switches modern skins on for a test, from nothing stored, and stores it as the listener's switch
 * does. Every build reads them off with nothing stored, so a test that wears a modern skin through
 * the player calls this first, and `WalGate.clear(context)` after.
 */
internal fun switchModernSkinsOn(context: Context) {
    WalGate.clear(context)
    WalGate.write(context, true)
}
