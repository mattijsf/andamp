// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Switching engines: each remembers its own pack, and neither is handed a pack it cannot
 * run. [WinampState.presetPack] is the current engine's selection.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresetOpsPluginTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `each engine keeps its own pack across a switch`() =
        runTest {
            makePack("milk-pack", "a.milk")
            makePack("avs-pack", "b.avs")
            val state = WinampState()
            val ops = PresetOps(context, state, this, io = UnconfinedTestDispatcher(testScheduler))
            ops.start()
            advanceUntilIdle()

            state.visPlugin = VisPlugin.Milkdrop
            ops.select("milk-pack")
            ops.selectPlugin(VisPlugin.Avs)
            advanceUntilIdle()
            ops.select("avs-pack")
            ops.selectPlugin(VisPlugin.Milkdrop)
            advanceUntilIdle()

            assertEquals("milk-pack", state.presetPack)
            ops.selectPlugin(VisPlugin.Avs)
            advanceUntilIdle()
            assertEquals("avs-pack", state.presetPack)
        }

    @Test
    fun `a fresh install lands on the bundled pack`() =
        runTest {
            val state = WinampState()
            val ops = PresetOps(context, state, this, io = UnconfinedTestDispatcher(testScheduler))

            ops.start()
            advanceUntilIdle()

            assertEquals("AndAmp", state.presetPack)
            assertEquals(File(root(), "AndAmp"), ops.activeAvsDir)
        }

    @Test
    fun `choosing the idle preset over the bundled pack sticks across restarts`() =
        runTest {
            val state = WinampState()
            val ops = PresetOps(context, state, this, io = UnconfinedTestDispatcher(testScheduler))
            ops.start()
            advanceUntilIdle()
            ops.select(null)
            advanceUntilIdle()

            val secondRun = WinampState()
            val again = PresetOps(context, secondRun, this, io = UnconfinedTestDispatcher(testScheduler))
            again.start()
            advanceUntilIdle()

            assertNull("the choice of the idle preset is kept", secondRun.presetPack)
        }

    @Test
    fun `a remembered pack the engine cannot run degrades to its idle preset`() =
        runTest {
            makePack("milk-only", "a.milk")
            VisualizerStore(context).save(
                VisualizerMemory(plugin = VisPlugin.Avs, packs = mapOf(VisPlugin.Avs to "milk-only")),
            )
            val state = WinampState()
            val ops = PresetOps(context, state, this, io = UnconfinedTestDispatcher(testScheduler))

            ops.start()
            advanceUntilIdle()

            assertNull("the AVS engine is not given a .milk-only pack", state.presetPack)
        }

    @Test
    fun `the active pack answers per engine`() =
        runTest {
            makePack("both", "a.milk", "b.avs")
            val state = WinampState()
            val ops = PresetOps(context, state, this, io = UnconfinedTestDispatcher(testScheduler))
            ops.start()
            advanceUntilIdle()
            state.visPlugin = VisPlugin.Avs
            ops.select("both")

            assertEquals(File(root(), "both"), ops.activeAvsDir)
            assertEquals(File(root(), "both").absolutePath, ops.active?.presetsDir)
        }

    @Test
    fun `importing an avs-only zip succeeds and selects the pack under avs`() =
        runTest {
            val state = WinampState()
            val ops = PresetOps(context, state, this, io = UnconfinedTestDispatcher(testScheduler))
            ops.start()
            advanceUntilIdle()
            state.visPlugin = VisPlugin.Avs

            ops.import(zipOf("one.avs"), "AVS pack")
            advanceUntilIdle()

            assertNull("the import finishes without a failure", ops.importing)
            assertEquals("AVS pack", state.presetPack)
        }

    @Test
    fun `importing a pack the current engine cannot run installs it without selecting it`() =
        runTest {
            val state = WinampState()
            val ops = PresetOps(context, state, this, io = UnconfinedTestDispatcher(testScheduler))
            ops.start()
            advanceUntilIdle()
            state.visPlugin = VisPlugin.Avs

            ops.import(zipOf("only.milk"), "Milk pack")
            advanceUntilIdle()

            assertNull("the import finishes without a failure", ops.importing)
            assertEquals("the import leaves the selection on AndAmp", "AndAmp", state.presetPack)
            assertEquals(listOf("AndAmp", "Milk pack"), ops.packs.map { it.name })
        }

    private fun zipOf(vararg names: String): java.io.ByteArrayInputStream {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            names.forEach {
                zip.putNextEntry(java.util.zip.ZipEntry(it))
                zip.write("preset".toByteArray())
                zip.closeEntry()
            }
        }
        return java.io.ByteArrayInputStream(bytes.toByteArray())
    }

    private fun root() = File(context.filesDir, "presets")

    private fun makePack(
        name: String,
        vararg files: String,
    ) {
        val dir = File(root(), name).apply { mkdirs() }
        files.forEach { File(dir, it).writeText("x") }
    }
}
