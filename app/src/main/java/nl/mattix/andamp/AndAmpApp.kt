// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.app.Application
import nl.mattix.andamp.state.PackSources

/**
 * Runs before everything else, whatever started the process. Which music sources this phone has is
 * read by the window, the view model and the home screen widget, and the widget can run in a
 * process the launcher started with no activity. So [PackSources] is attached here.
 */
class AndAmpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PackSources.attach(this)
    }
}
