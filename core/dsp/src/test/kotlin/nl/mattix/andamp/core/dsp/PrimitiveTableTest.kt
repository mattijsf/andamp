// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The [Primitive] table is what the validator reads, so its rows must not contradict themselves. */
class PrimitiveTableTest {
    @Test
    fun `no key is both an edge and a constant`() {
        Primitive.entries.forEach {
            val both = it.edgeKeys intersect it.constKeys
            assertTrue("${it.name} declares no key as both edge and constant: $both", both.isEmpty())
        }
    }

    @Test
    fun `every enumerated key is a constant`() {
        Primitive.entries.forEach {
            it.enums.keys.forEach { key ->
                assertTrue("${it.name}'s enumerated '$key' is a const", key in it.constKeys)
            }
        }
    }

    @Test
    fun `an enumerated key has at least one value and all are lowercase`() {
        Primitive.entries.forEach {
            it.enums.forEach { (key, allowed) ->
                assertTrue("${it.name}'s '$key' enumerates at least one value", allowed.isNotEmpty())
                assertTrue("${it.name}'s '$key' values are lowercase", allowed.all { v -> v == v.lowercase() })
            }
        }
    }

    @Test
    fun `a textual key is a constant, and every enumerated key counts as textual`() {
        Primitive.entries.forEach {
            val stray = it.textKeys - it.constKeys
            assertTrue("${it.name}'s textual keys are consts: $stray", stray.isEmpty())
            it.enums.keys.forEach { key -> assertTrue("${it.name}'s enumerated '$key' is textual", it.isText(key)) }
        }
    }

    @Test
    fun `keys is the union of edge keys and const keys`() {
        Primitive.entries.forEach { assertEquals(it.edgeKeys + it.constKeys, it.keys) }
    }

    @Test
    fun `a stateless primitive has no state words`() {
        Primitive.entries.forEach {
            if (!it.stateful) assertEquals("stateless ${it.name} has no state words", 0, it.stateWords)
        }
    }

    @Test
    fun `input and param are the only fixed-rate primitives and take no edges`() {
        val sources = Primitive.entries.filter { it.fixedRate != null }

        assertEquals(setOf(Primitive.INPUT, Primitive.PARAM), sources.toSet())
        sources.forEach { assertTrue("${it.name} is a source and takes no edges", it.edgeKeys.isEmpty()) }
    }

    @Test
    fun `every primitive takes at least one argument`() {
        Primitive.entries.forEach { assertTrue("${it.name} takes at least one argument", it.keys.isNotEmpty()) }
    }

    @Test
    fun `both halves of a feedback pair take a name, and only the write half takes an edge`() {
        assertEquals(setOf("name"), Primitive.TAPIN.constKeys)
        assertEquals(setOf("name"), Primitive.TAPOUT.constKeys)
        assertTrue("the read half takes no edge", Primitive.TAPOUT.edgeKeys.isEmpty())
        assertEquals(setOf("source"), Primitive.TAPIN.edgeKeys)
    }

    @Test
    fun `every optional key is a key the primitive has`() {
        Primitive.entries.forEach {
            val stray = it.optional - it.keys
            assertTrue("${it.name}'s optional keys are keys it takes: $stray", stray.isEmpty())
        }
    }

    @Test
    fun `no signal input is optional`() {
        Primitive.entries.forEach {
            val signals = setOf("input", "a", "b", "source")
            val skippable = it.optional intersect signals
            assertTrue("${it.name} has no optional signal input: $skippable", skippable.isEmpty())
        }
    }

    @Test
    fun `maxTime and line are constants`() {
        assertTrue("a delay's maxTime is a constant", "maxTime" in Primitive.DELAY.constKeys)
        assertTrue("a tap's line is a constant", "line" in Primitive.TAP.constKeys)
    }
}
