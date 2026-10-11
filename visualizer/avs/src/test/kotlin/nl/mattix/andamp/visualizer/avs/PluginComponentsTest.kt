// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The components that were plug-ins for the original AVS: what each does to a frame with a known answer. */
class PluginComponentsTest {
    private fun state(beat: Boolean = false) = AvsRenderState(AvsBuffers(SIZE, SIZE)).also { it.reset(beat) }

    private fun grey(level: Int) = pixelOf(level, level, level)

    private fun AvsComponentRenderer.on(
        pixel: Int,
        beat: Boolean = false,
    ): Int {
        val frame = AvsFrame(1, 1).also { it[0, 0] = pixel }
        val state = AvsRenderState(AvsBuffers(1, 1)).also { it.reset(beat) }
        render(frame, AvsAudioFrame(), state)
        return frame[0, 0]
    }

    @Test
    fun `multiplier scales every channel by a power of two and stops at full`() {
        assertEquals(pixelOf(80, 160, 255), MultiplierRenderer.read(int32(1)).on(pixelOf(10, 20, 40)))
        assertEquals(pixelOf(40, 80, 160), MultiplierRenderer.read(int32(2)).on(pixelOf(10, 20, 40)))
        assertEquals(pixelOf(20, 40, 80), MultiplierRenderer.read(int32(3)).on(pixelOf(10, 20, 40)))
        assertEquals(pixelOf(5, 10, 20), MultiplierRenderer.read(int32(4)).on(pixelOf(10, 20, 40)))
        assertEquals(pixelOf(1, 2, 5), MultiplierRenderer.read(int32(6)).on(pixelOf(10, 20, 40)))
        assertEquals(
            "a new Multiplier doubles",
            pixelOf(20, 40, 80),
            MultiplierRenderer.read(ByteArray(0)).on(pixelOf(10, 20, 40)),
        )
    }

    @Test
    fun `the two infinite multipliers push a pixel to black or white`() {
        val white = 0xFFFFFFFF.toInt()
        val root = MultiplierRenderer.read(int32(0))
        val square = MultiplierRenderer.read(int32(7))

        assertEquals("only white survives the root", AvsFrame.OPAQUE, root.on(pixelOf(255, 255, 254)))
        assertEquals(white, root.on(white))
        assertEquals("only black survives the square", white, square.on(pixelOf(0, 1, 0)))
        assertEquals(AvsFrame.OPAQUE, square.on(AvsFrame.OPAQUE))
    }

    @Test
    fun `normalise stretches the darkest level to black and the brightest to white`() {
        val frame = AvsFrame(3, 1)
        frame[0, 0] = grey(50)
        frame[1, 0] = grey(100)
        frame[2, 0] = grey(150)

        NormaliseRenderer.read(int32(1)).render(frame, AvsAudioFrame(), AvsRenderState(AvsBuffers(3, 1)))

        assertEquals(grey(0), frame[0, 0])
        assertEquals(grey(127), frame[1, 0])
        assertEquals(grey(255), frame[2, 0])
    }

    @Test
    fun `normalise leaves a frame that already spans the range, and is off unless told`() {
        val frame = AvsFrame(2, 1)
        frame[0, 0] = pixelOf(0, 40, 80)
        frame[1, 0] = pixelOf(255, 40, 80)
        val before = frame.pixels.copyOf()

        NormaliseRenderer.read(int32(1)).render(frame, AvsAudioFrame(), AvsRenderState(AvsBuffers(2, 1)))
        assertArrayEquals(before, frame.pixels)

        assertEquals(grey(50), NormaliseRenderer.read(ByteArray(0)).on(grey(50)))
    }

    @Test
    fun `add borders frames the picture by a percentage of each side`() {
        val frame = AvsFrame(SIZE, SIZE)
        val red = 0xFFFF0000.toInt()

        // 10 percent of 20 pixels is 2
        AddBordersRenderer.read(int32(1) + int32(0xFF0000) + int32(10)).render(frame, AvsAudioFrame(), state())

        assertEquals(red, frame[0, 0])
        assertEquals(red, frame[1, 10])
        assertEquals(red, frame[10, 1])
        assertEquals(red, frame[SIZE - 2, 10])
        assertEquals(red, frame[10, SIZE - 2])
        assertEquals("inside the border is untouched", AvsFrame.OPAQUE, frame[2, 2])
        assertEquals(AvsFrame.OPAQUE, frame[SIZE - 3, SIZE - 3])
    }

    @Test
    fun `chrome folds a channel around its middle`() {
        val chrome = MultiFilterRenderer.read(int32(1) + int32(0) + int32(0))

        assertEquals(pixelOf(0, 128, 254), chrome.on(pixelOf(0, 64, 127)))
        assertEquals(pixelOf(254, 126, 0), chrome.on(pixelOf(128, 192, 255)))
    }

    @Test
    fun `double chrome folds twice`() {
        val twice = MultiFilterRenderer.read(int32(1) + int32(1) + int32(0))

        // 64 -> 128 -> 254
        assertEquals(254, redOf(twice.on(grey(64))))
        // 127 -> 254 -> 2
        assertEquals(2, redOf(twice.on(grey(127))))
    }

    @Test
    fun `the border filter whitens what is not black, with the pixel above and the one to the left`() {
        val white = 0xFFFFFFFF.toInt()
        val frame = AvsFrame(SIZE, SIZE).also { it[5, 5] = pixelOf(1, 0, 0) }

        MultiFilterRenderer.read(int32(1) + int32(3) + int32(0)).render(frame, AvsAudioFrame(), state())

        assertEquals(white, frame[5, 5])
        assertEquals(white, frame[5, 4])
        assertEquals(white, frame[4, 5])
        assertEquals(3, frame.pixels.count { it == white })
    }

    @Test
    fun `a multifilter that toggles on the beat starts off and switches each beat`() {
        val toggling = MultiFilterRenderer.read(int32(1) + int32(0) + int32(1))

        assertEquals(grey(64), toggling.on(grey(64)))
        assertEquals("the first beat switches it on", grey(128), toggling.on(grey(64), beat = true))
        assertEquals("and it stays on", grey(128), toggling.on(grey(64)))
        assertEquals("the next switches it off", grey(64), toggling.on(grey(64), beat = true))
    }

    @Test
    fun `a video delay shows the frame from its delay ago`() {
        val delay = VideoDelayRenderer.read(int32(1) + int32(0) + int32(3))
        val shown = (1..6).map { delay.on(grey(it * 10)) }

        // three frames of black while the line fills, then the frames in order
        assertEquals(listOf(grey(0), grey(0), grey(0), grey(10), grey(20), grey(30)), shown)
    }

    @Test
    fun `a video delay of nothing, or one that is off, shows the frame as it is`() {
        assertEquals(grey(10), VideoDelayRenderer.read(int32(1) + int32(0) + int32(0)).on(grey(10)))
        assertEquals(grey(10), VideoDelayRenderer.read(int32(0) + int32(0) + int32(3)).on(grey(10)))
    }

    @Test
    fun `a video delay in beats is as long as the frames between two beats`() {
        val delay = VideoDelayRenderer.read(int32(1) + int32(1) + int32(1))
        val state = AvsRenderState(AvsBuffers(1, 1))
        val shown = mutableListOf<Int>()
        // beats four frames apart
        for (n in 1..16) {
            val frame = AvsFrame(1, 1).also { it[0, 0] = grey(n) }
            state.reset(beat = n % 4 == 0)
            delay.render(frame, AvsAudioFrame(), state)
            shown += redOf(frame[0, 0])
        }

        assertEquals("by the last frames the picture is four frames old", 16 - 4, shown.last())
    }

    @Test
    fun `one multi delay stores a frame and another shows it later`() {
        val lines = int32(0) + int32(2) + ByteArray(40)
        val store = MultiDelayRenderer.read(int32(1) + int32(0) + lines)
        val show = MultiDelayRenderer.read(int32(2) + int32(0))
        val state = AvsRenderState(AvsBuffers(1, 1))
        val shown = mutableListOf<Int>()
        for (n in 1..6) {
            state.reset()
            val frame = AvsFrame(1, 1).also { it[0, 0] = grey(n * 10) }
            store.render(frame, AvsAudioFrame(), state)
            show.render(frame, AvsAudioFrame(), state)
            shown += redOf(frame[0, 0])
        }

        assertEquals("line 0 is two frames long", listOf(0, 0, 10, 20, 30, 40), shown)
    }

    @Test
    fun `a multi delay line with no delay passes nothing on and changes nothing`() {
        val show = MultiDelayRenderer.read(int32(2) + int32(3) + ByteArray(48))

        assertEquals(grey(10), show.on(grey(10)))
    }

    @Test
    fun `a new color map is black to white on the red channel`() {
        val map = ColorMapRenderer.read(ByteArray(0))

        assertEquals(grey(200), map.on(pixelOf(200, 3, 9)))
    }

    @Test
    fun `a gradient ramps between its stops and is flat outside them`() {
        val baked = ColorMapRenderer.bake(listOf(100 to 0x000000, 200 to 0x0000FF))

        assertEquals(AvsFrame.OPAQUE, baked[0])
        assertEquals(AvsFrame.OPAQUE, baked[100])
        assertEquals(128, blueOf(baked[150]))
        assertEquals(0xFF0000FF.toInt(), baked[200])
        assertEquals(0xFF0000FF.toInt(), baked[255])
        assertTrue("the ramp only rises", (100 until 200).all { blueOf(baked[it]) <= blueOf(baked[it + 1]) })
    }

    @Test
    fun `a color map reads its key, its blend and its gradients`() {
        // keyed on the largest channel, replacing; map 0 runs black to red
        val body =
            int32(4) + int32(0) + int32(0) + byteArrayOf(0, 0, 0, 11) +
                mapHeader(enabled = 1, colours = 2) + ByteArray(7 * 60) +
                int32(0) + int32(0x000000) + int32(1) +
                int32(255) + int32(0xFF0000) + int32(2)

        val map = ColorMapRenderer.read(body)

        assertEquals(pixelOf(128, 0, 0), map.on(pixelOf(10, 128, 30)))
    }

    @Test
    fun `a color map that cycles on the beat fades to its next enabled gradient`() {
        // two maps: all black, and all white; sequential cycling at the fastest speed
        val body =
            int32(0) + int32(0) + int32(2) + byteArrayOf(0, 0, 0, 64) +
                mapHeader(1, 1) + mapHeader(1, 1) + ByteArray(6 * 60) +
                int32(0) + int32(0x000000) + int32(1) +
                int32(0) + int32(0xFFFFFF) + int32(2)
        val map = ColorMapRenderer.read(body)

        val steps = mutableListOf(redOf(map.on(grey(9), beat = true)))
        repeat(5) { steps += redOf(map.on(grey(9))) }

        assertEquals("the beat starts from the gradient it had", 0, steps.first())
        assertTrue("then it brightens: $steps", steps.zipWithNext().all { (a, b) -> b >= a })
        assertEquals("and arrives on the next one", 255, steps.last())
    }

    @Test
    fun `a new convolution filter copies the frame`() {
        val frame = patterned()
        val before = frame.pixels.copyOf()

        ConvolutionRenderer.read(ByteArray(0)).render(frame, AvsAudioFrame(), state())

        assertArrayEquals(before, frame.pixels)
    }

    @Test
    fun `a kernel of ones in a row averages across, with the edge pixel standing in past the edge`() {
        // three ones in the middle row, divided by three
        val kernel =
            IntArray(49).also {
                it[23] = 1
                it[24] = 1
                it[25] = 1
            }
        val blur = ConvolutionRenderer(ConvolutionRenderer.Settings(kernel = kernel.toList(), scale = 3))
        val frame = AvsFrame(SIZE, SIZE).also { it[5, 5] = grey(90) }

        blur.render(frame, AvsAudioFrame(), state())

        assertEquals(grey(30), frame[4, 5])
        assertEquals(grey(30), frame[5, 5])
        assertEquals(grey(30), frame[6, 5])
        assertEquals(AvsFrame.OPAQUE, frame[5, 4])

        val edge = AvsFrame(SIZE, SIZE).also { it[0, 5] = grey(90) }
        blur.render(edge, AvsAudioFrame(), state())
        assertEquals("the pixel past the left edge is the edge pixel again", grey(60), edge[0, 5])
    }

    @Test
    fun `a negative sum is black, its size with absolute, and wraps around with wrap`() {
        val negative = IntArray(49).also { it[24] = -1 }.toList()
        val clamped = ConvolutionRenderer(ConvolutionRenderer.Settings(kernel = negative))
        val absolute = ConvolutionRenderer(ConvolutionRenderer.Settings(kernel = negative, absolute = true))
        val wrapped = ConvolutionRenderer(ConvolutionRenderer.Settings(kernel = negative, wrap = true))

        assertEquals(grey(0), clamped.on(grey(10)))
        assertEquals(grey(10), absolute.on(grey(10)))
        assertEquals(grey(246), wrapped.on(grey(10)))
    }

    @Test
    fun `the bias is added before the division`() {
        val settings = ConvolutionRenderer.Settings(bias = 30, scale = 2)

        assertEquals(grey(20), ConvolutionRenderer(settings).on(grey(10)))
    }

    @Test
    fun `two passes add the kernel turned a quarter`() {
        // one tap to the left: the second pass reads the pixel above
        val kernel = IntArray(49).also { it[23] = 1 }.toList()
        val both = ConvolutionRenderer(ConvolutionRenderer.Settings(kernel = kernel, twoPass = true))
        val frame = AvsFrame(SIZE, SIZE)
        frame[4, 5] = grey(40)
        frame[5, 4] = grey(100)

        both.render(frame, AvsAudioFrame(), state())

        assertEquals(grey(140), frame[5, 5])
    }

    @Test
    fun `a convolution filter reads its flags, kernel, bias and scale in file order`() {
        val kernel = (0 until 49).fold(ByteArray(0)) { bytes, index -> bytes + int32(if (index == 24) 2 else 0) }
        val body = int32(1) + int32(0) + int32(0) + int32(0) + kernel + int32(10) + int32(4)

        // (2 * 20 + 10) / 4
        assertEquals(grey(12), ConvolutionRenderer.read(body).on(grey(20)))
    }

    @Test
    fun `a triangle's four pieces of code each end in a null`() {
        val body = "n=1\u0000t=t+1\u0000\u0000x1=0;y1=0\u0000".toByteArray(Charsets.ISO_8859_1)

        assertEquals(listOf("n=1", "t=t+1", "", "x1=0;y1=0"), TriangleRenderer.parse(body))
        assertEquals(
            "a body that ends early leaves the rest empty",
            listOf("n=1", "", "", ""),
            TriangleRenderer.parse("n=1".toByteArray()),
        )
    }

    @Test
    fun `the triangles of a preset share one depth record until it is cleared or the frame changes size`() {
        val depth = TriangleDepth()
        val first = depth.forFrame(4, 4, clear = false).also { it[3] = 7 }

        assertEquals(7, depth.forFrame(4, 4, clear = false)[3])
        assertTrue(first === depth.forFrame(4, 4, clear = false))
        assertEquals(0, depth.forFrame(4, 4, clear = true)[3])
        assertEquals(32, depth.forFrame(8, 4, clear = false).size)
    }

    @Test
    fun `a framerate limiter draws nothing`() {
        assertEquals(grey(10), FramerateLimiterRenderer().on(grey(10)))
    }

    private fun mapHeader(
        enabled: Int,
        colours: Int,
    ) = int32(enabled) + int32(colours) + int32(0) + ByteArray(48)

    private fun patterned() =
        AvsFrame(SIZE, SIZE).also { frame ->
            val random = Random(5)
            for (i in frame.pixels.indices) {
                frame.pixels[i] =
                    pixelOf(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            }
        }

    private fun int32(value: Int) =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private companion object {
        const val SIZE = 20
    }
}
