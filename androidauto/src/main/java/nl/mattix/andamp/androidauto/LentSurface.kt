// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.IBinder
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import nl.mattix.andamp.core.remotewindow.IRemoteWindow
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink

/**
 * The car's surface, lent to the player.
 *
 * When the car hands over a surface, this binds to the player and passes the surface on; the
 * player shows its main window there. A tap or a drag the car reports is passed on as well,
 * and so is the part of the surface the car leaves uncovered. When the car takes the surface
 * back, the player is told and let go.
 *
 * The player can come and go while the surface stays: each time it is reached, it is handed
 * the surface as it is then.
 */
internal class LentSurface(
    private val context: Context,
) : SurfaceCallback {
    private var held: SurfaceContainer? = null
    private var uncovered: Rect? = null
    private var player: IRemoteWindow? = null
    private var bound = false

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                player = IRemoteWindow.Stub.asInterface(binder)
                lend()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                player = null
            }
        }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        held = surfaceContainer
        if (!bound) {
            val toPlayer = Intent(RemoteWindowLink.ACTION).setPackage(RemoteWindowLink.PLAYER)
            bound = context.bindService(toPlayer, connection, Context.BIND_AUTO_CREATE)
        }
        lend()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        uncovered = Rect(stableArea)
        tell { keepTo(stableArea.left, stableArea.top, stableArea.right, stableArea.bottom) }
    }

    override fun onClick(
        x: Float,
        y: Float,
    ) = tell { tap(x, y) }

    /**
     * The car reports a drag as the distance the picture should scroll, which is the
     * opposite of the way the finger went; the player is told the finger's way.
     */
    override fun onScroll(
        distanceX: Float,
        distanceY: Float,
    ) = tell { drag(-distanceX, -distanceY) }

    override fun onFling(
        velocityX: Float,
        velocityY: Float,
    ) = tell { fling(velocityX, velocityY) }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = takeBack()

    /** Ends the loan: the player takes its window down, and is let go. */
    fun takeBack() {
        held = null
        tell { hide() }
        if (bound) context.unbindService(connection)
        bound = false
        player = null
    }

    /** Hands the player the surface, once there is both a surface and a player. */
    private fun lend() {
        val container = held ?: return
        val surface = container.surface ?: return
        tell { show(surface, container.width, container.height, container.dpi) }
        uncovered?.let { area -> tell { keepTo(area.left, area.top, area.right, area.bottom) } }
    }

    /** A call to the player, dropped when the player is not there or has just gone. */
    private fun tell(call: IRemoteWindow.() -> Unit) {
        player?.let { runCatching { it.call() } }
    }
}
