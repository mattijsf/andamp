// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.xml.XmlParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** Skin XML files saved with a byte-order mark read the same as without one. */
class XmlParserTest {
    private fun shape(xml: String) = XmlParser.parse(xml).map { "${it.name} ${it.attrs} ${it.children.map { c -> c.name }}" }

    @Test
    fun `a byte-order mark before the first tag changes nothing`() {
        val xml = """<container id="main"><layout id="normal" w="200"/></container>"""
        assertEquals(shape(xml), shape("﻿" + xml))
    }

    @Test
    fun `a byte-order mark before the declaration changes nothing`() {
        val xml =
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<WinampAbstractionLayer version="0.8"><include file="a.xml"/></WinampAbstractionLayer>"""
        assertEquals(shape(xml), shape("﻿" + xml))
        assertEquals("winampabstractionlayer", XmlParser.parse("﻿" + xml).single().name)
    }
}
