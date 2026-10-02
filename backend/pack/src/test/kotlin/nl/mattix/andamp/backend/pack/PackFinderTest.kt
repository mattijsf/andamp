// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A phone with more than one pack on it.
 *
 * The finder returns every package that answers the bind action, sorted by package name. A
 * client given a package stays with that package.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackFinderTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun TestScope.own(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.clientFor(from: String?): PackClient =
        PackClient(
            app,
            PackApi.ACTION_BIND,
            from,
            own(),
            UnconfinedTestDispatcher(testScheduler),
            UnconfinedTestDispatcher(testScheduler),
        )

    @Test
    fun `a phone with no pack finds none`() {
        assertEquals(emptyList<String>(), PackFinder.found(app))
    }

    @Test
    fun `both packs are found`() {
        app.install(FakePack(), from = FORK)
        app.install(FakePack(), from = ORIGINAL)

        assertEquals(listOf(ORIGINAL, FORK), PackFinder.found(app))
    }

    @Test
    fun `the order does not depend on which was installed first`() {
        app.install(FakePack(), from = ORIGINAL)
        app.install(FakePack(), from = FORK)

        assertEquals("the packs are sorted by package name", listOf(ORIGINAL, FORK), PackFinder.found(app))
    }

    @Test
    fun `a client is bound to the pack it was given`() =
        runTest {
            app.install(FakePack(whose = PackAccount(signedIn = true, name = "theirs")), from = FORK)
            val client = clientFor(FORK)
            advanceUntilIdle()

            assertTrue(client.installed())
            assertEquals("the client opens the fork's settings", FORK, client.settings()?.component?.packageName)
            assertEquals("theirs", (client.reach.value as PackReach.Ready).account.name)
        }

    @Test
    fun `a client named a pack that is not installed reaches nothing`() =
        runTest {
            app.install(FakePack(whose = PackAccount(signedIn = true, name = "someone else's")), from = ORIGINAL)
            val client = clientFor(FORK)
            advanceUntilIdle()

            assertFalse("the named pack is not installed", client.installed())
            assertEquals(PackReach.Absent, client.reach.value)
        }

    private companion object {
        /** Sorts after [ORIGINAL], and is installed first in one test. */
        const val FORK = "nl.mattix.andamp.pack.zfork"
        const val ORIGINAL = "nl.mattix.andamp.pack.example"
    }
}
