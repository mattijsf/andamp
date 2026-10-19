// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import nl.mattix.andamp.visualizer.projectm.BlankWatch.Companion.BLACK_MS
import nl.mattix.andamp.visualizer.projectm.BlankWatch.Companion.EVERY_MS
import nl.mattix.andamp.visualizer.projectm.BlankWatch.Companion.SETTLE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlankWatchTest {
    /** Looks at every frame the watch wants from [from] to [until], all [black], with music [audible]; the last answer. */
    private fun BlankWatch.run(
        from: Long,
        until: Long,
        black: Boolean = true,
        audible: Boolean = true,
    ): Boolean? {
        var answer: Boolean? = null
        var now = from
        while (now <= until) {
            if (wants(now)) answer = sampled(black, audible, now) ?: answer
            now += FRAME_MS
        }
        return answer
    }

    @Test
    fun `a preset is left alone while it blends in`() {
        val watch = BlankWatch().apply { start("/a.milk", 0) }

        assertFalse(watch.wants(SETTLE_MS - FRAME_MS))
        assertTrue(watch.wants(SETTLE_MS))
    }

    @Test
    fun `black while music plays marks the preset`() {
        val watch = BlankWatch().apply { start("/a.milk", 0) }

        assertNull(watch.run(0, SETTLE_MS + BLACK_MS - EVERY_MS - FRAME_MS))
        assertEquals(false, watch.run(SETTLE_MS + BLACK_MS - EVERY_MS, SETTLE_MS + BLACK_MS + EVERY_MS))
        assertFalse("a marked preset is not looked at again", watch.wants(SETTLE_MS + BLACK_MS * 3))
    }

    @Test
    fun `black in silence proves nothing`() {
        val watch = BlankWatch().apply { start("/a.milk", 0) }

        assertNull(watch.run(0, BLACK_MS * 5, audible = false))
        assertTrue(watch.wants(BLACK_MS * 5 + EVERY_MS))
    }

    @Test
    fun `a preset that draws is watched on, and one that fades to black is marked`() {
        val watch = BlankWatch().apply { start("/a.milk", 0) }

        assertEquals(true, watch.run(0, SETTLE_MS + EVERY_MS, black = false))
        assertTrue("drawing does not end the watch", watch.wants(SETTLE_MS + EVERY_MS * 3))
        val fadedAt = SETTLE_MS + EVERY_MS * 3
        assertEquals(false, watch.run(fadedAt, fadedAt + BLACK_MS + EVERY_MS))
    }

    @Test
    fun `anything drawn starts the count over`() {
        val watch = BlankWatch().apply { start("/a.milk", 0) }
        watch.run(0, SETTLE_MS + BLACK_MS - EVERY_MS * 2)

        val drewAt = SETTLE_MS + BLACK_MS - EVERY_MS
        assertEquals(true, watch.run(drewAt, drewAt, black = false))
        assertNull(watch.run(drewAt + 1, drewAt + BLACK_MS - EVERY_MS * 2))
    }

    @Test
    fun `a new preset is watched afresh, and the idle preset not at all`() {
        val watch = BlankWatch().apply { start("/a.milk", 0) }
        watch.run(0, SETTLE_MS + BLACK_MS / 2)

        watch.start("/b.milk", 10_000)
        assertNull(watch.run(10_000, 10_000 + SETTLE_MS + BLACK_MS / 2))
        assertEquals("/b.milk", watch.watching)

        watch.start(null, 20_000)
        assertFalse(watch.wants(30_000))
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
