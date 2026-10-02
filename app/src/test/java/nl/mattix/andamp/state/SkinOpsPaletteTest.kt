// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.skin.FakePalette
import nl.mattix.andamp.skin.LiveSkins
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinDist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What a palette change does to the skin on screen. The base is rebuilt, whatever is worn
 * is reloaded against it, and the chosen id stays. A configuration change that leaves the
 * palette as it was rebuilds nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SkinOpsPaletteTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val dark = SkinDist.baselineScheme("dark")
    private val other = dark.copy(primary = Color(0xFF00E250), surface = Color(0xFF101410))
    private val palette = FakePalette(dark)
    private lateinit var ops: SkinOps

    @Before
    fun setUp() {
        app
            .getSharedPreferences("skins", 0)
            .edit()
            .clear()
            .commit()
        java.io.File(app.filesDir, "skins").deleteRecursively()
        ops =
            SkinOps(
                app,
                WinampState(),
                CoroutineScope(SupervisorJob() + Dispatchers.Main),
                live = LiveSkins(palette),
            )
        ops.start()
        await { ops.skin != null }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("the condition holds within 10 s", condition())
    }

    @Test
    fun `a palette change swaps the worn live skin and keeps the id`() {
        val before: Skin = ops.skin!!
        assertSame(dark, before.liveScheme)

        palette.scheme = other
        ops.paletteChanged()
        await { ops.skin !== before }

        assertSame(other, ops.skin!!.liveScheme)
        assertEquals(SkinEntry.BASE_ID, ops.currentId)
        assertSame("the base skin is the new live skin", ops.skin, ops.baseSkin)
    }

    @Test
    fun `a configuration change that moved nothing rebuilds nothing`() {
        val before = ops.skin!!

        ops.paletteChanged()
        repeat(5) { shadowOf(Looper.getMainLooper()).idle() }

        assertSame(before, ops.skin)
        assertSame(before, ops.baseSkin)
    }

    @Test
    fun `a worn user skin is reloaded against the new base`() {
        val bytes = app.assets.open("skins/AndAmp Spot.wsz").use { it.readBytes() }
        ops.load(bytes.inputStream(), "Mine.wsz")
        await { ops.skin?.name == "Mine.wsz" }
        val before = ops.skin!!

        palette.scheme = other
        ops.paletteChanged()
        await { ops.skin !== before }

        assertEquals("Mine.wsz", ops.skin!!.name)
        assertSame(other, ops.baseSkin!!.liveScheme)
    }

    /**
     * A palette whose second read takes the current scheme and then blocks until [letGo],
     * so a palette change can arrive while the first build is under way.
     */
    private class SlowFirstBuild(
        @Volatile var scheme: ColorScheme,
    ) : (Context, Boolean) -> ColorScheme? {
        val building = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        private var asked = 0

        override fun invoke(
            context: Context,
            dark: Boolean,
        ): ColorScheme {
            val read = scheme
            if (synchronized(this) { ++asked } == 2) {
                building.countDown()
                letGo.await(10, TimeUnit.SECONDS)
            }
            return read
        }
    }

    @Test
    fun `a palette that arrives during the first build is the one left on screen`() {
        val slow = SlowFirstBuild(dark)
        val fresh = SkinOps(app, WinampState(), CoroutineScope(SupervisorJob() + Dispatchers.Main), live = LiveSkins(slow))
        fresh.start()
        await { slow.building.count == 0L }

        // the palette changes while the first build is blocked
        slow.scheme = other
        fresh.paletteChanged()
        repeat(50) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        slow.letGo.countDown()

        await { fresh.skin?.liveScheme === other }
        repeat(50) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertSame("the late first build leaves the new palette on screen", other, fresh.skin!!.liveScheme)
        assertSame(other, fresh.baseSkin!!.liveScheme)
    }
}
