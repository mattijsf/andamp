// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which links Andamp will fetch a plug-in from: its own site's plug-in folder,
 * over https, and nothing that only looks like it.
 */
class PluginLinksTest {
    @Test
    fun `a lua file in the site's plug-in folder is a plug-in link`() {
        assertTrue(PluginLinks.accepts("https://andamp.nl/extensions/plugins/warmth.lua"))
    }

    @Test
    fun `so is the same file in mattix_nl's plug-in folder`() {
        assertTrue(PluginLinks.accepts("https://mattix.nl/andamp/extensions/plugins/warmth.lua"))
    }

    @Test
    fun `anything else is not`() {
        listOf(
            "http://mattix.nl/andamp/extensions/plugins/warmth.lua",
            "https://mattix.nl/andamp/extensions/plugins/",
            "https://mattix.nl/andamp/extensions/plugins/index.html",
            "https://mattix.nl/andamp/warmth.lua",
            "https://evil.example/andamp/extensions/plugins/warmth.lua",
            "https://mattix.nl.evil.example/andamp/extensions/plugins/warmth.lua",
            "https://mattix.nl@evil.example/andamp/extensions/plugins/warmth.lua",
            "https://user@mattix.nl/andamp/extensions/plugins/warmth.lua",
            "https://mattix.nl:8443/andamp/extensions/plugins/warmth.lua",
            "https://mattix.nl/andamp/extensions/plugins/../../elsewhere/x.lua",
            "http://andamp.nl/extensions/plugins/warmth.lua",
            "https://andamp.nl/extensions/plugins/",
            "https://andamp.nl/extensions/warmth.lua",
            // each host has its own folder, and not the other's
            "https://andamp.nl/andamp/extensions/plugins/warmth.lua",
            "https://mattix.nl/extensions/plugins/warmth.lua",
            "https://www.andamp.nl/extensions/plugins/warmth.lua",
            "https://andamp.nl.evil.example/extensions/plugins/warmth.lua",
            "https://user@andamp.nl/extensions/plugins/warmth.lua",
            "https://andamp.nl:8443/extensions/plugins/warmth.lua",
            "https://andamp.nl/extensions/plugins/../../elsewhere/x.lua",
            "not a url at all",
        ).forEach { assertFalse(it, PluginLinks.accepts(it)) }
    }
}
