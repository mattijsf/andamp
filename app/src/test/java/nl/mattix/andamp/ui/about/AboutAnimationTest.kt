// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The about box's warp.
 *
 * A feedback effect fed by its own output can fade to a flat color or
 * saturate. These check that it moves, that it stays about as bright as it
 * started, and that a turn of the cycle ends where it began.
 */
class AboutAnimationTest {
    private val width = 64
    private val height = 40

    /** Something with edges in it: a flat field would smear into itself invisibly. */
    private fun picture(): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if ((x / 8 + y / 8) % 2 == 0) 0xFFFF5FA2.toInt() else 0xFF17130F.toInt()
        }

    private fun animation() = AboutAnimation(width, height, picture())

    /** In frames of [FRAME] milliseconds. */
    private fun AboutAnimation.run(millis: Long) {
        repeat((millis / FRAME).toInt()) { advance(FRAME) }
    }

    @Test
    fun `at rest it is the picture itself`() {
        val animation = animation()

        animation.run(millis = 1_000)

        assertTrue("the picture is unchanged at rest", animation.pixels.contentEquals(picture()))
    }

    @Test
    fun `the warp moves the picture`() {
        val animation = animation()

        animation.run(millis = 3_000) // into the burst

        val moved = animation.pixels.indices.count { animation.pixels[it] != picture()[it] }
        assertTrue("the warp moves over a tenth of the picture: $moved pixels differ", moved > animation.pixels.size / 10)
    }

    @Test
    fun `it keeps moving during the burst`() {
        val animation = animation()
        animation.run(millis = 2_600)

        val before = animation.pixels.copyOf()
        animation.run(millis = 200)

        val changed = before.indices.count { before[it] != animation.pixels[it] }
        assertTrue("the warp keeps moving: $changed pixels changed in 200ms", changed > before.size / 50)
    }

    @Test
    fun `the feedback neither fades out nor burns in`() {
        val animation = animation()
        val started = brightness(picture())

        // the whole burst, frame by frame, which is where a runaway would show
        repeat((4_500 / FRAME).toInt()) {
            animation.advance(FRAME)
            val now = brightness(animation.pixels)
            assertTrue("the picture keeps over half its brightness: $now against $started", now > started * 0.5)
            assertTrue("the picture stays under 1.6 times its brightness: $now against $started", now < started * 1.6)
        }
    }

    @Test
    fun `a turn of the cycle comes back to the picture`() {
        val animation = animation()

        animation.run(millis = 5_400) // past the settle, into the next rest

        assertTrue("the picture returns after a turn", animation.pixels.contentEquals(picture()))
    }

    @Test
    fun `the cycle counts the milliseconds it was given`() {
        val animation = animation()

        animation.run(millis = 1_200)

        // 1200 ms is 75 frames of 16 ms; the cycle counts milliseconds, not frames
        assertEquals(1_200 - 1_200 % FRAME, animation.cycleMillis)
    }

    @Test
    fun `every resting frame is one of the two pictures`() {
        val second = IntArray(width * height) { 0xFF00E63E.toInt() }
        val animation = AboutAnimation(width, height, listOf(picture(), second))

        // every resting frame, for two full turns, is one picture or the other
        // and never a blend
        repeat((11_000 / FRAME).toInt()) {
            animation.advance(FRAME)
            if (animation.cycleMillis < REST) {
                val resting = animation.pixels
                assertTrue(
                    "a resting frame is one of the two pictures",
                    resting.contentEquals(picture()) || resting.contentEquals(second),
                )
            }
        }
    }

    @Test
    fun `each turn comes to rest on the other picture`() {
        val second = IntArray(width * height) { 0xFF00E63E.toInt() }
        val animation = AboutAnimation(width, height, listOf(picture(), second))

        animation.run(millis = 1_000)
        assertTrue("the cycle starts on the first picture", animation.pixels.contentEquals(picture()))

        animation.run(millis = CYCLE)
        assertTrue("the first turn rests on the second picture", animation.pixels.contentEquals(second))

        animation.run(millis = CYCLE)
        assertTrue("the second turn rests on the first picture", animation.pixels.contentEquals(picture()))
    }

    @Test
    fun `an empty surface or a mismatched source is refused`() {
        val tooSmall = runCatching { AboutAnimation(0, 4, IntArray(0)) }
        val mismatched = runCatching { AboutAnimation(4, 4, IntArray(3)) }

        assertTrue("an empty surface is refused", tooSmall.isFailure)
        assertTrue("a source of the wrong size is refused", mismatched.isFailure)
    }

    /** The mean of the color channels, as a rough brightness. */
    private fun brightness(pixels: IntArray): Double =
        pixels.sumOf { pixel ->
            ((pixel ushr 16 and 0xFF) + (pixel ushr 8 and 0xFF) + (pixel and 0xFF)).toLong()
        } / (pixels.size * 3.0)

    private companion object {
        const val FRAME = 16L

        /** The phase lengths, which are private in `AboutAnimation`. */
        const val REST = 1_800L
        const val CYCLE = REST + 2_600L + 650L
    }
}
