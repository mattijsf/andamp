// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.ViewModelProvider
import nl.mattix.andamp.core.remotewindow.IRemoteWindow
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink
import nl.mattix.andamp.state.AppViewModels
import nl.mattix.andamp.state.WinampViewModel

/**
 * Lends the main window to a surface another app owns: a car's screen, through the app that
 * Android Auto talks to.
 *
 * The other app binds with [RemoteWindowLink.ACTION] and holds an [IRemoteWindow]. Its calls
 * arrive on a binder thread and are carried out on the main thread, in the order they were
 * made. A call from an app [RemoteWindowTrust] does not trust is dropped. When the other app
 * lets go, the window is taken off its surface.
 */
class RemoteWindowService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var trust: RemoteWindowTrust
    private lateinit var window: RemoteWindow

    private val binder =
        object : IRemoteWindow.Stub() {
            override fun show(
                surface: Surface,
                width: Int,
                height: Int,
                densityDpi: Int,
            ) = trusted { window.show(surface, width, height, densityDpi) }

            override fun keepTo(
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
            ) = trusted { window.keepTo(IntRect(left, top, right, bottom)) }

            override fun tap(
                x: Float,
                y: Float,
            ) = trusted { window.tap(x, y) }

            override fun hide() = trusted { window.hide() }

            // a list is all that scrolls, and it scrolls up and down
            override fun drag(
                dx: Float,
                dy: Float,
            ) = trusted { window.list.dragBy(dy) }

            override fun fling(
                vx: Float,
                vy: Float,
            ) = trusted { window.list.throwAt(vy) }
        }

    override fun onCreate() {
        super.onCreate()
        trust = RemoteWindowTrust(this)
        // the one view model of the process, which the activity and the floating player share
        val vm =
            ViewModelProvider(
                AppViewModels,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[WinampViewModel::class.java]
        window = RemoteWindow(this, vm)
    }

    /** Runs [call] on the main thread when the app that is calling may borrow the window. */
    private fun trusted(call: () -> Unit) {
        // asked on the binder thread, which is the only place the caller is known
        if (trust.trusts(Binder.getCallingUid())) main.post(call)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent): Boolean {
        window.hide()
        return false
    }

    override fun onDestroy() {
        window.hide()
        super.onDestroy()
    }
}
