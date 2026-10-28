// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider

/**
 * A context whose application is not the running test's: what a view model made by an earlier
 * test reads the modern-skin switches with.
 */
internal fun earlierApp(): Context =
    object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        override fun getApplicationContext(): Context = this
    }
