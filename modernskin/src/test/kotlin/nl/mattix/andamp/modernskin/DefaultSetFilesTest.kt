// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.AndampDefaultSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/** Andamp's default set hands out every file it ships, the stand-in fonts for Tahoma and Arial included. */
class DefaultSetFilesTest {
    private val root = File("src/main/resources/nl/mattix/andamp/modernskin/defaultset")

    @Test
    fun `every stand-in font reads`() {
        for (name in listOf("DejaVuSansCondensed", "LiberationSans")) {
            for (cut in listOf("", "-Bold")) {
                val path = "fonts/$name$cut.ttf"
                assertNotNull(path, AndampDefaultSet.read(path))
            }
        }
    }

    @Test
    fun `it lists every file it ships except the license texts`() {
        val shipped =
            root
                .walkTopDown()
                .filter { it.isFile && !it.name.endsWith("LICENSE.txt") }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toSortedSet()
        assertEquals(shipped, AndampDefaultSet.names().toSortedSet())
    }
}
