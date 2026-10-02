// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackApi
import nl.mattix.andamp.core.packapi.PackQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The four states of [PackReach] (absent, outdated, signed out and ready), and what the client
 * does when the pack's process ends, the pack is removed, or the pack throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackClientTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /**
     * The scope the client is given: unconfined on the test's scheduler, so that work the
     * constructor starts has run by the time a test looks. The test's background scope would
     * not run it while the scheduler is advanced by hand.
     */
    private fun TestScope.own(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    /** A client on [own], with both dispatchers unconfined on the test's scheduler. */
    private fun TestScope.clientFor(
        action: String = PackApi.ACTION_BIND,
        from: String? = null,
    ): PackClient =
        PackClient(app, action, from, own(), UnconfinedTestDispatcher(testScheduler), UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `a phone with no pack reaches nothing`() =
        runTest {
            val client = clientFor()
            advanceUntilIdle()

            assertFalse("no pack is installed", client.installed())
            assertNull("there is no settings screen", client.settings())
            assertNull("there is no account to read", client.account())
            assertEquals(PackReach.Absent, client.reach.value)
        }

    @Test
    fun `a pack somebody is signed into is ready and carries its descriptor`() =
        runTest {
            app.install(FakePack(whose = PackAccount(signedIn = true, name = "someone")))
            val client = clientFor()
            advanceUntilIdle()

            val reach = client.reach.value
            assertTrue("the pack is ready: $reach", reach is PackReach.Ready)
            assertEquals("Example", (reach as PackReach.Ready).descriptor.label)
            assertEquals("someone", reach.account.name)
            assertTrue(client.installed())
        }

    @Test
    fun `a pack nobody has signed into is signed out`() =
        runTest {
            app.install(FakePack(whose = PackAccount(signedIn = false)))
            val client = clientFor()
            advanceUntilIdle()

            // the signed-out state carries the descriptor too
            val reach = client.reach.value
            assertTrue("the pack is signed out: $reach", reach is PackReach.SignedOut)
            assertEquals("Example", (reach as PackReach.SignedOut).descriptor.label)
        }

    /**
     * A client with no registered listener is not told of a sign-in. Asking again has to set
     * [PackClient.reach] and what is remembered for the next launch.
     */
    @Test
    fun `asking again finds a sign-in and remembers it`() =
        runTest {
            val pack = FakePack(whose = PackAccount(signedIn = false))
            app.install(pack)
            val client = clientFor()
            advanceUntilIdle()
            assertTrue(client.reach.value is PackReach.SignedOut)

            pack.whose = PackAccount(signedIn = true, name = "someone")

            assertEquals(true, client.account()?.signedIn)
            assertTrue("the pack is ready: ${client.reach.value}", client.reach.value is PackReach.Ready)
            assertTrue("the sign-in is remembered", client.remembered().signedIn)
        }

    /** Only a pack that answered "nobody" is a sign-out; a pack that could not be asked returns null. */
    @Test
    fun `a pack that could not be asked returns null and one nobody is signed into returns an account`() =
        runTest {
            val nothingThere = clientFor()
            advanceUntilIdle()
            assertNull("an absent pack returns no account", nothingThere.account())

            app.install(FakePack(whose = PackAccount(signedIn = false)))
            val signedOut = clientFor()
            advanceUntilIdle()
            assertEquals("a signed-out pack returns a signed-out account", PackAccount(signedIn = false), signedOut.account())
        }

    /** Sign-in happens on the pack's own screen, so the player has to be able to name it. */
    @Test
    fun `the settings screen the player opens is the pack's own`() =
        runTest {
            app.install(FakePack())
            val client = clientFor()

            val screen = client.settings()

            assertNotNull(screen)
            assertEquals(PackApi.ACTION_SETTINGS, screen?.action)
            assertEquals(ComponentName(PACK_PACKAGE, PACK_SETTINGS), screen?.component)
        }

    @Test
    fun `a pack with no settings screen has no settings intent`() =
        runTest {
            app.install(FakePack(), withSettings = false)
            val client = clientFor()

            assertNull(client.settings())
        }

    /** A pack that answers with another contract number is not asked anything else. */
    @Test
    fun `a pack built against another contract is outdated and is asked nothing more`() =
        runTest {
            val pack = FakePack(api = PackApi.PACK_API + 1)
            app.install(pack)
            val client = clientFor()
            advanceUntilIdle()

            assertEquals(PackReach.Outdated, client.reach.value)
            assertEquals("an outdated pack is asked nothing", emptyList<String>(), pack.heard)
        }

    @Test
    fun `an outdated pack is bound once`() =
        runTest {
            val pack = FakePack(api = PackApi.PACK_API + 1)
            app.install(pack)
            val client = clientFor()
            advanceUntilIdle()

            client.account()
            client.account()

            assertEquals("an outdated pack is bound once", 1, pack.greetings)
            assertEquals(PackReach.Outdated, client.reach.value)
        }

    /** Uninstalled, updated and killed for memory all arrive as a disconnect. */
    @Test
    fun `a pack whose process goes away is absent and the next question binds again`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            advanceUntilIdle()
            assertTrue(client.reach.value is PackReach.Ready)
            val held = app.boundToPack()

            held.onServiceDisconnected(ComponentName(PACK_PACKAGE, PACK_SERVICE))

            assertEquals(PackReach.Absent, client.reach.value)
            assertEquals(1, pack.greetings)

            client.account()

            assertTrue(client.reach.value is PackReach.Ready)
            assertEquals("the pack is bound again", 2, pack.greetings)
        }

    /**
     * A process that dies reports it twice, as a disconnect and as a death notice, and a new
     * binding may exist by the time the second arrives. The late report must leave the new
     * binding alone.
     */
    @Test
    fun `a late disconnect from an old binding leaves the binding that replaced it alone`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            advanceUntilIdle()
            val first = app.boundToPack()
            first.onServiceDisconnected(ComponentName(PACK_PACKAGE, PACK_SERVICE))
            client.account()
            assertTrue(client.reach.value is PackReach.Ready)

            first.onServiceDisconnected(ComponentName(PACK_PACKAGE, PACK_SERVICE))

            assertTrue("a late disconnect leaves the new binding ready", client.reach.value is PackReach.Ready)
            client.account()
            assertEquals("the new binding stays held", 2, pack.greetings)
        }

    /** The client receives every package change on the phone; only the bound pack's removal drops the binding. */
    @Test
    fun `another app being removed leaves the pack bound`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            advanceUntilIdle()

            app.sendBroadcast(Intent(Intent.ACTION_PACKAGE_REMOVED, Uri.parse("package:com.example.game")))
            shadowOf(Looper.getMainLooper()).idle()
            advanceUntilIdle()

            assertTrue(client.reach.value is PackReach.Ready)
            assertEquals("the pack stays bound", 1, pack.greetings)
        }

    @Test
    fun `the pack itself being removed drops the binding`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            advanceUntilIdle()

            app.sendBroadcast(Intent(Intent.ACTION_PACKAGE_REMOVED, Uri.parse("package:$PACK_PACKAGE")))
            shadowOf(Looper.getMainLooper()).idle()
            advanceUntilIdle()

            assertEquals("the pack is bound again by the next look", 2, pack.greetings)
        }

    /**
     * A binder carries some of what the pack's code throws back into the caller. The question
     * goes unanswered and the binding stays.
     */
    @Test
    fun `a pack that throws leaves the question unanswered and stays bound`() =
        runTest {
            val pack = FakePack(answers = { error("the pack throws") })
            app.install(pack)
            val client = clientFor()
            advanceUntilIdle()

            assertNull(client.ask(PackQuestion(kind = "anything")))

            assertTrue(client.reach.value is PackReach.Ready)
            assertEquals("the pack stays bound", 1, pack.greetings)
        }

    @Test
    fun `release drops the binding and asking again binds again`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            advanceUntilIdle()

            client.release()

            assertEquals(PackReach.Absent, client.reach.value)

            client.account()

            assertTrue(client.reach.value is PackReach.Ready)
            assertEquals(2, pack.greetings)
        }

    /** The pack is found by its action, so a client asking for another action finds nothing. */
    @Test
    fun `a pack answering some other action is not this client's pack`() =
        runTest {
            app.install(FakePack())
            val client = clientFor(action = "nl.mattix.andamp.source.SOMETHING_ELSE")
            advanceUntilIdle()

            assertFalse(client.installed())
            assertEquals(PackReach.Absent, client.reach.value)
        }
}
