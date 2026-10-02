// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A parameter's name and help text are kept by the loader. */
class PluginHelpTest {
    private val source =
        """
        plugin { id = "x", name = "X", version = "1" }
        local width = param.number {
          id = "width", name = "Width", min = 0, max = 1, default = 0,
          help = "Mixes the untouched stereo back over the result.",
        }
        function build(g, ctx)
          local o = {}
          for c = 0, ctx.channels - 1 do o[c] = g.mul(g.input(c), width) end
          return o
        end
        """.trimIndent()

    @Test
    fun `a parameter's name and help are loaded`() {
        val result = PluginLoader().load(source, sampleRate = 44_100, channels = 2)
        assertTrue("the plug-in loads: $result", result is PluginLoader.Result.Loaded)
        val param = (result as PluginLoader.Result.Loaded).plugin.params.single()

        assertEquals("Width", param.name)
        assertEquals("Mixes the untouched stereo back over the result.", param.help)
    }

    @Test
    fun `a parameter without a name or help gets its id as name and empty help`() {
        val plain =
            """
            plugin { id = "x", name = "X", version = "1" }
            local amount = param.number { id = "amount", default = 0 }
            function build(g, ctx)
              local o = {}
              for c = 0, ctx.channels - 1 do o[c] = g.mul(g.input(c), amount) end
              return o
            end
            """.trimIndent()

        val plugin = (PluginLoader().load(plain, 44_100, 2) as PluginLoader.Result.Loaded).plugin

        assertEquals("amount", plugin.params.single().name)
        assertEquals("", plugin.params.single().help)
    }
}
