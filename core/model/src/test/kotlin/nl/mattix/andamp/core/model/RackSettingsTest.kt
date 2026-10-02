// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The rack's list is the signal order. */
class RackSettingsTest {
    private val rack =
        RackSettings(
            listOf(RackSlot("a"), RackSlot("b"), RackSlot("c")),
        )

    private fun RackSettings.ids() = slots.map { it.pluginId }

    @Test
    fun `moving a plug-in earlier closes the gap behind it`() {
        assertEquals(listOf("c", "a", "b"), rack.move("c", 0).ids())
    }

    @Test
    fun `moving a plug-in later closes the gap in front of it`() {
        assertEquals(listOf("b", "c", "a"), rack.move("a", 2).ids())
    }

    @Test
    fun `a move off either end or of an unknown plug-in changes nothing`() {
        assertSame(rack, rack.move("a", -1))
        assertSame(rack, rack.move("a", 3))
        assertSame(rack, rack.move("nobody", 0))
    }

    @Test
    fun `enabling and setting a value touch only the slot named`() {
        val updated = rack.setEnabled("b", true).setValue("b", "depth", 0.7f)

        assertEquals(listOf(false, true, false), updated.slots.map { it.enabled })
        assertEquals(0.7f, updated["b"]!!.params["depth", 0f], 0f)
        assertEquals(0f, updated["a"]!!.params["depth", 0f], 0f)
    }

    @Test
    fun `removing a plug-in takes its settings with it`() {
        val updated = rack.setValue("b", "depth", 0.7f).remove("b")

        assertEquals(listOf("a", "c"), updated.ids())
        assertNull(updated["b"])
    }

    @Test
    fun `reconcile keeps stored order and settings for plug-ins still installed`() {
        val stored = rack.setEnabled("c", true).move("c", 0)

        val updated = stored.reconcile(listOf("a", "b", "c"))

        assertEquals(listOf("c", "a", "b"), updated.ids())
        assertEquals(true, updated["c"]!!.enabled)
    }

    @Test
    fun `reconcile drops what is gone and appends what is new`() {
        val updated = rack.reconcile(listOf("a", "c", "d"))

        // "b" was uninstalled, "d" was installed since; the rest keeps its place
        assertEquals(listOf("a", "c", "d"), updated.ids())
    }

    @Test
    fun `the effect kept first is put first, and the rest keep their order`() {
        val rack = RackSettings(listOf(RackSlot("a"), RackSlot("b"), RackSlot("pre"), RackSlot("c")))

        assertEquals(listOf("pre", "a", "b", "c"), rack.withFirst("pre").slots.map { it.pluginId })
        assertEquals("a missing id leaves the order unchanged", rack, rack.withFirst("missing"))
    }
}
