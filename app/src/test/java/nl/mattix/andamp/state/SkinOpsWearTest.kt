// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.skin.BundledSkins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A music source's skin, worn over the listener's own. Wearing one leaves the listener's
 * choice as it was: a track from elsewhere, or a worn skin that is removed or will not
 * open, puts the listener's own back on. A skin the listener picks wins over one a source
 * asked for earlier.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SkinOpsWearTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val state = WinampState()
    private lateinit var library: SkinLibrary
    private lateinit var ops: SkinOps

    /** A skin of the listener's own, so a pick has a row to pick that is not a bundled one. */
    private lateinit var mine: String

    @Before
    fun setUp() {
        app
            .getSharedPreferences("skins", 0)
            .edit()
            .clear()
            .commit()
        java.io.File(app.filesDir, "skins").deleteRecursively()
        library = SkinLibrary(app)
        // the listener's own choice, made before this launch
        library.currentId = BundledSkins.LIGHT.id
        mine = library.save(app.assets.open(BundledSkins.SPOT.asset).use { it.readBytes() }, "Mine.wsz").id
        ops = SkinOps(app, state, CoroutineScope(SupervisorJob() + Dispatchers.Main), library)
        ops.start()
        // the rows too: a pick is made by row, and they are listed a hop after
        // the skin itself goes on
        await { ops.skin != null && ops.currentId == BundledSkins.LIGHT.id && state.skinEntries.isNotEmpty() }
    }

    /**
     * Idles the looper before each look at the condition, so a condition the setup already
     * satisfies is not accepted before the work under test has run.
     */
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        fail("the condition holds within 10 s")
    }

    /** Everything queued has run, for an assertion about what did not happen. */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun rowOf(id: String): Int =
        state.skinEntries.indexOfFirst { it.id == id }.also { assertTrue("$id has a row", it >= 0) }

    @Test
    fun `a source's skin goes on without changing what the listener chose`() {
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }

        assertEquals("the listener's choice is untouched", BundledSkins.LIGHT.id, library.currentId)
        assertEquals("the worn skin is recorded for the home screen", BundledSkins.SPOT.id, library.worn)
    }

    @Test
    fun `a track from elsewhere puts the listener's own back on`() {
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }

        ops.wear(null)
        await { ops.currentId == BundledSkins.LIGHT.id }

        assertNull(library.wearing)
    }

    @Test
    fun `a skin that is not installed is not worn - the listener's own goes back on`() {
        // worn first, so what is asserted afterwards is a move only the second
        // wear can have made rather than the state the setup left behind
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }

        ops.wear("no-such-skin")
        await { ops.currentId == BundledSkins.LIGHT.id }

        assertNull(library.wearing)
        assertEquals(BundledSkins.LIGHT.id, library.worn)
    }

    @Test
    fun `a source's skin that will not open takes the listener's own back, not the base`() {
        val rotten = library.save("this is not a zip".toByteArray(), "Rotten.wsz").id
        ops.wear(mine)
        await { ops.currentId == mine }

        ops.wear(rotten)
        await { ops.currentId == BundledSkins.LIGHT.id }

        assertNull("a skin that will not open is not recorded as worn", library.wearing)
        assertEquals(BundledSkins.LIGHT.id, library.worn)
    }

    @Test
    fun `removing the worn skin goes back to the listener's own, not to the base`() {
        ops.wear(mine)
        await { ops.currentId == mine }

        ops.remove(mine)
        await { ops.currentId == BundledSkins.LIGHT.id }

        assertNull(library.wearing)
        assertEquals(BundledSkins.LIGHT.id, library.currentId)
    }

    @Test
    fun `picking the skin a source is wearing makes it the listener's own`() {
        // the menu ticks what is on screen, so this is the listener tapping the
        // skin they can already see to keep it
        ops.wear(BundledSkins.SPOT.id)
        await { ops.currentId == BundledSkins.SPOT.id }

        ops.applyAt(rowOf(BundledSkins.SPOT.id))
        await { library.currentId == BundledSkins.SPOT.id }

        // and the next track from the phone's own files leaves the pick alone
        ops.wear(null)
        settle()
        assertEquals(BundledSkins.SPOT.id, ops.currentId)
        assertNull(library.wearing)
        assertEquals(BundledSkins.SPOT.id, library.worn)
    }

    @Test
    fun `a skin the listener picks beats a source's skin that was already on its way`() {
        ops.wear(BundledSkins.SPOT.id)
        ops.applyAt(rowOf(mine))

        await { ops.currentId == mine }
        settle()

        assertNull("the source's skin is not worn over the pick", library.wearing)
        assertEquals(mine, library.currentId)
        assertEquals("the home screen records the picked skin", mine, library.worn)
    }

    private companion object {
        /** Enough turns of the looper for a verb's own hop to IO and back. */
        const val SETTLE_ROUNDS = 20
    }
}
