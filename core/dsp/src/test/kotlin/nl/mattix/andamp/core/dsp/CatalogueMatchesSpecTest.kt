// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The catalog in docs/dsp-plugin-spec.md is what a plug-in author writes against, and
 * [Primitive] is what the host accepts. This reads the table back out of the document and
 * checks that both name the same primitives. It does not compare their arguments.
 */
class CatalogueMatchesSpecTest {
    private val spec = File("../../docs/dsp-plugin-spec.md")

    /** Every `g.something` named in section 6. */
    private fun documented(): Set<String> {
        val section = spec.readText().substringAfter("## 6. The primitives").substringBefore("## 7.")
        return Regex("""`g\.([a-zA-Z0-9]+)""").findAll(section).map { it.groupValues[1].lowercase() }.toSet()
    }

    @Test
    fun `the spec file exists at the expected path`() {
        assertTrue("the spec exists at ${spec.absolutePath}", spec.isFile)
    }

    @Test
    fun `every primitive the host accepts is in the spec`() {
        val missing = authorable() - documented()

        assertEquals("the spec names every primitive the host accepts", emptySet<String>(), missing)
    }

    @Test
    fun `every primitive in the spec is one the host accepts`() {
        val promised = documented() - authorable()

        assertEquals("the host accepts every primitive the spec names", emptySet<String>(), promised)
    }

    /**
     * Everything an author writes as `g.something`. [Primitive.PARAM] is not among them: a
     * parameter is passed where an edge belongs, and there is no `g.param` (section 5).
     */
    private fun authorable() =
        Primitive.entries
            .filterNot { it == Primitive.PARAM }
            .map { it.name.lowercase() }
            .toSet()
}
