// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.plugin

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Loads the plug-ins in docs/examples and the bundled ones, which are what a plug-in author
 * reads first. An example with a call the binding does not have has to fail here.
 *
 * The ones under refused/ must not load, and must come back with a reason.
 */
class ExamplePluginsTest {
    private val examples = File("../../docs/examples")

    /** The bundled plug-ins, checked the same way. */
    private val bundled = File("../../backend/media3/src/main/resources/plugins")

    private fun load(file: File) = PluginLoader().load(file.readText(), RATE, CHANNELS)

    private fun File.luaFiles(): List<File> =
        listFiles { f: File -> f.isFile && f.extension == "lua" }.orEmpty().sortedBy { it.name }

    @Test
    fun `every example and bundled plug-in loads`() {
        val files =
            examples
                .listFiles { f: File -> f.isFile && f.extension == "lua" }
                ?.sortedBy { it.name }
                .orEmpty()
        assertTrue("examples exist in ${examples.absolutePath}", files.isNotEmpty())

        val shipped = bundled.listFiles { f: File -> f.isFile && f.extension == "lua" }.orEmpty()
        assertTrue("bundled plug-ins exist in ${bundled.absolutePath}", shipped.isNotEmpty())

        (files + shipped).forEach { file ->
            val result = load(file)
            val why = (result as? PluginLoader.Result.Rejected)?.errors?.joinToString { "${it.code}: ${it.message}" }
            assertTrue("${file.name} loads: $why", result is PluginLoader.Result.Loaded)
        }
    }

    @Test
    fun `bundled plug-ins use the app's id namespace`() {
        // the id is also the key a plug-in's settings are stored under
        val borrowed =
            bundled.luaFiles().mapNotNull { file ->
                val loaded = load(file) as? PluginLoader.Result.Loaded ?: return@mapNotNull null
                "${file.name}: ${loaded.plugin.id}".takeUnless { loaded.plugin.id.startsWith("$OURS.") }
            }
        assertTrue("every bundled plug-in id starts with $OURS: $borrowed", borrowed.isEmpty())
    }

    @Test
    fun `examples that load use the example id namespace`() {
        // the examples are published as they are, so their ids end up in listeners' racks
        val borrowed =
            examples.luaFiles().mapNotNull { file ->
                val loaded = load(file) as? PluginLoader.Result.Loaded ?: return@mapNotNull null
                "${file.name}: ${loaded.plugin.id}".takeUnless { loaded.plugin.id.startsWith("$OURS.example.") }
            }
        assertTrue("every example id starts with $OURS.example: $borrowed", borrowed.isEmpty())
    }

    @Test
    fun `examples and bundled plug-ins write only keys the host reads`() {
        // a key the loader ignores, such as a `step`, looks as if it did something
        val ignored =
            (examples.luaFiles() + bundled.luaFiles()).flatMap { file ->
                val loaded = load(file) as? PluginLoader.Result.Loaded ?: return@flatMap emptyList<String>()
                loaded.plugin.unread.map { "${file.name}: $it" }
            }
        assertTrue("every key written is one the host reads: $ignored", ignored.isEmpty())
    }

    @Test
    fun `no percentage parameter has a range above one without a display scale`() {
        // the host multiplies a `pc` value by a hundred unless `display.scale` is set, so a
        // 0..100 range would read a hundred times over; the spec asks for 0..1 with
        // `display = { scale = 100 }`
        val wrong =
            (examples.luaFiles() + bundled.luaFiles())
                .flatMap { file ->
                    val loaded = load(file) as? PluginLoader.Result.Loaded ?: return@flatMap emptyList<String>()
                    loaded.plugin.params
                        .filter { param -> param.unit == "pc" && param.max > 1f && param.displayScale == 1f }
                        .map { param -> "${file.name}: ${param.id} is 0..${param.max}" }
                }
        assertTrue("every percentage with a range above one has a display scale: $wrong", wrong.isEmpty())
    }

    @Test
    fun `the refused examples are rejected with a message`() {
        val files = File(examples, "refused").listFiles { f: File -> f.isFile && f.extension == "lua" }.orEmpty()
        assertTrue("refused examples exist", files.isNotEmpty())

        files.forEach { file ->
            val result = load(file)
            assertTrue("${file.name} is rejected", result is PluginLoader.Result.Rejected)
            assertTrue(
                "${file.name} gives a message for every error",
                (result as PluginLoader.Result.Rejected).errors.all { it.message.isNotBlank() },
            )
        }
    }

    private companion object {
        /** The id namespace of bundled plug-ins and examples. */
        const val OURS = "nl.mattix.andamp"

        const val RATE = 44_100
        const val CHANNELS = 2
    }
}
