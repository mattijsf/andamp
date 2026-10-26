// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import androidx.compose.runtime.snapshotFlow
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The modern-skin switch: off with nothing stored, what it stores, changes stored in the order they
 * are made, a change racing the first read, and the state the player follows.
 *
 * A plain Application, because AndAmpApp reads the switch at startup on a thread that would race
 * the reads here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WalGateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val file get() = File(app.noBackupFilesDir, "wal_dev")

    @Before
    fun clear() = WalGate.clear(app)

    @After
    fun forget() = WalGate.clear(app)

    @Test
    fun `with nothing stored it reads off`() {
        assertFalse(WalGate.read(app))
        assertFalse(WalGate.on)
    }

    @Test
    fun `a stored value that says neither reads off`() {
        file.writeText("yes")
        assertFalse(WalGate.read(app))
    }

    @Test
    fun `on is written and read back`() {
        WalGate.write(app, true)
        WalGate.forget(app)
        assertTrue(WalGate.read(app))
    }

    @Test
    fun `off is written and read back`() {
        file.writeText("on")
        WalGate.write(app, false)
        WalGate.forget(app)
        assertFalse(WalGate.read(app))
    }

    @Test
    fun `the value lives in the no-backup folder and holds the word`() {
        WalGate.write(app, true)
        assertEquals(app.noBackupFilesDir, WalGate.file(app).parentFile)
        assertEquals("on", file.readText())
        WalGate.write(app, false)
        assertEquals("off", file.readText())
    }

    @Test
    fun `the stored value is read once`() {
        assertFalse(WalGate.read(app))
        file.writeText("on")
        assertFalse(WalGate.read(app))
    }

    @Test
    fun `a write sets the state at once`() {
        assertFalse(WalGate.read(app))
        WalGate.write(app, true)
        assertTrue(WalGate.on)
        assertTrue(WalGate.read(app))
        WalGate.write(app, false)
        assertFalse(WalGate.on)
    }

    @Test
    fun `a change sets the state at once and stores it after`() {
        WalGate.change(app, true)
        assertTrue(WalGate.on)
        assertTrue("read gives the change, not what is stored", WalGate.read(app))
        WalGate.awaitStored()
        assertEquals("on", file.readText())
    }

    @Test
    fun `changes are stored in the order they are made`() {
        WalGate.change(app, false)
        WalGate.change(app, true)
        WalGate.change(app, false)
        WalGate.change(app, true)
        assertTrue(WalGate.on)
        WalGate.awaitStored()
        assertEquals("on", file.readText())
        WalGate.forget(app)
        assertTrue(WalGate.read(app))
    }

    @Test
    fun `a write after changes is stored last`() {
        WalGate.change(app, true)
        WalGate.change(app, false)
        WalGate.write(app, true)
        assertEquals("on", file.readText())
    }

    @Test
    fun `a change made while the first read loads is not undone by it`() {
        // the hook first: whichever read loads the stored value makes the change while it loads
        WalGate.onLoaded = { WalGate.change(app, true) }
        file.writeText("off")
        assertTrue(WalGate.read(app))
        assertTrue(WalGate.on)
        WalGate.awaitStored()
        assertEquals("on", file.readText())
    }

    @Test
    fun `a change to off made while the first read loads is not undone by it`() {
        WalGate.onLoaded = { WalGate.change(app, false) }
        file.writeText("on")
        assertFalse(WalGate.read(app))
        assertFalse(WalGate.on)
        WalGate.awaitStored()
        assertEquals("off", file.readText())
    }

    @Test
    fun `clearing drops what is stored, forgetting keeps it`() {
        WalGate.write(app, true)
        WalGate.forget(app)
        assertTrue(file.exists())
        WalGate.clear(app)
        assertFalse(file.exists())
        assertFalse(WalGate.read(app))
    }

    @Test
    fun `code outside a composition follows a write and a change`() =
        runTest {
            WalGate.read(app)
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                snapshotFlow { WalGate.on }.collect { seen += it }
            }
            WalGate.write(app, true)
            WalGate.write(app, false)
            WalGate.change(app, true)
            assertEquals(listOf(false, true, false, true), seen)
        }
}
