// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.ParamValues
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.RackSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What survives a relaunch: the order, the switches, the values, and nothing that was removed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RackPrefsCodecTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = app.getSharedPreferences("rack-test", Context.MODE_PRIVATE)

    private val available = listOf("a", "b", "c")

    @Before
    fun clear() =
        prefs
            .edit()
            .clear()
            .commit()
            .let { }

    @Test
    fun `a rack survives the round trip whole`() {
        val rack =
            RackSettings(listOf(RackSlot("a"), RackSlot("b"), RackSlot("c")))
                .setEnabled("b", true)
                .setValue("b", "depth", 0.7f)
                .move("c", 0)

        RackPrefsCodec.encode(prefs, rack)

        assertEquals(rack, RackPrefsCodec.decode(prefs, available))
    }

    @Test
    fun `the peak limiter starts off and survives the round trip`() {
        assertEquals(false, RackPrefsCodec.decode(prefs, available).limiter)

        RackPrefsCodec.encode(prefs, RackSettings(listOf(RackSlot("a"))).setLimiter(true))

        assertEquals(true, RackPrefsCodec.decode(prefs, available).limiter)
    }

    @Test
    fun `a plug-in installed since last launch joins the end, switched off`() {
        RackPrefsCodec.encode(prefs, RackSettings(listOf(RackSlot("c"), RackSlot("a"))))

        val decoded = RackPrefsCodec.decode(prefs, available)

        assertEquals(listOf("c", "a", "b"), decoded.slots.map { it.pluginId })
        assertFalse(decoded["b"]!!.enabled)
    }

    @Test
    fun `a plug-in that is not installed leaves the rack`() {
        RackPrefsCodec.encode(prefs, RackSettings(listOf(RackSlot("a"), RackSlot("gone"), RackSlot("b"))))

        assertNull(RackPrefsCodec.decode(prefs, available)["gone"])
    }

    @Test
    fun `forgetting a plug-in clears its keys and nothing else's`() {
        val rack =
            RackSettings(listOf(RackSlot("a"), RackSlot("b")))
                .setEnabled("a", true)
                .setValue("a", "depth", 0.7f)
                .setEnabled("b", true)
                .setValue("b", "depth", 0.3f)
        RackPrefsCodec.encode(prefs, rack)

        RackPrefsCodec.forget(prefs, "a")

        assertTrue("no value of the forgotten plug-in remains", prefs.all.keys.none { it.startsWith("a.") })
        assertNull("no switch of the forgotten plug-in remains", prefs.all["enabled.a"])
        assertEquals("b", prefs.getString("rack.order", null))
        val decoded = RackPrefsCodec.decode(prefs, listOf("b"))
        assertEquals(true, decoded["b"]!!.enabled)
        assertEquals(0.3f, decoded["b"]!!.params["depth", 0f], 0f)
    }

    @Test
    fun `forgetting a plug-in clears the settings saved for it too`() {
        RackPrefsCodec.remember(prefs, "a", ParamValues(mapOf("depth" to 0.7f)))
        RackPrefsCodec.remember(prefs, "b", ParamValues(mapOf("depth" to 0.3f)))

        RackPrefsCodec.forget(prefs, "a")

        assertNull(RackPrefsCodec.remembered(prefs, "a"))
        assertTrue("no saved value of the forgotten plug-in remains", prefs.all.keys.none { it.startsWith("mine.a") })
        assertEquals(0.3f, RackPrefsCodec.remembered(prefs, "b")!!["depth", 0f], 0f)
    }

    @Test
    fun `a plug-in whose id begins with another's keeps its own keys`() {
        // ids are reverse-DNS, so one can sit inside another: org.example.foo
        // and org.example.foo.pro are two plug-ins, and "a.b.c.depth" is the
        // second one's depth, not a parameter of the first called "c.depth"
        val rack =
            RackSettings(listOf(RackSlot("a.b"), RackSlot("a.b.c")))
                .setEnabled("a.b", true)
                .setValue("a.b", "depth", 0.2f)
                .setEnabled("a.b.c", true)
                .setValue("a.b.c", "depth", 0.9f)
        RackPrefsCodec.encode(prefs, rack)
        RackPrefsCodec.remember(prefs, "a.b.c", ParamValues(mapOf("depth" to 0.8f)))
        RackPrefsCodec.remember(prefs, "a.b", ParamValues(mapOf("depth" to 0.1f)))

        assertEquals(rack, RackPrefsCodec.decode(prefs, listOf("a.b", "a.b.c")))
        assertEquals(mapOf("depth" to 0.1f), RackPrefsCodec.remembered(prefs, "a.b")!!.values)

        RackPrefsCodec.forget(prefs, "a.b")

        val decoded = RackPrefsCodec.decode(prefs, listOf("a.b.c"))
        assertEquals(true, decoded["a.b.c"]!!.enabled)
        assertEquals(0.9f, decoded["a.b.c"]!!.params["depth", 0f], 0f)
        assertEquals(mapOf("depth" to 0.8f), RackPrefsCodec.remembered(prefs, "a.b.c")!!.values)
        assertNull(RackPrefsCodec.remembered(prefs, "a.b"))
        assertFalse("a.b's own value is removed", prefs.contains("a.b.depth"))
    }

    @Test
    fun `the fixed rack's old keys migrate to their plug-ins, in the order it ran`() {
        prefs
            .edit()
            .putBoolean("reverb.on", true)
            .putFloat("reverb.size", 0.9f)
            .putBoolean("mod.on", false)
            .putString("mod.mode", "PHASER")
            .putFloat("mod.depth", 0.6f)
            .putFloat("pan.pan", 0.25f)
            .commit()

        val decoded = RackPrefsCodec.decode(prefs, BuiltInEffects.ids)

        // the fixed rack's effects come first, in the order it ran them; every other
        // effect joins the end
        assertEquals(
            listOf(BuiltInEffects.KARAOKE, BuiltInEffects.MODULATION, BuiltInEffects.REVERB),
            decoded.slots.map { it.pluginId }.take(3),
        )
        assertEquals(BuiltInEffects.ids.toSet(), decoded.slots.map { it.pluginId }.toSet())
        assertTrue(decoded[BuiltInEffects.REVERB]!!.enabled)
        assertEquals(0.9f, decoded[BuiltInEffects.REVERB]!!.params["size", 0f], 0f)
        // the fixed rack stored the mode as an enum name; it decodes to the choice's value
        assertEquals(2f, decoded[BuiltInEffects.MODULATION]!!.params["mode", 0f], 0f)
        assertEquals(0.6f, decoded[BuiltInEffects.MODULATION]!!.params["depth", 0f], 0f)
        // the fixed rack's pan has no plug-in and gets no slot
        assertTrue(decoded.slots.none { it.pluginId.endsWith("pan") })
        assertTrue("the old keys are removed after the migration", prefs.all.keys.none { it.startsWith("reverb.") })
    }

    @Test
    fun `the legacy enabled-set key is swept on decode`() {
        prefs.edit().putStringSet("enabled", setOf("flanger")).commit()

        RackPrefsCodec.decode(prefs, available)

        assertNull(prefs.all["enabled"])
    }

    @Test
    fun `the legacy enabled-set key is swept on a rack that already has an order`() {
        // the migration is skipped once an order is stored; the sweep is not part of it
        RackPrefsCodec.encode(prefs, RackSettings(listOf(RackSlot("a"))))
        prefs.edit().putStringSet("enabled", setOf("flanger")).commit()

        RackPrefsCodec.decode(prefs, available)

        assertNull(prefs.all["enabled"])
    }

    @Test
    fun `a fresh install decodes to the available plug-ins, all switched off`() {
        val decoded = RackPrefsCodec.decode(prefs, available)

        assertEquals(available, decoded.slots.map { it.pluginId })
        assertTrue(decoded.slots.none { it.enabled })
    }
}
