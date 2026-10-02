// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which skin each source asks for, and when it is worn.
 *
 * The sources are made up here, one that takes a skin and one that says the
 * setting is unsupported, because the app's code never names one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceSkinsTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun skins() = SourceSkins(app.getSharedPreferences("skins-${System.nanoTime()}", Context.MODE_PRIVATE))

    private val example = MusicSource("EXAMPLE", "Example")
    private val plain = MusicSource("PLAIN", "Plain")

    private val worn = mutableListOf<String?>()

    private fun ops(skins: SourceSkins = skins()) = SourceSkinOps(skins, { worn += it }, supports = { it == example })

    @Test
    fun `nothing chosen is Default, a choice is remembered, and Default can be chosen again`() {
        val prefs = app.getSharedPreferences("skins-kept", Context.MODE_PRIVATE)
        val skins = SourceSkins(prefs)
        assertNull(skins.of(example))

        skins.choose(example, "spot")
        assertEquals("spot", SourceSkins(prefs).of(example))

        skins.choose(example, null)
        assertNull(SourceSkins(prefs).of(example))
    }

    @Test
    fun `a skin that leaves the library leaves every source that asked for it on Default`() {
        val skins = skins()
        skins.choose(example, "gone")
        skins.choose(plain, "gone")

        skins.forgetSkin("gone")

        assertNull(skins.of(example))
        assertNull(skins.of(plain))
    }

    @Test
    fun `a track from a source wears its skin, and the phone wears the listener's own`() {
        val skins = skins().apply { choose(example, "spot") }
        val ops = ops(skins)

        ops.playingFrom(example)
        ops.playingFrom(MusicSource.LOCAL)

        assertEquals(listOf("spot", null), worn)
    }

    @Test
    fun `a source that says the setting is unsupported wears the listener's own whatever is stored`() {
        val skins = skins().apply { choose(plain, "spot") }

        ops(skins).playingFrom(plain)

        assertEquals(listOf<String?>(null), worn)
    }

    /** [SourceSkins.wornFor] asked directly: the player and the home screen widget both go through it. */
    @Test
    fun `a choice of a skin that is not installed wears the listener's own`() {
        val prefs = app.getSharedPreferences("skins-gone-${System.nanoTime()}", Context.MODE_PRIVATE)
        val skins = SourceSkins(prefs, installed = { false })
        skins.choose(example, "gone")

        assertNull(skins.wornFor(example, supports = { it == example }))
        assertEquals("the stored choice is kept", "gone", skins.of(example))
    }

    @Test
    fun `a new choice for the source that is playing shows at once, and for another one it waits`() {
        val ops = ops()
        ops.playingFrom(example)
        worn.clear()

        ops.choose(example, "spot")
        ops.choose(MusicSource("OTHER", "Other"), "light")

        assertEquals(listOf<String?>("spot"), worn)
    }
}
