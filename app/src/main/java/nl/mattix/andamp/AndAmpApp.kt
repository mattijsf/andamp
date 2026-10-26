// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.app.Application
import nl.mattix.andamp.backend.media3.Media3PlaybackHost
import nl.mattix.andamp.state.PackSources
import nl.mattix.andamp.state.PlaybackRoot
import nl.mattix.andamp.wal.WalGate
import nl.mattix.andamp.wal.WalSharp
import kotlin.concurrent.thread

/**
 * Runs before everything else, whatever started the process. Which music sources this phone has is
 * read by the window, the view model and the home screen widget, and the widget can run in a
 * process the launcher started with no activity. So [PackSources] is attached here.
 *
 * A car can start the process too, by connecting to the playback service, and then asks for a
 * session before any window has built a player. So the service is told here how to get one.
 *
 * Whether modern skins may be worn ([WalGate]) and whether they are drawn with sharp pixels
 * ([WalSharp]) are read on a background thread, so the first screen does not wait for them.
 */
class AndAmpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PackSources.attach(this)
        Media3PlaybackHost.wake = { PlaybackRoot.backend(it) }
        thread(name = "wal-gate") {
            WalGate.read(this)
            WalSharp.read(this)
        }
    }
}
