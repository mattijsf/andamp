// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.content.Context
import androidx.compose.runtime.snapshotFlow
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The modern-skin switch: off with nothing stored, kept in the backed-up modern-skin preferences,
 * a value in the no-backup file moved there once, changes stored in the order they are made, a
 * change racing the first read, and the state the player follows.
 *
 * A plain Application, because AndAmpApp reads the switch at startup on a thread that would race
 * the reads here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WalGateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences(WalSettings.PREFS, Context.MODE_PRIVATE)
    private val oldFile get() = File(app.noBackupFilesDir, "wal_dev")

    /** What the preferences hold under the switch's key, or null when nothing is stored. */
    private fun stored(): Boolean? = if (prefs.contains(WalGate.KEY)) prefs.getBoolean(WalGate.KEY, false) else null

    private fun store(value: Boolean) = prefs.edit().putBoolean(WalGate.KEY, value).commit()

    @Before
    fun clear() = WalGate.clear(app)

    @After
    fun forget() {
        WalGate.clear(app)
        WalGate.forget()
    }

    @Test
    fun `with nothing stored it reads off, in every build`() {
        assertFalse(WalGate.read(app))
        assertFalse(WalGate.on)
        assertNull("reading stores nothing", stored())
    }

    @Test
    fun `on is written and read back`() {
        WalGate.write(app, true)
        WalGate.forget(app)
        assertTrue(WalGate.read(app))
    }

    @Test
    fun `a read by a player left from an earlier test gives the stored value and leaves the switch unread`() {
        WalGate.write(app, true)
        WalGate.forget(app)
        val earlier = earlierApp()

        assertTrue(WalGate.read(earlier))
        assertFalse(WalGate.on)
        assertTrue("a read with the test's application still loads it", WalGate.read(app))
        assertTrue(WalGate.on)
    }

    @Test
    fun `off is written and read back`() {
        store(true)
        WalGate.write(app, false)
        WalGate.forget(app)
        assertFalse(WalGate.read(app))
    }

    @Test
    fun `the value is kept in the modern-skin preferences, not in the no-backup folder`() {
        WalGate.write(app, true)
        assertEquals(true, stored())
        WalGate.write(app, false)
        assertEquals(false, stored())
        assertEquals(
            "nothing goes to the no-backup folder",
            emptyList<String>(),
            app.noBackupFilesDir
                .list()
                .orEmpty()
                .toList(),
        )
    }

    @Test
    fun `a restored value is read at the first start`() {
        store(true)
        assertTrue(WalGate.read(app))
    }

    @Test
    fun `the stored value is read once`() {
        assertFalse(WalGate.read(app))
        store(true)
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
        assertEquals(true, stored())
    }

    @Test
    fun `changes are stored in the order they are made`() {
        WalGate.change(app, false)
        WalGate.change(app, true)
        WalGate.change(app, false)
        WalGate.change(app, true)
        assertTrue(WalGate.on)
        WalGate.awaitStored()
        assertEquals(true, stored())
        WalGate.forget(app)
        assertTrue(WalGate.read(app))
    }

    @Test
    fun `a write after changes is stored last`() {
        WalGate.change(app, true)
        WalGate.change(app, false)
        WalGate.write(app, true)
        assertEquals(true, stored())
    }

    @Test
    fun `a change made while the first read loads is not undone by it`() {
        // the hook first: whichever read loads the stored value makes the change while it loads
        WalGate.onLoaded = { WalGate.change(app, true) }
        store(false)
        assertTrue(WalGate.read(app))
        assertTrue(WalGate.on)
        WalGate.awaitStored()
        assertEquals(true, stored())
    }

    @Test
    fun `a change to off made while the first read loads is not undone by it`() {
        WalGate.onLoaded = { WalGate.change(app, false) }
        store(true)
        assertFalse(WalGate.read(app))
        assertFalse(WalGate.on)
        WalGate.awaitStored()
        assertEquals(false, stored())
    }

    @Test
    fun `clearing drops what is stored, forgetting keeps it`() {
        WalGate.write(app, true)
        WalGate.forget(app)
        assertEquals(true, stored())
        WalGate.clear(app)
        assertNull(stored())
        assertFalse(WalGate.read(app))
    }

    @Test
    fun `on in the no-backup file is moved to the preferences once, and the file goes`() {
        oldFile.writeText("on")
        assertTrue(WalGate.read(app))
        assertFalse(oldFile.exists())
        assertEquals(true, stored())
        WalGate.forget(app)
        assertTrue("the next start reads the preferences", WalGate.read(app))
    }

    @Test
    fun `off in the no-backup file is moved the same way`() {
        oldFile.writeText("off")
        assertFalse(WalGate.read(app))
        assertFalse(oldFile.exists())
        assertEquals(false, stored())
    }

    @Test
    fun `a file that says neither is moved as off`() {
        oldFile.writeText("yes")
        assertFalse(WalGate.read(app))
        assertFalse(oldFile.exists())
        assertEquals(false, stored())
    }

    @Test
    fun `the stored value wins over the file, which goes`() {
        store(true)
        oldFile.writeText("off")
        assertTrue(WalGate.read(app))
        assertFalse(oldFile.exists())
        assertEquals(true, stored())
    }

    @Test
    fun `a change stored before the file is read wins over it`() {
        oldFile.writeText("on")
        WalGate.write(app, false)
        WalGate.forget(app)
        assertFalse(WalGate.read(app))
        assertFalse(oldFile.exists())
        assertEquals(false, stored())
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
