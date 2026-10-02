// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.plugin

import nl.mattix.andamp.core.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout a plug-in asks for: five widgets and three options, as in section 3 of
 * docs/dsp-plugin-spec.md. An unknown option, or a widget on a parameter it cannot draw, fails
 * the load.
 */
class UiTreeTest {
    private val loader = PluginLoader()

    /** Loads a plug-in that passes its audio through, unless [build] says otherwise. */
    private fun load(
        body: String,
        build: String = "o[c] = g.input(c)",
    ) = loader.load(
        """
        plugin { id = "x", name = "X", version = "1" }
        $body
        function build(g, ctx)
          local o = {}
          for c = 0, ctx.channels - 1 do $build end
          return o
        end
        """.trimIndent(),
        sampleRate = 44_100,
        channels = 2,
    )

    private fun tree(
        body: String,
        build: String = "o[c] = g.input(c)",
    ): List<UiNode> {
        val result = load(body, build)
        assertTrue("the plug-in loads: ${codes(result)}", result is PluginLoader.Result.Loaded)
        return (result as PluginLoader.Result.Loaded).plugin.ui
    }

    private fun codes(result: PluginLoader.Result) =
        (result as? PluginLoader.Result.Rejected)?.errors?.joinToString { "${it.code}: ${it.message}" } ?: ""

    private fun refusal(body: String): String {
        val result = load(body)
        assertTrue("the plug-in is rejected", result is PluginLoader.Result.Rejected)
        return codes(result)
    }

    @Test
    fun `a plug-in without a ui block has an empty tree`() {
        // the host then lays the parameters out in declaration order
        assertEquals(emptyList<UiNode>(), tree("""local a = param.number { id = "a", default = 0 }"""))
    }

    @Test
    fun `widgets keep the order they were written in`() {
        val ui =
            tree(
                """
                local a = param.number { id = "a", default = 0 }
                local b = param.toggle { id = "b", default = true }
                ui { slider(a), label("in between"), toggle(b) }
                """.trimIndent(),
            )

        assertEquals(3, ui.size)
        assertEquals(UiNode.Control(0, UiNode.Kind.SLIDER), ui[0])
        assertEquals(UiNode.Label("in between"), ui[1])
        assertEquals(UiNode.Control(1, UiNode.Kind.TOGGLE), ui[2])
    }

    @Test
    fun `a group is a titled card holding its own widgets`() {
        val ui =
            tree(
                """
                local a = param.number { id = "a", default = 0 }
                ui { group("Output", { slider(a) }) }
                """.trimIndent(),
            )

        val group = ui.single() as UiNode.Group
        assertEquals("Output", group.name)
        assertEquals(listOf(UiNode.Control(0, UiNode.Kind.SLIDER)), group.items)
        assertNull("the group has no enabling toggle", group.enabledBy)
    }

    @Test
    fun `a group may have no title at all`() {
        val ui =
            tree(
                """
                local a = param.number { id = "a", default = 0 }
                ui { group(nil, { slider(a) }) }
                """.trimIndent(),
            )

        assertNull((ui.single() as UiNode.Group).name)
    }

    @Test
    fun `a group can be switched by a toggle`() {
        val ui =
            tree(
                """
                local on = param.toggle { id = "on", default = true }
                local a = param.number { id = "a", default = 0 }
                ui { group("EBS", { slider(a) }, { enabledBy = on }) }
                """.trimIndent(),
                build = "o[c] = g.crossfade(g.input(c), g.mul(g.input(c), a), on)",
            )

        assertEquals(0, (ui.single() as UiNode.Group).enabledBy)
    }

    @Test
    fun `a widget's label and help options override the parameter's`() {
        val ui =
            tree(
                """
                local a = param.number { id = "a", name = "A", help = "the declared one", default = 0 }
                ui { slider(a, { label = "Called this here", help = "and explained this way" }) }
                """.trimIndent(),
            )

        val control = ui.single() as UiNode.Control
        assertEquals("Called this here", control.label)
        assertEquals("and explained this way", control.help)
    }

    @Test
    fun `a choice widget on a choice parameter loads`() {
        val ui =
            tree(
                """
                local m = param.choice {
                  id = "mode", name = "Mode",
                  options = { Chorus = 0, Flanger = 1 }, default = "Flanger",
                }
                ui { choice(m) }
                """.trimIndent(),
            )

        assertEquals(UiNode.Control(0, UiNode.Kind.CHOICE), ui.single())
    }

    @Test
    fun `a widget on the wrong kind of parameter is refused`() {
        val number = """local a = param.number { id = "a", default = 0 }"""
        val switch = """local b = param.toggle { id = "b", default = true }"""

        assertTrue("cannot draw 'a'" in refusal("$number\nui { toggle(a) }"))
        assertTrue("cannot draw 'b'" in refusal("$switch\nui { slider(b) }"))
        assertTrue("cannot draw 'a'" in refusal("$number\nui { choice(a) }"))
    }

    @Test
    fun `an unknown option is refused`() {
        // `greatherThan` stands for any key the widget does not have
        val body =
            """
            local a = param.number { id = "a", default = 0 }
            ui { slider(a, { greatherThan = 0 }) }
            """.trimIndent()

        assertTrue("has no option 'greatherThan'" in refusal(body))
    }

    @Test
    fun `help on a label is refused`() {
        // a label takes no help option
        val body = """ui { label("A line", { help = "about the line" }) }"""

        assertTrue("label has no option 'help'" in refusal(body))
    }

    @Test
    fun `only a toggle can switch a group`() {
        val body =
            """
            local a = param.number { id = "a", default = 0 }
            ui { group("G", { slider(a) }, { enabledBy = a }) }
            """.trimIndent()

        assertTrue("enabledBy cannot draw 'a'" in refusal(body))
    }

    @Test
    fun `a ui block may only hold widgets`() {
        assertTrue("may only hold widgets" in refusal("""ui { "just a string" }"""))
    }

    @Test
    fun `a choice without options, without a default or with an unknown default is refused`() {
        assertTrue(
            "has no options" in
                refusal("""local m = param.choice { id = "m", options = {}, default = "a" }"""),
        )
        assertTrue(
            "does not say where it starts" in
                refusal("""local m = param.choice { id = "m", options = { A = 0 } }"""),
        )
        assertTrue(
            "not one of its options" in
                refusal("""local m = param.choice { id = "m", options = { A = 0 }, default = "B" }"""),
        )
    }

    @Test
    fun `a preset is a name and the values it sets`() {
        val result =
            load(
                """
                local a = param.number { id = "a", min = 0, max = 10, default = 1 }
                local on = param.toggle { id = "on", default = false }
                presets {
                  { name = "Flat", values = { a = 0, on = false } },
                  { name = "Loud", values = { a = 8, on = true } },
                }
                """.trimIndent(),
            )

        val presets = (result as PluginLoader.Result.Loaded).plugin.presets

        assertEquals(listOf("Flat", "Loud"), presets.map { it.name })
        // a toggle's value is written as a boolean and stored as 0 or 1
        assertEquals(mapOf("a" to 0f, "on" to 0f), presets[0].values)
        assertEquals(mapOf("a" to 8f, "on" to 1f), presets[1].values)
    }

    @Test
    fun `a preset need not mention every control`() {
        // the preset holds only the values it names
        val result =
            load(
                """
                local a = param.number { id = "a", default = 1 }
                local b = param.number { id = "b", default = 1 }
                presets { { name = "Half", values = { a = 0.5 } } }
                """.trimIndent(),
            )

        assertEquals(
            mapOf("a" to 0.5f),
            (result as PluginLoader.Result.Loaded)
                .plugin.presets
                .single()
                .values,
        )
    }

    @Test
    fun `a preset that names a control the plug-in does not have is refused`() {
        // `aa` is not a parameter of this plug-in
        val body =
            """
            local a = param.number { id = "a", default = 0 }
            presets { { name = "Odd", values = { aa = 1 } } }
            """.trimIndent()

        assertTrue("'aa', which is not one of its controls" in refusal(body))
    }

    @Test
    fun `a group switched by a toggle the graph never reads is refused`() {
        // the group would gray out while its part of the graph kept running
        val body =
            """
            local on = param.toggle { id = "on", default = true }
            local a = param.number { id = "a", default = 0 }
            ui { group("Room", { slider(a) }, { enabledBy = on }) }
            """.trimIndent()

        val why = refusal(body)

        assertTrue("unreadGate" in why)
        assertTrue("The group 'Room' is switched by 'on'" in why)
    }

    @Test
    fun `a nested group switched by a toggle the graph never reads is refused`() {
        // a nested group's `enabledBy` is checked the same way
        val body =
            """
            local on = param.toggle { id = "on", default = true }
            local a = param.number { id = "a", default = 0 }
            ui { group("Outer", { group("Room", { slider(a) }, { enabledBy = on }) }) }
            """.trimIndent()

        assertTrue("The group 'Room' is switched by 'on'" in refusal(body))
    }

    @Test
    fun `a group whose toggle the graph reads loads`() {
        // the same plug-in, with the bypass written in `build`
        val result =
            load(
                """
                local on = param.toggle { id = "on", default = true }
                local a = param.number { id = "a", default = 0 }
                ui { group("Room", { slider(a) }, { enabledBy = on }) }
                """.trimIndent(),
                build = "o[c] = g.crossfade(g.input(c), g.mul(g.input(c), a), on)",
            )

        assertTrue("the plug-in loads: ${codes(result)}", result is PluginLoader.Result.Loaded)
    }

    @Test
    fun `a parameter's display table is loaded`() {
        val result =
            load(
                """
                local mix = param.number {
                  id = "mix", min = 0, max = 1, default = 0.5, unit = "pc",
                  display = { scale = 100, decimals = 0, zero = "OFF" },
                }
                """.trimIndent(),
            )

        val param = (result as PluginLoader.Result.Loaded).plugin.params.single()

        assertEquals(100f, param.displayScale, 0f)
        assertEquals(0, param.displayDecimals)
        assertEquals("OFF", param.displayZero)
    }

    @Test
    fun `a parameter without a display table gets the defaults`() {
        val result = load("""local a = param.number { id = "a", default = 0 }""")

        val param = (result as PluginLoader.Result.Loaded).plugin.params.single()

        assertEquals(1f, param.displayScale, 0f)
        assertEquals("the decimals are left for the host to work out from the range", -1, param.displayDecimals)
        assertEquals("", param.displayZero)
    }

    @Test
    fun `a choice is not smoothed`() {
        val result =
            load(
                """
                local m = param.choice { id = "m", options = { A = 0, B = 1 }, default = "A" }
                """.trimIndent(),
            )

        val param = (result as PluginLoader.Result.Loaded).plugin.params.single()
        // the values between two options are other options
        assertEquals(0f, param.smoothMs, 0f)
        assertEquals(mapOf("A" to 0f, "B" to 1f), param.choices)
    }
}
