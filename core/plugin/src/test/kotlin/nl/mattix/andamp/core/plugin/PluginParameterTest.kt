// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.plugin

import nl.mattix.andamp.core.dsp.GraphCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A parameter declared in Lua and set from Kotlin changes the audio. */
class PluginParameterTest {
    private val source =
        """
        plugin { id = "org.example.gain", name = "Gain", version = "1.0.0" }
        local amount = param.number { id = "amount", min = 0, max = 1, default = 1, smooth = 0 }
        function build(g, ctx)
          local out = {}
          for ch = 0, ctx.channels - 1 do out[ch] = g.mul(g.input(ch), amount) end
          return out
        end
        """.trimIndent()

    @Test
    fun `setting a declared parameter changes the output`() {
        val result = PluginLoader().load(source, sampleRate = 44_100, channels = 2)
        assertTrue("the plug-in loads: $result", result is PluginLoader.Result.Loaded)
        val plugin = (result as PluginLoader.Result.Loaded).plugin
        val engine = GraphCompiler.engine(plugin.graph)!!

        val atDefault = floatArrayOf(1f, 1f).also(engine::process)[0]
        engine.setParameter(0, 0.25f)
        // the declaration asks for no smoothing, so the value applies at once
        val moved = floatArrayOf(1f, 1f).also(engine::process)[0]

        assertEquals("amount", plugin.params[0].id)
        assertEquals(1f, atDefault, 0f)
        assertEquals(0.25f, moved, 0f)
    }
}
