// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.VisMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the listener chose for the widget, and what each refresh rate costs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSettingsTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an untouched install shows the bars at the middle rate`() {
        assertEquals(WidgetRefresh.MEDIUM, WidgetSettings().refresh)
        assertEquals(VisMode.Analyzer, WidgetSettings().mode)
    }

    @Test
    fun `a written choice is read back`() {
        // the widget renders in a broadcast, after the screen that set this is
        // gone, so the choice is read from disk
        WidgetSettings.write(app, WidgetSettings(mode = VisMode.Oscilloscope, refresh = WidgetRefresh.LOW))

        assertEquals(WidgetSettings(mode = VisMode.Oscilloscope, refresh = WidgetRefresh.LOW), WidgetSettings.read(app))
    }

    @Test
    fun `an unknown rate reads as the default`() {
        // read on a broadcast, where an exception would stop the widget drawing
        app
            .getSharedPreferences("widget", Context.MODE_PRIVATE)
            .edit()
            .putString("settings.refresh", "TURBO")
            .apply()

        assertEquals(WidgetRefresh.MEDIUM, WidgetSettings.read(app).refresh)
    }

    @Test
    fun `an unknown picture reads as the bars`() {
        app
            .getSharedPreferences("widget", Context.MODE_PRIVATE)
            .edit()
            .putString("settings.vismode", "Fire")
            .apply()

        assertEquals(VisMode.Analyzer, WidgetSettings.read(app).mode)
    }

    @Test
    fun `each step down does no more work`() {
        val rates = listOf(WidgetRefresh.HIGH, WidgetRefresh.MEDIUM, WidgetRefresh.LOW)

        rates.zipWithNext { faster, slower ->
            assertTrue("$slower redraws no more often than $faster", slower.windowMs >= faster.windowMs)
            assertTrue("$slower follows the audio no more often than $faster", slower.burstMs >= faster.burstMs)
        }
        assertTrue("Low follows the audio less often than High", WidgetRefresh.LOW.burstMs > WidgetRefresh.HIGH.burstMs)
    }

    @Test
    fun `the clock steps at the rate that was chosen`() {
        assertEquals(7, WidgetOps.stepped(7_400, WidgetRefresh.MEDIUM))
        assertEquals(7, WidgetOps.stepped(7_400, WidgetRefresh.HIGH))
        assertEquals(5, WidgetOps.stepped(7_400, WidgetRefresh.LOW))
        assertEquals(10, WidgetOps.stepped(14_900, WidgetRefresh.LOW))
        assertEquals(0, WidgetOps.stepped(0, WidgetRefresh.LOW))
    }

    @Test
    fun `an untouched install keeps the widget as large as the cell allows`() {
        assertEquals(WidgetSize.LARGEST, WidgetSettings().size)
    }

    @Test
    fun `the size choice survives too`() {
        WidgetSettings.write(app, WidgetSettings(size = WidgetSize.FILL))

        assertEquals(WidgetSize.FILL, WidgetSettings.read(app).size)
    }

    @Test
    fun `an unknown size reads as the largest`() {
        app
            .getSharedPreferences("widget", Context.MODE_PRIVATE)
            .edit()
            .putString("settings.size", "HUGE")
            .apply()

        assertEquals(WidgetSize.LARGEST, WidgetSettings.read(app).size)
    }

    /** A launcher cell: 388 by 208 dp in portrait, 819 by 113 in landscape. */
    private fun pixelCell() =
        android.os.Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 388)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 819)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 113)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 208)
        }

    @Test
    fun `in portrait the box is the portrait cell`() {
        // the portrait pair is the minimum width with the maximum height
        val (width, height) = WidgetBox.of(app, pixelCell())

        assertEquals("the box takes the portrait width", WidgetBox.px(app, 388), width)
        assertEquals("the box takes the portrait height", WidgetBox.px(app, 208), height)
    }

    @Test
    @Config(qualifiers = "land")
    fun `in landscape the box is the landscape cell`() {
        // the landscape pair is the maximum width with the minimum height
        val (width, height) = WidgetBox.of(app, pixelCell())

        assertEquals(WidgetBox.px(app, 819), width)
        assertEquals(WidgetBox.px(app, 113), height)
    }

    @Test
    fun `a half the host left empty falls back to the other`() {
        val onlyPortrait =
            android.os.Bundle().apply {
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 388)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 208)
            }

        assertEquals(WidgetBox.px(app, 388) to WidgetBox.px(app, 208), WidgetBox.of(app, onlyPortrait))
    }

    @Test
    fun `the two switches survive a restart, and start off`() {
        assertEquals(false, WidgetSettings().openOnLogo)
        assertEquals(false, WidgetSettings().volumeControl)

        WidgetSettings.write(app, WidgetSettings(openOnLogo = true, volumeControl = true))

        assertEquals(true, WidgetSettings.read(app).openOnLogo)
        assertEquals(true, WidgetSettings.read(app).volumeControl)
    }

    @Test
    fun `the clock's direction is written with the snapshot`() {
        // the widget renders in a broadcast, after the window that set this is
        // gone, so it is on disk with the rest of the picture
        WidgetSnapshot.write(app, WidgetSnapshot(title = "Hush", timeRemaining = true))

        assertEquals(true, WidgetSnapshot.read(app).timeRemaining)
    }
}
