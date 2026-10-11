// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.car

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import androidx.car.app.SurfaceContainer
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.remotewindow.IRemoteWindow
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the player is told as the car hands over a surface, reports what happens on it, and
 * takes it back. The player here is a recorder behind the action the real one answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LentSurfaceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val told = mutableListOf<String>()
    private val surface = Surface(SurfaceTexture(0))

    private val player =
        object : IRemoteWindow.Stub() {
            override fun show(
                surface: Surface,
                width: Int,
                height: Int,
                densityDpi: Int,
            ) {
                told += "show ${width}x$height@$densityDpi"
            }

            override fun keepTo(
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
            ) {
                told += "keepTo $left,$top,$right,$bottom"
            }

            override fun tap(
                x: Float,
                y: Float,
            ) {
                told += "tap $x,$y"
            }

            override fun hide() {
                told += "hide"
            }

            override fun drag(
                dx: Float,
                dy: Float,
            ) {
                told += "drag $dx,$dy"
            }

            override fun fling(
                vx: Float,
                vy: Float,
            ) {
                told += "fling $vx,$vy"
            }
        }

    @Before
    fun installThePlayer() {
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent(RemoteWindowLink.ACTION).setPackage(RemoteWindowLink.PLAYER),
            ComponentName(RemoteWindowLink.PLAYER, "RemoteWindowService"),
            player,
        )
    }

    private fun handedOver(): LentSurface =
        LentSurface(app).also {
            it.onSurfaceAvailable(container())
            shadowOf(Looper.getMainLooper()).idle()
        }

    private fun container() = SurfaceContainer(surface, 1280, 720, 160)

    @Test
    fun `a surface from the car is passed on to the player`() {
        handedOver()

        assertEquals(listOf("show 1280x720@160"), told)
    }

    @Test
    fun `the part the car leaves uncovered is passed on`() {
        val lent = handedOver()

        lent.onStableAreaChanged(Rect(0, 80, 1280, 720))

        assertEquals("keepTo 0,80,1280,720", told.last())
    }

    @Test
    fun `an uncovered part known before the player answers is passed on with the surface`() {
        val lent = LentSurface(app)
        lent.onStableAreaChanged(Rect(0, 80, 1280, 720))

        lent.onSurfaceAvailable(container())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("show 1280x720@160", "keepTo 0,80,1280,720"), told)
    }

    @Test
    fun `a tap is passed on with its place`() {
        val lent = handedOver()

        lent.onClick(292f, 516f)

        assertEquals("tap 292.0,516.0", told.last())
    }

    /** The car says how far the picture should scroll; the finger went the other way. */
    @Test
    fun `a drag is passed on as the way the finger went`() {
        val lent = handedOver()

        lent.onScroll(0f, 40f)

        assertEquals("drag -0.0,-40.0", told.last())
    }

    @Test
    fun `a fling is passed on with the finger's speed`() {
        val lent = handedOver()

        lent.onFling(0f, -1500f)

        assertEquals("fling 0.0,-1500.0", told.last())
    }

    @Test
    fun `when the car takes the surface back the player is told and let go`() {
        val lent = handedOver()

        lent.onSurfaceDestroyed(container())

        assertEquals("hide", told.last())
        assertEquals(1, shadowOf(app).unboundServiceConnections.size)
    }

    @Test
    fun `nothing is passed on after the surface has gone`() {
        val lent = handedOver()
        lent.onSurfaceDestroyed(container())
        told.clear()

        lent.onClick(10f, 10f)

        assertEquals(emptyList<String>(), told)
    }

    @Test
    fun `a new surface from the car replaces the old one`() {
        val lent = handedOver()

        lent.onSurfaceAvailable(SurfaceContainer(surface, 800, 480, 120))

        assertEquals("show 800x480@120", told.last())
        assertEquals("the player is bound once", 1, shadowOf(app).boundServiceConnections.size)
    }
}
