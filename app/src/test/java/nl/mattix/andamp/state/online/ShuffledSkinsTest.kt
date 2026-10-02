// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A shuffle that needs no list in hand: [ShuffledSkins] permutes pages by arithmetic. Every skin
 * still appears once, and a page still costs one request.
 */
class ShuffledSkinsTest {
    private val museum = 1_000
    private val pageSize = 10

    /** A museum of [holds] skins, each named after where it really lives. */
    private class Plain(
        val holds: Int,
    ) : SkinsSource {
        var asked = 0

        override suspend fun page(
            offset: Int,
            count: Int,
        ): SkinsPage {
            asked++
            val there =
                (offset until minOf(offset + count, holds)).map { at ->
                    OnlineSkin("%032x".format(at), "skin$at.wsz", "s", "d", null, false)
                }
            return SkinsPage(holds, offset, there)
        }
    }

    private val plain = Plain(museum)

    private fun shuffled(seed: Int = 1) = ShuffledSkins(plain, seed, pageSize) { museum }

    /** Every skin the shuffled list hands out, in the order it hands them out. */
    private suspend fun wholeList(of: ShuffledSkins) =
        (0 until museum / pageSize).flatMap { of.page(it * pageSize, pageSize).items }

    /** Where a dealt skin really lives, read back out of its name. */
    private fun OnlineSkin.livesAt() = filename.removePrefix("skin").removeSuffix(".wsz").toInt()

    @Test
    fun `every skin is dealt, and none is dealt twice`() =
        runTest {
            val dealt = wholeList(shuffled()).map { it.filename }

            assertEquals("every skin is dealt once", museum, dealt.toSet().size)
            assertEquals(museum, dealt.size)
        }

    @Test
    fun `the dealt order differs from the museum's own`() =
        runTest {
            val dealt = wholeList(shuffled()).map { it.filename }

            assertNotEquals((0 until museum).map { "skin$it.wsz" }, dealt)
        }

    @Test
    fun `consecutive pages come from far apart in the museum`() =
        runTest {
            // a page is a run of the museum's own order, reordered within itself, so
            // pages that follow one another are dealt from far apart
            val of = shuffled()
            val jumps =
                (0 until 20).map { at ->
                    val here =
                        of
                            .page(at * pageSize, pageSize)
                            .items
                            .first()
                            .livesAt()
                    val next =
                        of
                            .page((at + 1) * pageSize, pageSize)
                            .items
                            .first()
                            .livesAt()
                    abs(here - next)
                }

            assertTrue("consecutive pages land over a tenth of the museum apart: ${jumps.min()}", jumps.min() > museum / 10)
        }

    @Test
    fun `the same seed deals the same hand, a different seed a different one`() =
        runTest {
            assertEquals(wholeList(shuffled(seed = 7)), wholeList(shuffled(seed = 7)))
            assertNotEquals(wholeList(shuffled(seed = 7)), wholeList(shuffled(seed = 8)))
        }

    @Test
    fun `a page still costs one request`() =
        runTest {
            // it deals pages, so a shuffled page is one request to the source
            val before = plain.asked

            shuffled().page(0, pageSize)

            assertEquals(1, plain.asked - before)
        }

    @Test
    fun `a page comes back numbered where it was asked for`() =
        runTest {
            val page = shuffled().page(30, pageSize)

            assertEquals("the page carries the offset it was asked for", 30, page.offset)
            assertEquals(museum, page.total)
        }

    @Test
    fun `the short page at the end stays at the end`() =
        runTest {
            // dealt into the middle it would leave a hole, and the catalog reads a
            // short page as the end of the list
            val odd = 95
            val of = ShuffledSkins(Plain(odd), 1, pageSize) { odd }

            assertEquals(5, of.page(90, pageSize).items.size)
            for (page in 0 until 9) {
                assertEquals("page $page is full", pageSize, of.page(page * pageSize, pageSize).items.size)
            }
        }

    @Test
    fun `a list too short to shuffle is left alone`() =
        runTest {
            val tiny = ShuffledSkins(plain, 1, pageSize) { 5 }

            assertEquals(
                "skin0.wsz",
                tiny
                    .page(0, pageSize)
                    .items
                    .first()
                    .filename,
            )
        }

    @Test
    fun `a length nobody knows yet is left alone`() =
        runTest {
            val unknown = ShuffledSkins(plain, 1, pageSize) { 0 }

            assertEquals(
                "skin0.wsz",
                unknown
                    .page(0, pageSize)
                    .items
                    .first()
                    .filename,
            )
        }

    @Test
    fun `asking for anything but a whole page reaches the real list`() =
        runTest {
            // the catalog's search for the end probes single positions, and a
            // probe reads the list in its real order
            val probe = shuffled().page(500, 1)

            assertEquals("skin500.wsz", probe.items.single().filename)
        }
}
