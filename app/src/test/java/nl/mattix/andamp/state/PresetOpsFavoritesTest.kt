// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.state.presets.Favorite
import nl.mattix.andamp.visualizer.core.PresetGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Favorites: presets from any pack, played as one source, kept across launches, and gone with
 * their pack.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresetOpsFavoritesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `favorites from two packs play as one list, in the order they were added`() =
        runTest {
            makePack("one", "a.milk", "b.milk")
            makePack("two", "c.milk")
            val (state, ops) = started(VisPlugin.Milkdrop, pack = "one")

            ops.toggleFavorite(file("two", "c.milk"))
            ops.toggleFavorite(file("one", "a.milk"))
            ops.selectFavorites()

            assertTrue(state.presetFavorites)
            assertEquals(listOf(file("two", "c.milk"), file("one", "a.milk")), ops.active?.files)
            assertEquals(ops.active?.files, ops.listing(null))
            assertTrue(ops.isFavorite(file("one", "a.milk")))
            assertFalse(ops.isFavorite(file("one", "b.milk")))
        }

    /** The selector's stars read [PresetOps.isFavorite] in composition, so a change must reach them. */
    @Test
    fun `a new favorite is seen by whoever reads it in a snapshot`() =
        runTest {
            makePack("one", "a.milk")
            val (_, ops) = started(VisPlugin.Milkdrop, pack = "one")
            val seen = mutableListOf<Boolean>()
            val watching =
                launch {
                    androidx.compose.runtime
                        .snapshotFlow { ops.isFavorite(file("one", "a.milk")) }
                        .collect { seen += it }
                }
            advanceUntilIdle()

            ops.toggleFavorite(file("one", "a.milk"))
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
            advanceUntilIdle()
            watching.cancel()

            assertEquals(listOf(false, true), seen)
        }

    @Test
    fun `the textures of every pack a favorite comes from are searched`() =
        runTest {
            makePack("one", "a.milk", "textures/x.png")
            makePack("two", "c.milk")
            val (_, ops) = started(VisPlugin.Milkdrop, pack = "one")

            ops.toggleFavorite(file("one", "a.milk"))
            ops.selectFavorites()

            val ofOne =
                ops.packs
                    .first { it.name == "one" }
                    .textureDirs
                    .map { it.absolutePath }
            assertTrue(File(root(), "one/textures").absolutePath in ofOne)
            assertEquals("only the packs the favorites come from", ofOne, ops.active?.textureDirs)
        }

    @Test
    fun `favorites are kept across launches, and so is playing them`() =
        runTest {
            makePack("one", "a.avs")
            val (_, ops) = started(VisPlugin.Avs, pack = "one")
            ops.toggleFavorite(file("one", "a.avs"))
            ops.selectFavorites()

            val (again, opsAgain) = started(plugin = null)

            assertTrue(again.presetFavorites)
            assertEquals(listOf(file("one", "a.avs")), opsAgain.activeAvsFiles)
            assertNull("favorites are not one pack's directory", opsAgain.activeAvsDir)
        }

    @Test
    fun `each engine keeps separate favorites`() =
        runTest {
            makePack("both", "a.milk", "b.avs")
            val (state, ops) = started(VisPlugin.Milkdrop, pack = "both")
            ops.toggleFavorite(file("both", "a.milk"))

            ops.selectPlugin(VisPlugin.Avs)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), ops.favoriteFiles(VisPlugin.Avs))
            ops.selectFavorites()
            assertFalse("an engine with no favorites cannot play them", state.presetFavorites)
        }

    @Test
    fun `removing a pack takes its favorites along`() =
        runTest {
            makePack("one", "a.milk")
            makePack("two", "c.milk")
            val (state, ops) = started(VisPlugin.Milkdrop, pack = "one")
            ops.toggleFavorite(file("one", "a.milk"))
            ops.toggleFavorite(file("two", "c.milk"))
            ops.selectFavorites()

            ops.remove(ops.packs.first { it.name == "two" })
            advanceUntilIdle()

            assertEquals(listOf(Favorite(VisPlugin.Milkdrop, "one", "a.milk")), ops.favorites)
            assertEquals(listOf(file("one", "a.milk")), ops.active?.files)
            assertTrue(state.presetFavorites)
        }

    @Test
    fun `with the last favorite's pack gone, the engine goes back to its pack`() =
        runTest {
            makePack("one", "a.milk")
            makePack("two", "c.milk")
            val (state, ops) = started(VisPlugin.Milkdrop, pack = "one")
            ops.toggleFavorite(file("two", "c.milk"))
            ops.selectFavorites()

            ops.remove(ops.packs.first { it.name == "two" })
            advanceUntilIdle()

            assertFalse(state.presetFavorites)
            assertEquals("one", state.presetPack)
        }

    @Test
    fun `unstarring the last favorite while they play goes back to the pack`() =
        runTest {
            makePack("one", "a.milk")
            val (state, ops) = started(VisPlugin.Milkdrop, pack = "one")
            ops.toggleFavorite(file("one", "a.milk"))
            ops.selectFavorites()

            ops.toggleFavorite(file("one", "a.milk"))

            assertFalse(state.presetFavorites)
            assertEquals("one", state.presetPack)
            assertTrue(ops.favorites.isEmpty())
        }

    @Test
    fun `picking a preset of a list not playing loads that list, then goes to it`() =
        runTest {
            makePack("one", "a.milk", "b.milk")
            val (state, ops) = started(VisPlugin.Milkdrop, pack = "one")
            ops.toggleFavorite(file("one", "b.milk"))

            ops.play(file("one", "b.milk"), fromFavorites = true)

            assertTrue(state.presetFavorites)
            assertEquals(file("one", "b.milk"), state.presetWanted)
        }

    @Test
    fun `picking a preset of the list playing goes straight to it`() =
        runTest {
            makePack("one", "a.milk", "b.milk")
            val (state, ops) = started(VisPlugin.Milkdrop, pack = "one")
            state.presetPaths = listOf(file("one", "a.milk"), file("one", "b.milk"))
            var went = -1
            state.visualCommands =
                object : VisualCommands {
                    override fun nextPreset() = Unit

                    override fun previousPreset() = Unit

                    override fun goToPreset(index: Int) {
                        went = index
                    }
                }

            ops.play(file("one", "b.milk"), fromFavorites = false)

            assertEquals(1, went)
            assertNull(state.presetWanted)
        }

    @Test
    fun `picking a pack leaves the favorites`() =
        runTest {
            makePack("one", "a.milk")
            makePack("two", "c.milk")
            val (state, ops) = started(VisPlugin.Milkdrop, pack = "one")
            ops.toggleFavorite(file("one", "a.milk"))
            ops.selectFavorites()

            ops.select("two")

            assertFalse(state.presetFavorites)
            assertEquals(listOf(file("two", "c.milk")), ops.active?.files)
        }

    @Test
    fun `what an engine could not show is kept by file`() =
        runTest {
            val (_, ops) = started(VisPlugin.Milkdrop)
            val gaps = PresetGaps(unreadable = true, reason = "bad")

            ops.record("/x.milk", gaps)
            ops.record(null, gaps)

            assertEquals(mapOf("/x.milk" to gaps), ops.health.toMap())
        }

    @Test
    fun `what a preset could not show is kept by its content, and found again after a reinstall`() =
        runTest {
            makePack("one", "a.milk", "b.milk")
            File(root(), "one/a.milk").writeText("a preset that draws nothing")
            val (_, ops) = started(VisPlugin.Milkdrop, pack = "one")
            val blank = PresetGaps(blank = true)
            ops.record(file("one", "a.milk"), blank)
            advanceUntilIdle()
            // the pack goes, and comes back under another name with the file renamed
            ops.remove(ops.packs.first { it.name == "one" })
            advanceUntilIdle()
            makePack("again", "renamed.milk", "b.milk")
            File(root(), "again/renamed.milk").writeText("a preset that draws nothing")

            val (_, later) = started(VisPlugin.Milkdrop, pack = "again")

            assertEquals(blank, later.health[file("again", "renamed.milk")])
            assertEquals(setOf(file("again", "renamed.milk")), later.skipped)
        }

    @Test
    fun `a preset that is changed starts over`() =
        runTest {
            makePack("one", "a.milk")
            val (_, ops) = started(VisPlugin.Milkdrop, pack = "one")
            ops.record(file("one", "a.milk"), PresetGaps(unreadable = true))
            advanceUntilIdle()
            File(root(), "one/a.milk").writeText("fixed")

            val (_, later) = started(VisPlugin.Milkdrop, pack = "one")

            assertNull(later.health[file("one", "a.milk")])
        }

    @Test
    fun `a preset left out only in part plays, one that does not show is skipped`() =
        runTest {
            val (_, ops) = started(VisPlugin.Milkdrop)

            ops.record("/p/partly.milk", PresetGaps(missing = listOf("warp shader")))
            ops.record("/p/broken.milk", PresetGaps(unreadable = true))
            ops.record("/p/black.milk", PresetGaps(blank = true))

            assertEquals(setOf("/p/broken.milk", "/p/black.milk"), ops.skipped)
        }

    @Test
    fun `picking a skipped preset by hand forgets what was kept, so it is tried again`() =
        runTest {
            makePack("one", "a.milk")
            val (_, ops) = started(VisPlugin.Milkdrop, pack = "one")
            ops.record(file("one", "a.milk"), PresetGaps(blank = true))
            advanceUntilIdle()

            ops.play(file("one", "a.milk"), fromFavorites = false)
            advanceUntilIdle()
            val (_, later) = started(VisPlugin.Milkdrop, pack = "one")

            assertTrue(ops.skipped.isEmpty())
            assertNull("forgotten across launches too", later.health[file("one", "a.milk")])
        }

    /** A started [PresetOps] on [plugin] and [pack]; a null plugin keeps what was remembered. */
    private fun TestScope.started(
        plugin: VisPlugin?,
        pack: String? = null,
    ): Pair<WinampState, PresetOps> {
        val state = WinampState()
        val ops = PresetOps(context, state, this, io = UnconfinedTestDispatcher(testScheduler))
        ops.start()
        advanceUntilIdle()
        if (plugin != null) {
            ops.selectPlugin(plugin)
            advanceUntilIdle()
            ops.select(pack)
        }
        return state to ops
    }

    private fun root() = File(context.filesDir, "presets")

    private fun file(
        pack: String,
        name: String,
    ) = File(root(), "$pack/$name").absolutePath

    private fun makePack(
        name: String,
        vararg files: String,
    ) {
        val dir = File(root(), name)
        files.forEach { File(dir, it).apply { parentFile?.mkdirs() }.writeText("x") }
    }
}
