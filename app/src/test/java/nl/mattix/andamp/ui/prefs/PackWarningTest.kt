// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import nl.mattix.andamp.backend.pack.PackReach
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackDescriptor
import nl.mattix.andamp.state.EnglishWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the source's row says in each state a pack can be in while it waits for
 * the listener, and that a ready pack leaves the row to describe its page. A
 * pack that was never installed and one that has been uninstalled are the same
 * reach and read differently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackWarningTest {
    @Test
    fun `with no pack the row says where packs come from`() {
        assertEquals(
            "Not installed - see More sources below",
            packWarning(PackReach.Absent, signedIn = false, words = EnglishWords),
        )
    }

    /** The tracks are still in the playlist, so the row says why they are skipped. */
    @Test
    fun `a pack that has gone says what became of the tracks it played`() {
        assertEquals(
            "Uninstalled, and its tracks are skipped",
            packWarning(PackReach.Absent, signedIn = true, words = EnglishWords),
        )
    }

    @Test
    fun `a pack too old to talk to says so`() {
        assertEquals(
            "Installed, but too old for this version of Andamp",
            packWarning(PackReach.Outdated, signedIn = false, words = EnglishWords),
        )
    }

    @Test
    fun `a pack made for a newer player says so`() {
        assertEquals(
            "Installed, but made for a newer version of Andamp",
            packWarning(PackReach.Ahead, signedIn = false, words = EnglishWords),
        )
    }

    @Test
    fun `the page of a pack built for another contract names the app to update`() {
        assertEquals(
            "Moose Music is set up for an older Andamp and is not asked to play anything. " +
                "A newer one puts its tracks back.",
            packNotice("Moose Music", PackReach.Outdated, EnglishWords),
        )
        assertEquals(
            "Moose Music is set up for a newer Andamp and is not asked to play anything. " +
                "Updating Andamp puts its tracks back.",
            packNotice("Moose Music", PackReach.Ahead, EnglishWords),
        )
        assertEquals(null, packNotice("Moose Music", PackReach.Absent, EnglishWords))
    }

    @Test
    fun `a pack with no account says only that`() {
        assertEquals(
            "Not signed in",
            packWarning(
                PackReach.SignedOut(PackDescriptor(scheme = "moose", label = "Moose Music", version = "0.4.0")),
                signedIn = false,
                words = EnglishWords,
            ),
        )
    }

    @Test
    fun `a ready pack has no warning, whoever is signed in`() {
        assertNull(packWarning(ready("listener"), signedIn = true, words = EnglishWords))
        assertNull(packWarning(ready(""), signedIn = true, words = EnglishWords))
    }

    private fun ready(name: String) =
        PackReach.Ready(
            PackDescriptor(scheme = "moose", label = "Moose Music", version = "0.4.0"),
            PackAccount(signedIn = true, name = name),
        )
}
