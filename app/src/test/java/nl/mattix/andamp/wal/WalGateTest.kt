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
 * The modern-skin gate: its value with no file per build type, the file the adb switch writes, and
 * the state the player follows.
 *
 * A plain Application, because AndAmpApp reads the gate at startup on a thread that would race the
 * reads here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WalGateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val file get() = File(app.noBackupFilesDir, "wal_dev")

    @Before
    fun clear() {
        WalGate.forget()
        file.delete()
    }

    @After
    fun forget() = WalGate.forget()

    @Test
    fun `with no file a debug build reads on`() {
        assertTrue(WalGate.read(app, debug = true))
        assertTrue(WalGate.on)
    }

    @Test
    fun `with no file a release build reads off`() {
        assertFalse(WalGate.read(app, debug = false))
        assertFalse(WalGate.on)
    }

    @Test
    fun `a file that says neither reads as no file`() {
        file.writeText("yes")
        assertFalse(WalGate.read(app, debug = false))
    }

    @Test
    fun `on is written and read back over the build default`() {
        WalGate.write(app, true)
        WalGate.forget()
        assertTrue(WalGate.read(app, debug = false))
    }

    @Test
    fun `off is written and read back over the build default`() {
        WalGate.write(app, false)
        WalGate.forget()
        assertFalse(WalGate.read(app, debug = true))
    }

    @Test
    fun `the file lives in the no-backup folder and holds the word`() {
        WalGate.write(app, true)
        assertEquals(app.noBackupFilesDir, WalGate.file(app).parentFile)
        assertEquals("on", file.readText())
        WalGate.write(app, false)
        assertEquals("off", file.readText())
    }

    @Test
    fun `the file is read once`() {
        assertFalse(WalGate.read(app, debug = false))
        file.writeText("on")
        assertFalse(WalGate.read(app, debug = false))
    }

    @Test
    fun `a write sets the state at once`() {
        assertFalse(WalGate.read(app, debug = false))
        WalGate.write(app, true)
        assertTrue(WalGate.on)
        assertTrue(WalGate.read(app, debug = false))
        WalGate.write(app, false)
        assertFalse(WalGate.on)
    }

    @Test
    fun `code outside a composition follows a write`() =
        runTest {
            WalGate.read(app, debug = false)
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                snapshotFlow { WalGate.on }.collect { seen += it }
            }
            WalGate.write(app, true)
            WalGate.write(app, false)
            assertEquals(listOf(false, true, false), seen)
        }
}
