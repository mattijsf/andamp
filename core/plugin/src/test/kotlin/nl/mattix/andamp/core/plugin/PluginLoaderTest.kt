// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.plugin

import nl.mattix.andamp.core.dsp.GraphCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the loader accepts, what it refuses, and what the sandbox keeps a script from doing. */
class PluginLoaderTest {
    private val loader = PluginLoader()

    private fun load(source: String) = loader.load(source, sampleRate = 44_100, channels = 2)

    private fun codes(source: String) = (load(source) as? PluginLoader.Result.Rejected)?.errors?.map { it.code } ?: emptyList()

    private fun loaded(source: String): PluginSpec {
        val result = load(source)
        assertTrue("the plug-in loads: $result", result is PluginLoader.Result.Loaded)
        return (result as PluginLoader.Result.Loaded).plugin
    }

    private val mono =
        """
        plugin { id = "org.example.mono", name = "Mono", version = "1.0.0", author = "nobody" }
        function build(g, ctx)
          local sum = g.mul(g.add(g.input(0), g.input(1)), 0.5)
          local out = {}
          for ch = 0, ctx.channels - 1 do out[ch] = sum end
          return out
        end
        """.trimIndent()

    @Test
    fun `a plug-in with metadata and a build function loads`() {
        val plugin = loaded(mono)

        assertEquals("org.example.mono", plugin.id)
        assertEquals("Mono", plugin.name)
        assertTrue("the plug-in describes a graph", plugin.graph.nodes.isNotEmpty())
    }

    @Test
    fun `the loaded graph runs and mixes the channels to mono`() {
        val engine = GraphCompiler.engine(loaded(mono).graph)!!
        val frame = floatArrayOf(1f, 0f)

        engine.process(frame)

        assertEquals("the left channel carries the mono mix", 0.5f, frame[0], 0f)
        assertEquals(0.5f, frame[1], 0f)
    }

    @Test
    fun `a plug-in with missing metadata does not load`() {
        assertTrue("missingMetadata" in codes("""plugin { id = "x" }"""))
    }

    @Test
    fun `a plug-in that will not parse does not load`() {
        assertTrue("syntax" in codes("this is not lua at all ->"))
    }

    @Test
    fun `a plug-in that throws is rejected with the script's message`() {
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            function build(g, ctx) error("no") end
            """.trimIndent()

        val refused = load(source) as PluginLoader.Result.Rejected
        assertEquals(listOf("failed"), refused.errors.map { it.code })
        assertTrue(
            "the message ends with the script's own error",
            refused.errors
                .single()
                .message
                .endsWith("plugin:2 no"),
        )
    }

    @Test
    fun `a plug-in that never finishes is rejected as too slow`() {
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            local n = 0
            while true do n = n + 1 end
            """.trimIndent()

        // the script loops forever, so the loader has to stop waiting for it
        assertTrue("tooSlow" in codes(source))
    }

    /**
     * Lists every global, so that a library that gains a function, or one loaded for the
     * host's own use, fails this test.
     */
    @Test
    fun `a plug-in sees these names and no others`() {
        val globals = Sandbox.globals()
        val seen = mutableListOf<String>()
        var key = org.luaj.vm2.LuaValue.NIL
        while (true) {
            val next = globals.next(key)
            key = next.arg1()
            if (key.isnil()) break
            seen += key.tojstring()
        }

        assertEquals(
            listOf(
                "_VERSION",
                "assert",
                "bit32",
                "error",
                "ipairs",
                "math",
                "next",
                "pairs",
                "pcall",
                "print",
                "select",
                "string",
                "table",
                "tonumber",
                "tostring",
                "type",
                "xpcall",
            ),
            seen.sorted(),
        )
    }

    @Test
    fun `a plug-in longer than the limit is refused`() {
        val huge = "-- " + "x".repeat(300 * 1024)

        assertTrue("tooLong" in codes(huge))
    }

    @Test
    fun `the forbidden names are nil inside a plug-in`() {
        Sandbox.forbidden.forEach { name ->
            val source =
                """
                plugin { id = "x", name = "X", version = "1" }
                if $name ~= nil then error("$name is reachable") end
                function build(g, ctx) local o = {} for c = 0, ctx.channels - 1 do o[c] = g.input(c) end return o end
                """.trimIndent()

            assertTrue("$name is nil inside a plug-in: ${codes(source)}", load(source) is PluginLoader.Result.Loaded)
        }
    }

    @Test
    fun `an unknown primitive is named in the error`() {
        // a plain table would fail with "attempt to call nil", which does not name it
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            function build(g, ctx)
              return { [0] = g.rate { input = g.input(0) }, [1] = g.input(1) }
            end
            """.trimIndent()

        val refused = load(source) as PluginLoader.Result.Rejected

        assertTrue(refused.errors.any { "there is no primitive called 'rate'" in it.message })
    }

    @Test
    fun `a plug-in that asks for another host version is refused`() {
        val source =
            """
            plugin { id = "x", name = "X", version = "1", api = 7 }
            function build(g, ctx) local o = {} for c = 0, ctx.channels - 1 do o[c] = g.input(c) end return o end
            """.trimIndent()

        assertTrue("wrongApi" in codes(source))
        // a plug-in that names no version loads
        assertTrue(
            load(
                """
                plugin { id = "x", name = "X", version = "1" }
                function build(g, ctx) local o = {} for c = 0, ctx.channels - 1 do o[c] = g.input(c) end return o end
                """.trimIndent(),
            ) is PluginLoader.Result.Loaded,
        )
    }

    @Test
    fun `a unit that is not an LV2 symbol is refused`() {
        // the unit symbols are LV2's: `hz` is one, "Hz" is not
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            local f = param.number { id = "freq", min = 20, max = 20000, default = 1000, unit = "Hz" }
            function build(g, ctx)
              local o = {}
              for c = 0, ctx.channels - 1 do o[c] = g.mul(g.input(c), f) end
              return o
            end
            """.trimIndent()

        val refused = load(source) as PluginLoader.Result.Rejected

        assertTrue("unknownUnit" in refused.errors.map { it.code })
        assertTrue(refused.errors.any { "'freq' is in 'Hz'" in it.message })
    }

    @Test
    fun `a toggle is a number the graph reads as zero or one`() {
        // docs/dsp-plugin-spec.md section 2: a toggle reaches the graph as a value smoothed
        // like any other, so g.crossfade(dry, wet, toggle) is a bypass
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            local on = param.toggle { id = "on", name = "Enhanced Bass", default = true }
            local off = param.toggle { id = "off", name = "Off by default", default = false }
            function build(g, ctx)
              local o = {}
              for c = 0, ctx.channels - 1 do o[c] = g.crossfade(g.input(c), g.mul(g.input(c), 2), on) end
              return o
            end
            """.trimIndent()

        val plugin = loaded(source)

        val on = plugin.params.first { it.id == "on" }
        assertEquals(1f, on.default, 0f)
        assertEquals(0f, on.min, 0f)
        assertEquals(1f, on.max, 0f)
        assertTrue("the parameter is marked as a toggle", on.toggle)
        assertEquals(0f, plugin.params.first { it.id == "off" }.default, 0f)
    }

    @Test
    fun `a toggle without a default is refused`() {
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            local on = param.toggle { id = "on", name = "On" }
            function build(g, ctx)
              local o = {}
              for c = 0, ctx.channels - 1 do o[c] = g.mul(g.input(c), on) end
              return o
            end
            """.trimIndent()

        assertEquals(listOf("missingDefault"), codes(source))
    }

    @Test
    fun `a number without a default is refused`() {
        // the default is where the slider is drawn and the value the graph receives
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            local amount = param.number { id = "amount", min = 0, max = 1 }
            function build(g, ctx)
              local o = {}
              for c = 0, ctx.channels - 1 do o[c] = g.mul(g.input(c), amount) end
              return o
            end
            """.trimIndent()

        assertEquals(listOf("missingDefault"), codes(source))
    }

    @Test
    fun `a default outside the range is refused`() {
        // no drag could bring the slider back to such a default
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            local amount = param.number { id = "amount", min = -12, max = 12, default = 20 }
            function build(g, ctx)
              local o = {}
              for c = 0, ctx.channels - 1 do o[c] = g.mul(g.input(c), amount) end
              return o
            end
            """.trimIndent()

        val refused = load(source) as PluginLoader.Result.Rejected

        assertEquals(listOf("defaultOutOfRange"), refused.errors.map { it.code })
        assertTrue(
            refused.errors
                .single()
                .message
                .contains("'amount' starts at 20.0, outside its own -12.0 to 12.0"),
        )
    }

    @Test
    fun `a graph that fails validation is rejected as badGraph`() {
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            function build(g, ctx)
              local o = {}
              for c = 0, ctx.channels - 1 do o[c] = g.input(9) end
              return o
            end
            """.trimIndent()

        assertTrue("badGraph" in codes(source))
    }

    @Test
    fun `a parameter used in build reaches the audio at its default`() {
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            local amount = param.number { id = "amount", min = 0, max = 1, default = 0.25 }
            function build(g, ctx)
              local o = {}
              for c = 0, ctx.channels - 1 do o[c] = g.mul(g.input(c), amount) end
              return o
            end
            """.trimIndent()

        val plugin = loaded(source)

        assertEquals(1, plugin.params.size)
        assertEquals("amount", plugin.params[0].id)
        val frame = floatArrayOf(1f, 1f)
        GraphCompiler.engine(plugin.graph)!!.process(frame)
        assertEquals("the parameter reaches the audio at its default", 0.25f, frame[0], 0f)
    }

    @Test
    fun `a tap reads the line g delay returned`() {
        // one delay line with two readers
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            function build(g, ctx)
              local line = g.delay { input = g.input(0), time = 3, maxTime = 4 }
              return { [0] = g.tap { line = line, time = 1 }, [1] = line }
            end
            """.trimIndent()

        val engine = GraphCompiler.engine(loaded(source).graph)!!
        val heard =
            (0 until 5).map { at ->
                val frame = floatArrayOf(if (at == 0) 1f else 0f, 0f)
                engine.process(frame)
                frame.toList()
            }

        assertEquals("the tap reads the impulse one frame later", 1f, heard[1][0], 0f)
        assertEquals("the line outputs the impulse three frames later", 1f, heard[3][1], 0f)
    }

    @Test
    fun `a tap given a number for its line is refused`() {
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            function build(g, ctx)
              local line = g.delay { input = g.input(0), time = 3, maxTime = 4 }
              return { [0] = g.tap { line = 0, time = 1 }, [1] = line }
            end
            """.trimIndent()

        val refused = load(source) as PluginLoader.Result.Rejected

        assertTrue(refused.errors.any { "a tap's line is what g.delay returned" in it.message })
    }

    @Test
    fun `a structural key on a parameter is reported as unread`() {
        // build runs once per audio format and is not rerun when a parameter changes
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            local mode = param.choice { id = "mode", options = { A = 0, B = 1 }, default = "A", structural = true }
            function build(g, ctx)
              local o = {}
              for c = 0, ctx.channels - 1 do o[c] = g.crossfade(g.input(c), g.mul(g.input(c), 2), mode) end
              return o
            end
            """.trimIndent()

        assertEquals(listOf("mode.structural"), loaded(source).unread)
    }

    @Test
    fun `a plug-in that never finishes stops running once it is abandoned`() {
        // The worker must end too, or it would keep a core busy. The pcall checks that the
        // script cannot catch the stop.
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            while true do pcall(function() while true do end end) end
            """.trimIndent()
        val impatient = PluginLoader(PluginLoader.Budgets(maxLoadMillis = 100))

        assertEquals(
            listOf("tooSlow"),
            (impatient.load(source, 44_100, 2) as PluginLoader.Result.Rejected).errors.map { it.code },
        )

        val deadline = System.nanoTime() + 2_000_000_000L
        while (loading() && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse("the abandoned script stops running", loading())
    }

    private fun loading() = Thread.getAllStackTraces().keys.any { it.name == "plugin-load" && it.isAlive }

    @Test
    fun `one plug-in's change to the string library does not reach another`() {
        // LuaJ keeps one metatable for all strings, which must not be the string table of
        // the first load
        val patches =
            """
            plugin { id = "x", name = "X", version = "1" }
            string.upper = function() return "patched" end
            if ("x"):upper() ~= "patched" then error("its own patch is not what a method call reaches") end
            function build(g, ctx) local o = {} for c = 0, ctx.channels - 1 do o[c] = g.input(c) end return o end
            """.trimIndent()
        val trusts =
            """
            plugin { id = "y", name = "Y", version = "1" }
            if ("x"):upper() ~= "X" then error("another plug-in's patch reached this one") end
            function build(g, ctx) local o = {} for c = 0, ctx.channels - 1 do o[c] = g.input(c) end return o end
            """.trimIndent()

        assertTrue(codes(patches).toString(), load(patches) is PluginLoader.Result.Loaded)
        assertTrue(codes(trusts).toString(), load(trusts) is PluginLoader.Result.Loaded)
    }

    @Test
    fun `a choice's options are ordered by value`() {
        // a Lua table with named keys has no order, so the written order cannot be
        // recovered
        val source =
            """
            plugin { id = "x", name = "X", version = "1" }
            local m = param.choice {
              id = "m", default = "Low",
              options = { Low = 0, Lower = 1, Middle = 2, Upper = 3, High = 4, Highest = 5, Top = 6 },
            }
            function build(g, ctx) local o = {} for c = 0, ctx.channels - 1 do o[c] = g.mul(g.input(c), m) end return o end
            """.trimIndent()

        assertEquals(
            listOf("Low", "Lower", "Middle", "Upper", "High", "Highest", "Top"),
            loaded(source)
                .params
                .single()
                .choices.keys
                .toList(),
        )
    }
}
