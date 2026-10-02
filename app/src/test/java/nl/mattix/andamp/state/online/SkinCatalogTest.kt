// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Paging over a museum too big to hold: which pages a view asks for, which it does not, and
 * what happens when one fails. Time is virtual because the catalog samples: it waits out a
 * settle before reading the list again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkinCatalogTest {
    /** Answers with made-up skins, and counts what was asked of it. */
    private class FakeMuseum(
        val total: Int = 1_000,
        var failFrom: Int? = null,
        /** How long a failure takes to arrive; a slow one lands after the list has settled. */
        val failAfterMs: Long = 0,
        /** How many the museum hands out, which can be fewer than it counts. */
        val listed: Int = Int.MAX_VALUE,
    ) : SkinsSource {
        val asked = mutableListOf<Int>()

        /** Offsets whose fetch is held open, so a test can scroll away mid-flight. */
        val held = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var hold = false

        override suspend fun page(
            offset: Int,
            count: Int,
        ): SkinsPage {
            asked += offset
            if (offset == failFrom) {
                delay(failAfterMs)
                throw IOException("no network")
            }
            if (hold) held.getOrPut(offset) { CompletableDeferred() }.await()
            val items =
                (0 until count)
                    .map { at -> offset + at }
                    .filter { it < minOf(listed, total) }
                    .map { n -> OnlineSkin("m$n", "skin$n.wsz", "s$n", "d$n", museumUrl = null, nsfw = false) }
            return SkinsPage(total, offset, items)
        }
    }

    private val scopes = mutableListOf<CoroutineScope>()

    /** The catalog gets a scope of its own, which [catalogTest] cancels. */
    private fun TestScope.catalog(museum: FakeMuseum): SkinCatalog {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        scopes += scope
        return SkinCatalog(
            museum,
            scope,
            io = UnconfinedTestDispatcher(testScheduler),
            pageSize = PAGE,
            settleMs = SETTLE,
            retryAfterMs = COOLDOWN,
            now = { testScheduler.currentTime },
        )
    }

    /**
     * A test that stops the catalog before it ends. `runTest` finishes by running the scheduler
     * dry, and a catalog with a failed page keeps scheduling another look.
     */
    private fun catalogTest(body: suspend TestScope.() -> Unit) =
        runTest {
            try {
                body()
            } finally {
                scopes.forEach { it.cancel() }
            }
        }

    /**
     * Lets the catalog act on what it was told. `advanceUntilIdle` would not return while a
     * page keeps failing, because each failure schedules another look.
     */
    private fun TestScope.settle() {
        testScheduler.advanceTimeBy(SETTLE + 1)
        testScheduler.runCurrent()
    }

    @Test
    fun `showing the top of the list loads the first page and learns the size`() =
        catalogTest {
            val museum = FakeMuseum()
            val catalog = catalog(museum)

            catalog.show(0..9)
            settle()

            assertEquals(1_000, catalog.total)
            assertEquals("skin0.wsz", catalog.skins[0]?.filename)
            assertEquals(listOf(0, PAGE), museum.asked)
        }

    @Test
    fun `a catalog that has been let go stops asking`() =
        catalogTest {
            // both of the catalog's loops park on flows it owns, so neither
            // ends until close() cancels them
            val museum = FakeMuseum()
            val catalog = catalog(museum)
            catalog.show(0..9)
            settle()
            val askedWhileOpen = museum.asked.size

            catalog.close()
            catalog.show(500..509)
            settle()

            assertEquals("a closed catalog asks for no more pages", askedWhileOpen, museum.asked.size)
        }

    @Test
    fun `a jump to the far end loads that page and nothing between`() =
        catalogTest {
            val museum = FakeMuseum(total = 100_000)
            val catalog = catalog(museum)
            catalog.show(0..9)
            settle()
            museum.asked.clear()

            catalog.show(90_000..90_020)
            settle()

            assertEquals("skin90000.wsz", catalog.skins[90_000]?.filename)
            assertEquals(listOf(90_000, 90_000 + PAGE), museum.asked)
            assertNull("the pages in between are not fetched", catalog.skins[45_000])
        }

    @Test
    fun `a page already loaded is not asked for twice`() =
        catalogTest {
            val museum = FakeMuseum()
            val catalog = catalog(museum)

            catalog.show(0..5)
            settle()
            catalog.show(2..7)
            settle()
            catalog.show(0..9)
            settle()

            assertEquals(listOf(0, PAGE), museum.asked)
        }

    @Test
    fun `a range spanning two pages loads both`() =
        catalogTest {
            val museum = FakeMuseum()
            val catalog = catalog(museum)

            catalog.show((PAGE - 2)..(PAGE + 2))
            settle()

            assertEquals(listOf(0, PAGE, PAGE * 2), museum.asked)
        }

    @Test
    fun `a failed page is not asked for again inside the cooldown, and a retry loads it`() =
        catalogTest {
            val museum = FakeMuseum(failFrom = 0)
            val catalog = catalog(museum)

            catalog.show(0..9)
            settle()
            assertEquals("no network", catalog.failed[0])

            // showing it again inside the cooldown does not ask the museum
            museum.asked.clear()
            catalog.show(0..8)
            settle()
            assertTrue("the museum is not asked again inside the cooldown: ${museum.asked}", museum.asked.none { it == 0 })

            museum.failFrom = null
            catalog.retry(0)
            settle()
            assertEquals("skin0.wsz", catalog.skins[0]?.filename)
            assertTrue(catalog.failed.isEmpty())
        }

    @Test
    fun `a museum that hands out fewer than it counts is only as long as what it hands out`() =
        catalogTest {
            // the museum counts every skin it holds and lists fewer; positions past
            // what it lists would be tiles that never fill
            val museum = FakeMuseum(total = 10_000, listed = 3_456)
            val catalog = catalog(museum)

            catalog.show(0..9)
            settle()
            assertEquals("the catalog starts from the museum's count", 10_000, catalog.total)

            catalog.show(9_900..9_909)
            settle()
            settle()

            assertEquals(3_456, catalog.total)
        }

    @Test
    fun `nothing past the end of the museum is asked for`() =
        catalogTest {
            val museum = FakeMuseum(total = PAGE)
            val catalog = catalog(museum)
            catalog.show(0..1)
            settle()
            museum.asked.clear()

            catalog.show((PAGE * 3)..(PAGE * 3 + 1))
            settle()

            assertTrue("the museum is not asked past its end: ${museum.asked}", museum.asked.isEmpty())
        }

    @Test
    fun `a page on its way says so, and stops saying it when it lands`() =
        catalogTest {
            val museum = FakeMuseum()
            museum.hold = true
            val catalog = catalog(museum)

            catalog.show(0..9)
            settle()
            assertTrue("a page on its way reports loading", catalog.isLoading(0))

            museum.held.values.forEach { it.complete(Unit) }
            settle()

            assertTrue("a landed page stops reporting loading", !catalog.isLoading(0))
        }

    @Test
    fun `a drag across the museum asks only for where it started and where it stopped`() =
        catalogTest {
            // dragging the handle walks the visible range over page after page;
            // the catalog samples, so the pages in between are not fetched
            val museum = FakeMuseum(total = 100_000)
            val catalog = catalog(museum)
            catalog.show(0..9)
            settle()
            museum.asked.clear()

            for (row in 1_000..20_000 step 1_000) catalog.show(row..(row + 9))
            settle()

            assertEquals(listOf(1_000, 1_040, 20_000, 20_040), museum.asked)
        }

    @Test
    fun `a page that failed is tried again when the list comes back to it`() =
        catalogTest {
            // once the cooldown has passed, a failed page is worth asking for again
            val museum = FakeMuseum(failFrom = 0)
            val catalog = catalog(museum)
            catalog.show(0..9)
            settle()
            assertEquals("no network", catalog.failed[0])
            museum.asked.clear()
            museum.failFrom = null

            testScheduler.advanceTimeBy(COOLDOWN + 1)
            catalog.show(0..8)
            settle()

            assertEquals("skin0.wsz", catalog.skins[0]?.filename)
            assertTrue("the failure is cleared", catalog.failed.isEmpty())
        }

    @Test
    fun `a page that failed is tried again where the list already stands`() =
        catalogTest {
            // the failure lands after the list has stopped moving, so no scroll
            // asks again; the catalog retries on its own
            val museum = FakeMuseum(failFrom = 0, failAfterMs = SLOW_FAILURE)
            val catalog = catalog(museum)
            catalog.show(0..9)
            settle()
            testScheduler.advanceTimeBy(SLOW_FAILURE + 1)
            assertEquals("no network", catalog.failed[0])
            museum.asked.clear()
            museum.failFrom = null

            testScheduler.advanceTimeBy(COOLDOWN * 2)

            assertEquals("skin0.wsz", catalog.skins[0]?.filename)
            assertTrue("the failure is cleared", catalog.failed.isEmpty())
        }

    @Test
    fun `retries space out while the museum stays out of reach`() =
        catalogTest {
            val museum = FakeMuseum(failFrom = 0, failAfterMs = SLOW_FAILURE)
            val catalog = catalog(museum)
            catalog.show(0..9)
            settle()

            testScheduler.advanceTimeBy(MINUTE)

            val tries = museum.asked.count { it == 0 }
            assertTrue("a minute without network makes 2 to 8 tries at page 0: $tries", tries in 2..8)
        }

    @Test
    fun `a page left behind while it was still loading is dropped`() =
        catalogTest {
            val museum = FakeMuseum(total = 100_000)
            museum.hold = true
            val catalog = catalog(museum)

            catalog.show(0..9)
            settle()
            catalog.show(50_000..50_009)
            settle()

            // its fetch was cancelled, so the answer that arrives is not filed
            museum.held.values.forEach { it.complete(Unit) }
            settle()
            assertNull("a page out of view is not filed", catalog.skins[0])
        }

    @Test
    fun `a page just out of view is left to finish`() =
        catalogTest {
            // a fetch for a page within two pages of the view is not cancelled
            val museum = FakeMuseum(total = 100_000)
            museum.hold = true
            val catalog = catalog(museum)
            catalog.show(0..9)
            settle()

            catalog.show((PAGE + 1)..(PAGE + 9))
            settle()
            museum.held.values.forEach { it.complete(Unit) }
            settle()

            assertEquals("the page just out of view is filed", "skin0.wsz", catalog.skins[0]?.filename)
        }

    @Test
    fun `a list already on the phone waits less before it is read`() {
        // the wait spares round trips, and a list read from the phone has none
        assertTrue(
            "the local list waits less than the museum",
            SkinCatalog.LOCAL_SETTLE_MS < SkinCatalog.SETTLE_MS,
        )
    }

    @Test
    fun `a page dropped mid-drag is asked for again when the drag ends on it`() =
        catalogTest {
            // the handle sweeps away from a page and back to it: the fetch it
            // started was dropped on the way out, and coming back asks again
            val museum = FakeMuseum(total = 100_000)
            museum.hold = true
            val catalog = catalog(museum)

            catalog.show(0..9)
            settle()
            catalog.show(50_000..50_009)
            settle()
            museum.hold = false
            catalog.show(0..9)
            settle()
            settle()

            assertEquals("skin0.wsz", catalog.skins[0]?.filename)
        }

    @Test
    fun `a page dropped mid-flight can be asked for again`() =
        catalogTest {
            val museum = FakeMuseum(total = 100_000)
            museum.hold = true
            val catalog = catalog(museum)
            catalog.show(0..9)
            settle()
            catalog.show(50_000..50_009)
            settle()

            museum.hold = false
            catalog.show(0..9)
            settle()

            assertEquals("skin0.wsz", catalog.skins[0]?.filename)
            assertTrue("a dropped page is not marked failed", catalog.failed.isEmpty())
        }

    private companion object {
        const val PAGE = 40
        const val SETTLE = 200L
        const val COOLDOWN = 3_000L
        const val MINUTE = 60_000L
        const val SLOW_FAILURE = 1_000L
    }
}
