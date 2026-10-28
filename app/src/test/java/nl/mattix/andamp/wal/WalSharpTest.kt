// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sharp pixels: off unless switched on, what a change sets and stores, a stored value in
 * [WalRender.direct] once it is read, before a modern skin draws, and a change made before or
 * during the first read winning over the stored value.
 *
 * A plain Application, because AndAmpApp reads the setting at startup on a thread that would race
 * the reads here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WalSharpTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("wal_skins", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        WalSharp.forget(app)
        prefs.edit().clear().commit()
    }

    @After
    fun forget() = WalSharp.forget()

    @Test
    fun `off by default`() {
        WalSharp.read(app)
        assertFalse(WalSharp.on)
        assertFalse(WalRender.direct)
    }

    @Test
    fun `a change sets the drawing, draws again once and is stored`() {
        WalSharp.read(app)
        val redraws = WalRender.redraws.intValue
        WalSharp.change(app, true)
        assertTrue(WalSharp.on)
        assertTrue(WalRender.direct)
        assertEquals(redraws + 1, WalRender.redraws.intValue)
        assertTrue(prefs.getBoolean("sharp_pixels", false))

        WalSharp.change(app, false)
        assertFalse(WalSharp.on)
        assertFalse(WalRender.direct)
        assertEquals(redraws + 2, WalRender.redraws.intValue)
        assertFalse(prefs.getBoolean("sharp_pixels", true))
    }

    @Test
    fun `a stored value is in the drawing as soon as it is read`() {
        prefs.edit().putBoolean("sharp_pixels", true).commit()
        val redraws = WalRender.redraws.intValue
        WalSharp.read(app)
        assertTrue(WalSharp.on)
        assertTrue("a skin started now draws its first frame sharp", WalRender.direct)
        assertEquals("nothing on screen yet to draw again", redraws, WalRender.redraws.intValue)
    }

    @Test
    fun `a read by a player left from an earlier test leaves the setting unread`() {
        prefs.edit().putBoolean("sharp_pixels", true).commit()
        WalSharp.read(earlierApp())
        assertFalse(WalSharp.on)
        assertFalse(WalRender.direct)
        WalSharp.read(app)
        assertTrue("the test's own read still loads it", WalSharp.on)
    }

    @Test
    fun `the stored value is read once`() {
        WalSharp.read(app)
        prefs.edit().putBoolean("sharp_pixels", true).commit()
        WalSharp.read(app)
        assertFalse(WalSharp.on)
        assertFalse(WalRender.direct)
    }

    @Test
    fun `a change made before the first read wins over the stored value`() {
        WalSharp.change(app, true)
        prefs.edit().putBoolean("sharp_pixels", false).commit()
        WalSharp.read(app)
        assertTrue(WalSharp.on)
        assertTrue(WalRender.direct)
    }

    @Test
    fun `a change to on made while the first read loads is not undone by it`() {
        prefs.edit().putBoolean("sharp_pixels", false).commit()
        WalSharp.onLoaded = { WalSharp.change(app, true) }
        WalSharp.read(app)
        assertTrue(WalSharp.on)
        assertTrue(WalRender.direct)
        assertTrue(prefs.getBoolean("sharp_pixels", false))
    }

    @Test
    fun `a change to off made while the first read loads is not undone by it`() {
        prefs.edit().putBoolean("sharp_pixels", true).commit()
        WalSharp.onLoaded = { WalSharp.change(app, false) }
        WalSharp.read(app)
        assertFalse(WalSharp.on)
        assertFalse(WalRender.direct)
        assertFalse(prefs.getBoolean("sharp_pixels", true))
    }
}
