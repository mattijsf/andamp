// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TagFill.run]: when the playlist hears about the tags of a picked folder. */
@OptIn(ExperimentalCoroutinesApi::class)
class TagFillTest {
    private fun rows(count: Int) =
        (0 until count).map { Track(id = "row-$it", artist = "", title = "", durationMs = 0, uri = "content://tree/$it") }

    private fun tagged(row: Track) = row.copy(title = "Title of ${row.id}")

    /** Every update the playlist got, with the virtual time it came at. */
    private class Heard {
        val updates = mutableListOf<Pair<Long, List<Track>>>()
        val rows get() = updates.flatMap { it.second }
    }

    @Test
    fun `the top of the folder is shown as soon as it is read`() =
        runTest {
            val heard = Heard()

            TagFill.run(rows(2_000), StandardTestDispatcher(testScheduler), read = {
                delay(READ_MS)
                tagged(it)
            }) {
                heard.updates += currentTime to it
            }

            val (at, first) = heard.updates.first()
            assertEquals(READ_MS, at)
            assertEquals((0 until TagFill.READERS).map { "row-$it" }, first.map { it.id })
        }

    @Test
    fun `a long folder updates the playlist by the clock, a few rows at a time`() =
        runTest {
            val heard = Heard()

            TagFill.run(rows(2_000), StandardTestDispatcher(testScheduler), read = {
                delay(READ_MS)
                tagged(it)
            }) {
                heard.updates += currentTime to it
            }

            val gaps = heard.updates.zipWithNext { a, b -> b.first - a.first }
            assertTrue("updates closer than the pace: ${gaps.min()}", gaps.min() >= TagFill.PACE_MS)
            // nothing read waits longer than one pace and one read
            assertTrue("a wait of ${gaps.max()} ms", gaps.max() <= TagFill.PACE_MS + READ_MS)
            val most = heard.updates.maxOf { it.second.size }
            assertTrue("$most rows in one update", most <= TagFill.READERS * (TagFill.PACE_MS / READ_MS + 1))
        }

    @Test
    fun `every row is filled in, from the top down`() =
        runTest {
            val heard = Heard()
            val rows = rows(2_000)

            TagFill.run(rows, StandardTestDispatcher(testScheduler), read = {
                delay(READ_MS)
                tagged(it)
            }) {
                heard.updates += currentTime to it
            }

            assertEquals(rows.map(::tagged), heard.rows)
        }

    @Test
    fun `slow reads are shown as they come`() =
        runTest {
            val heard = Heard()

            TagFill.run(rows(3 * TagFill.READERS), StandardTestDispatcher(testScheduler), read = {
                delay(3 * TagFill.PACE_MS)
                tagged(it)
            }) {
                heard.updates += currentTime to it
            }

            assertEquals(List(3) { TagFill.READERS }, heard.updates.map { it.second.size })
        }

    @Test
    fun `a row that cannot be read is left as it is`() =
        runTest {
            val heard = Heard()

            TagFill.run(rows(3), StandardTestDispatcher(testScheduler), read = { if (it.id == "row-1") null else tagged(it) }) {
                heard.updates += currentTime to it
            }

            assertEquals(listOf("row-0", "row-2"), heard.rows.map { it.id })
        }

    private companion object {
        const val READ_MS = 20L
    }
}
