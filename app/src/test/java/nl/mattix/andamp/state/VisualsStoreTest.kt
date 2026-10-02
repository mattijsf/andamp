// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which visual a listener was watching, and in which mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VisualsStoreTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app
            .getSharedPreferences("visuals", 0)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `nothing stored is the player's own defaults`() {
        val visuals = VisualsStore(app).load()

        assertEquals(VisMode.Analyzer, visuals.mode)
    }

    @Test
    fun `the mode comes back`() {
        VisualsStore(app).save(Visuals(mode = VisMode.Oscilloscope))

        assertEquals(VisMode.Oscilloscope, VisualsStore(app).load().mode)
    }

    @Test
    fun `a mode this build does not know falls back to the analyzer`() {
        app
            .getSharedPreferences("visuals", 0)
            .edit()
            .putString("mode", "Kaleidoscope")
            .commit()

        assertEquals(VisMode.Analyzer, VisualsStore(app).load().mode)
    }
}
