// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.overlay

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.AmpPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayOpsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var granted = true

    private fun ops() = OverlayOps(OverlayStore(app), permitted = { granted })

    @Test
    fun `asking for it is remembered for the next session`() {
        ops().want(true)

        assertTrue(OverlayStore(app).wanted)
        assertTrue(ops().gate.wanted)
    }

    @Test
    fun `wanting it without permission does not show it`() {
        granted = false
        val ops = ops()

        ops.want(true)

        assertFalse(ops.gate.showing)
        assertEquals("Waiting for permission to draw over other apps", ops.summary())
    }

    @Test
    fun `granting permission afterwards is picked up on the next look`() {
        granted = false
        val ops = ops()
        ops.want(true)

        granted = true
        ops.recheck()
        ops.leftApp()

        assertTrue(ops.gate.showing)
    }

    @Test
    fun `permission withdrawn while running takes the overlay away`() {
        val ops = ops()
        ops.want(true)
        ops.leftApp()

        granted = false
        ops.recheck()

        assertFalse(ops.gate.showing)
    }

    @Test
    fun `closing the floating window leaves the preference on`() {
        val ops = ops()
        ops.want(true)
        ops.leftApp()

        ops.dismiss()

        assertFalse(ops.gate.showing)
        assertTrue(OverlayStore(app).wanted)
    }

    @Test
    fun `opening the app arms a closed overlay again`() {
        val ops = ops()
        ops.want(true)
        ops.leftApp()
        ops.dismiss()

        ops.enteredApp()
        ops.leftApp()

        assertTrue(ops.gate.showing)
    }

    @Test
    fun `asking for always on top without permission prompts and leaves it off`() {
        granted = false
        val ops = ops()
        var asked: AmpPrompt? = null
        var settingsOpened = 0

        ops.askFor(on = true, prompt = { asked = it }, openSettings = { settingsOpened++ })

        assertFalse("the overlay stays off", ops.gate.wanted)
        assertEquals("the settings screen stays closed until the prompt is confirmed", 0, settingsOpened)
        assertEquals("Always on top", asked?.title)
        asked?.onConfirm?.invoke()
        assertEquals("confirming opens the settings screen", 1, settingsOpened)
    }

    @Test
    fun `asking with permission in hand just turns it on`() {
        val ops = ops()
        var asked: AmpPrompt? = null

        ops.askFor(on = true, prompt = { asked = it }, openSettings = {})

        assertTrue(ops.gate.wanted)
        assertNull("no prompt is shown", asked)
    }

    @Test
    fun `turning it off never asks for anything`() {
        granted = false
        val ops = ops()
        var asked: AmpPrompt? = null

        ops.askFor(on = false, prompt = { asked = it }, openSettings = {})

        assertFalse(ops.gate.wanted)
        assertNull(asked)
    }

    @Test
    fun `the drawn player is told whether always on top is on`() {
        var lit: Boolean? = null
        val ops = OverlayOps(OverlayStore(app), permitted = { granted }, mirror = { lit = it })

        assertEquals("the player is told the state at once", false, lit)

        ops.want(true)
        assertEquals(true, lit)

        ops.want(false)
        assertEquals(false, lit)
    }

    /**
     * A toggle is three steps: ask for the permission if it is missing, flip the preference only
     * if it is granted, and report a change only if the preference moved.
     */
    @Test
    fun `toggling on with the permission granted flips it and hands over`() {
        granted = true
        val ops = ops()
        var handed: Boolean? = null

        ops.toggle(prompt = { asked ->
            assertNull(
                "a granted permission shows no prompt",
                asked,
            )
        }, openSettings = {}, onChanged = {
            handed =
                it
        })

        assertTrue(ops.gate.wanted)
        assertEquals(true, handed)
    }

    @Test
    fun `toggling off hands back`() {
        granted = true
        val ops = ops()
        ops.want(true)
        var handed: Boolean? = null

        ops.toggle(prompt = {}, openSettings = {}, onChanged = { handed = it })

        assertFalse(ops.gate.wanted)
        assertEquals(false, handed)
    }

    @Test
    fun `toggling on without the permission prompts and changes nothing`() {
        granted = false
        val ops = ops()
        var asked: AmpPrompt? = null
        var handed: Boolean? = null

        ops.toggle(prompt = { asked = it }, openSettings = {}, onChanged = { handed = it })

        assertTrue("the permission is asked for", asked != null)
        assertFalse("the preference stays off", ops.gate.wanted)
        assertEquals("the player is not handed over", null, handed)
    }

    /** A phone whose Android cannot show a floating player, with the permission granted. */
    private fun opsOnAnOlderPhone(
        mirror: (Boolean) -> Unit = {},
        onSwitchedOn: () -> Unit = {},
    ) = OverlayOps(OverlayStore(app), permitted = { true }, mirror, onSwitchedOn, supported = false)

    @Test
    fun `a phone that cannot float the player never shows it, whatever was stored`() {
        OverlayStore(app).wanted = true
        var lit: Boolean? = null

        val ops = opsOnAnOlderPhone(mirror = { lit = it })
        ops.leftApp()

        assertFalse(ops.gate.wanted)
        assertFalse(ops.gate.showing)
        assertEquals("the A stays dark", false, lit)
    }

    @Test
    fun `switching it on does nothing on a phone that cannot float the player`() {
        var switchedOn = 0
        val ops = opsOnAnOlderPhone(onSwitchedOn = { switchedOn++ })

        ops.want(true)
        ops.leftApp()

        assertFalse(ops.gate.showing)
        assertFalse("nothing is stored", OverlayStore(app).wanted)
        assertEquals("nothing else is switched off for it", 0, switchedOn)
    }

    @Test
    fun `asking for it on a phone that cannot float the player says why and opens no settings`() {
        val ops = opsOnAnOlderPhone()
        var asked: AmpPrompt? = null
        var settingsOpened = 0

        ops.askFor(on = true, prompt = { asked = it }, openSettings = { settingsOpened++ })

        assertFalse(ops.gate.wanted)
        assertEquals("Always on top", asked?.title)
        assertTrue("the notice names the Android it needs", asked?.body.orEmpty().contains("Android 11"))
        assertNull("a notice has only its OK", asked?.dismissLabel)
        asked?.onConfirm?.invoke()
        assertEquals(0, settingsOpened)
    }

    @Test
    fun `the A on a phone that cannot float the player says why and hands nothing over`() {
        val ops = opsOnAnOlderPhone()
        var asked: AmpPrompt? = null
        var handed: Boolean? = null

        ops.toggle(prompt = { asked = it }, openSettings = {}, onChanged = { handed = it })

        assertTrue("the notice is shown", asked != null)
        assertFalse(ops.gate.wanted)
        assertNull("the player is not handed over", handed)
    }
}
