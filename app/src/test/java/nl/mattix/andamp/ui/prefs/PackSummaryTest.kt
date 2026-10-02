// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import nl.mattix.andamp.backend.pack.PackReach
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one line under the source's row, in each state a pack can be in. A pack
 * that was never installed and one that has been uninstalled are the same
 * reach and read differently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackSummaryTest {
    @Test
    fun `with no pack the row says where packs come from`() {
        assertEquals(
            "Not installed - see More sources below",
            packSummary(PackReach.Absent, signedIn = false),
        )
    }

    /** The tracks are still in the playlist, so the row says why they are skipped. */
    @Test
    fun `a pack that has gone says what became of the tracks it played`() {
        assertEquals(
            "Uninstalled, and its tracks are skipped",
            packSummary(PackReach.Absent, signedIn = true),
        )
    }

    @Test
    fun `a pack too old to talk to says so`() {
        assertEquals(
            "Installed, but too old for this version of Andamp",
            packSummary(PackReach.Outdated, signedIn = false),
        )
    }

    @Test
    fun `a pack with no account says only that`() {
        assertEquals(
            "Not signed in",
            packSummary(
                PackReach.SignedOut(PackDescriptor(scheme = "moose", label = "Moose Music", version = "0.4.0")),
                signedIn = false,
            ),
        )
    }

    @Test
    fun `a ready pack says who it is signed in as`() {
        assertEquals("Signed in as listener", packSummary(ready("listener"), signedIn = true))
    }

    /** A pack may leave the account name empty. */
    @Test
    fun `a ready pack with no account name says it is signed in on this phone`() {
        assertEquals("Signed in on this phone", packSummary(ready(""), signedIn = true))
    }

    private fun ready(name: String) =
        PackReach.Ready(
            PackDescriptor(scheme = "moose", label = "Moose Music", version = "0.4.0"),
            PackAccount(signedIn = true, name = name),
        )
}
