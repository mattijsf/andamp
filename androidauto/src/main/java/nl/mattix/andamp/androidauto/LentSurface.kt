// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import nl.mattix.andamp.core.remotewindow.IRemoteWindow

/**
 * The car's surface, lent to the player.
 *
 * When the car hands over a surface, this binds to the player ([Player]) and passes the surface
 * on; the player shows its main window there. A tap or a drag the car reports is passed on as well,
 * and so is the part of the surface the car leaves uncovered. When the car takes the surface
 * back, the player is told and let go.
 *
 * The player can come and go while the surface stays: each time it is reached, it is handed
 * the surface as it is then. A player that was replaced, by an update, is reached for again:
 * the binding to the one before it brings no player back.
 */
internal class LentSurface(
    private val context: Context,
) : SurfaceCallback {
    private var held: SurfaceContainer? = null
    private var uncovered: Rect? = null
    private var player: IRemoteWindow? = null
    private var bound = false
    private val main = Handler(Looper.getMainLooper())

    /** How many more times the player is reached for after a try that found none. */
    private var tries = 0
    private val again = Runnable { reach() }

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

            /** The player was replaced. This binding is over, and another is made. */
            override fun onBindingDied(name: ComponentName) {
                player = null
                letGo()
                tries = RETRIES
                reach()
            }
        }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        held = surfaceContainer
        reach()
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

    /** The list of songs comes up on the surface, or goes when it is up. */
    fun togglePlaylist() = tell { togglePlaylist() }

    /** Ends the loan: the player takes its window down, and is let go. */
    fun takeBack() {
        held = null
        main.removeCallbacks(again)
        tries = 0
        tell { hide() }
        letGo()
        player = null
    }

    /**
     * Binds to the player, while there is a surface to lend and no binding. A player that is
     * not there is tried for again as long as [tries] lasts: an update takes it away for a
     * moment.
     */
    private fun reach() {
        main.removeCallbacks(again)
        if (held == null || bound) return
        bound = Player.reach(context)?.let { context.bindService(it, connection, Context.BIND_AUTO_CREATE) } ?: false
        if (!bound && tries > 0) {
            tries--
            main.postDelayed(again, RETRY_MS)
        }
    }

    private fun letGo() {
        if (bound) context.unbindService(connection)
        bound = false
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

    private companion object {
        /** How often, and how long apart, a player that was replaced is reached for. */
        const val RETRIES = 10
        const val RETRY_MS = 1_000L
    }
}
