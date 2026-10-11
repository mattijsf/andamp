// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picture, Picture II, Texer and Texer II's body: a picture from the pack, put on the frame. */
class ImageComponentsTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** Left half red, right half blue. */
    private val halves = AvsImage(4, 2, IntArray(8) { if (it % 4 < 2) red else blue })

    private fun images(vararg named: Pair<String, AvsImage>) = AvsImages { name -> named.toMap()[name] }

    private fun state(beat: Boolean = false) = AvsRenderState(AvsBuffers(SIZE, SIZE)).also { it.reset(beat) }

    private fun grey(level: Int) = pixelOf(level, level, level)

    @Test
    fun `a place between pixels is a mix of the four around it`() {
        val image = AvsImage(2, 1, intArrayOf(grey(0), grey(200)))

        assertEquals(grey(0), image.between(0.0, 0.0))
        assertEquals(grey(100), image.between(0.5, 0.0))
        assertEquals("past the edge is the edge pixel", grey(200), image.between(5.0, 3.0))
    }

    @Test
    fun `a picture is stretched over the whole frame`() {
        val picture = PictureRenderer(PictureRenderer.Settings(average = false, name = "a.bmp"))
        val frame = AvsFrame(SIZE, SIZE)

        assertNull(picture.attach(images("a.bmp" to halves)))
        picture.render(frame, AvsAudioFrame(), state())

        assertEquals(red, frame[0, 0])
        assertEquals(red, frame[SIZE / 2 - 1, SIZE - 1])
        assertEquals(blue, frame[SIZE / 2, 0])
        assertEquals(blue, frame[SIZE - 1, SIZE - 1])
    }

    @Test
    fun `a picture kept in shape is centered on black`() {
        // 4 by 2 into 16 by 16, fitting the width: 8 rows high, from row 4
        val picture = PictureRenderer(PictureRenderer.Settings(average = false, name = "a.bmp", keepRatio = true))
        val frame = AvsFrame(SIZE, SIZE).also { it.pixels.fill(white) }

        picture.attach(images("a.bmp" to halves))
        picture.render(frame, AvsAudioFrame(), state())

        assertEquals(AvsFrame.OPAQUE, frame[0, 3])
        assertEquals(red, frame[0, 4])
        assertEquals(blue, frame[SIZE - 1, 11])
        assertEquals(AvsFrame.OPAQUE, frame[0, 12])
    }

    @Test
    fun `a new picture mixes half and half, and adaptive adds on the beat`() {
        val flat = AvsImage(1, 1, intArrayOf(grey(100)))
        val mixing = PictureRenderer.read(int32(1) + int32(0) + int32(1) + int32(0) + int32(0) + "a.bmp\u0000".toByteArray())
        val adaptive = PictureRenderer.read(int32(1) + int32(0) + int32(0) + int32(1) + int32(2) + "a.bmp\u0000".toByteArray())
        mixing.attach(images("a.bmp" to flat))
        adaptive.attach(images("a.bmp" to flat))

        fun PictureRenderer.on(beat: Boolean): Int {
            val frame = AvsFrame(SIZE, SIZE).also { it.pixels.fill(grey(60)) }
            render(frame, AvsAudioFrame(), state(beat))
            return redOf(frame[3, 3])
        }

        assertEquals(80, mixing.on(beat = false))
        assertEquals(80, adaptive.on(beat = false))
        assertEquals(160, adaptive.on(beat = true))
        // a count of two, started on the beat's own frame: one more frame of adding
        assertEquals(160, adaptive.on(beat = false))
        assertEquals(80, adaptive.on(beat = false))
    }

    @Test
    fun `a picture that is not in the pack is named, and draws nothing`() {
        val picture = PictureRenderer(PictureRenderer.Settings(name = "gone.bmp"))
        val frame = AvsFrame(SIZE, SIZE).also { it.pixels.fill(grey(60)) }

        assertEquals("gone.bmp", picture.attach(AvsImages.NONE))
        picture.render(frame, AvsAudioFrame(), state())

        assertEquals(grey(60), frame[0, 0])
    }

    @Test
    fun `a picture with no name is not missing anything`() {
        assertNull(PictureRenderer.read(ByteArray(0)).attach(AvsImages.NONE))
    }

    @Test
    fun `picture two blends one way on a beat and another off it`() {
        // replace off the beat, additive on it
        val body = name260("a.bmp") + int32(0) + int32(1) + int32(0) + int32(0) + int32(0) + int32(0)
        val picture = Picture2Renderer.read(body)
        picture.attach(images("a.bmp" to AvsImage(1, 1, intArrayOf(grey(100)))))

        fun on(beat: Boolean): Int {
            val frame = AvsFrame(SIZE, SIZE).also { it.pixels.fill(grey(60)) }
            picture.render(frame, AvsAudioFrame(), state(beat))
            return redOf(frame[3, 3])
        }

        assertEquals(100, on(beat = false))
        assertEquals(160, on(beat = true))
    }

    @Test
    fun `picture two's smooth stretch blends across the picture's pixels`() {
        val body = name260("a.bmp") + int32(0) + int32(0) + int32(1) + int32(0) + int32(0) + int32(0)
        val smooth = Picture2Renderer.read(body)
        smooth.attach(images("a.bmp" to halves))
        val frame = AvsFrame(SIZE, SIZE)

        smooth.render(frame, AvsAudioFrame(), state())

        assertEquals(red, frame[0, 0])
        assertEquals(blue, frame[SIZE - 1, 0])
        val seam = frame[SIZE / 2, 0]
        assertTrue("the seam is a mix of the two halves", redOf(seam) in 1..254 && blueOf(seam) in 1..254)
    }

    @Test
    fun `texer stamps its picture on each lit pixel, tinted when asked`() {
        val dot = AvsImage(3, 3, IntArray(9) { white })
        val body = ByteArray(16) + name260("dot.bmp") + int32(1 or 8) + int32(10)
        val texer = TexerRenderer.read(body)
        texer.attach(images("dot.bmp" to dot))
        val frame = AvsFrame(SIZE, SIZE).also { it[8, 8] = red }

        texer.render(frame, AvsAudioFrame(), state())

        assertEquals(red, frame[7, 7])
        assertEquals(red, frame[9, 9])
        assertEquals("three by three and no more", AvsFrame.OPAQUE, frame[10, 8])
        assertEquals(9, frame.pixels.count { it != AvsFrame.OPAQUE })
    }

    @Test
    fun `texer stops after the stamps it was given`() {
        val dot = AvsImage(1, 1, intArrayOf(white))
        val texer = TexerRenderer.read(ByteArray(16) + name260("dot.bmp") + int32(1 or 4) + int32(2))
        texer.attach(images("dot.bmp" to dot))
        val frame = AvsFrame(SIZE, SIZE)
        for (x in 0 until 5) frame[x, 0] = red

        texer.render(frame, AvsAudioFrame(), state())

        assertEquals("the output starts black, and two stamps are drawn", 2, frame.pixels.count { it != AvsFrame.OPAQUE })
        assertEquals("untinted", white, frame[0, 0])
    }

    @Test
    fun `texer two's body is a version, a file name, three flags and four pieces of code`() {
        val body =
            int32(1) + name260("star.png") + int32(1) + int32(1) + int32(0) + int32(0) +
                text("n=4") + text("t=t+1") + text("") + text("x=i;y=0")

        val parsed = Texer2Renderer.parse(body)

        assertEquals("star.png", parsed.settings.name)
        assertTrue(parsed.settings.resize)
        assertTrue(parsed.settings.wrap)
        assertFalse(parsed.settings.colorize)
        assertFalse(parsed.settings.wrapsOnce)
        assertEquals(listOf("n=4", "t=t+1", "", "x=i;y=0"), listOf(parsed.init, parsed.perFrame, parsed.onBeat, parsed.perStamp))
    }

    @Test
    fun `a texer two from AVS 2_81d wraps a stamp once`() {
        assertTrue(Texer2Renderer.parse(int32(0) + name260("")).settings.wrapsOnce)
    }

    @Test
    fun `texer two's own stamp is a soft white dot, brightest in the middle`() {
        val dot = Texer2Renderer.DEFAULT_IMAGE

        assertEquals(21, dot.width)
        assertEquals(white, dot.at(10, 10))
        assertEquals(AvsFrame.OPAQUE, dot.at(0, 0))
        assertTrue(redOf(dot.at(10, 3)) in 1..254)
    }

    @Test
    fun `the engine hands a preset its pictures and names the ones it could not find`() {
        val there = AvsComponent.Ape("Picture II", name260("here.bmp"))
        val gone = AvsComponent.Ape("Picture II", name260("gone.bmp"))
        val engine = AvsEngine(SIZE, SIZE)
        engine.images = images("here.bmp" to halves)

        engine.load(AvsPreset(clearEveryFrame = false, components = listOf(there, gone)))

        assertEquals(listOf("Picture II (gone.bmp)"), engine.unimplemented)
        assertEquals("the one that is there is drawn", red, engine.render(AvsAudioFrame())[0, 0])
    }

    private fun name260(name: String) = name.toByteArray(Charsets.ISO_8859_1).copyOf(260)

    private fun text(code: String): ByteArray {
        val bytes = code.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        return int32(bytes.size) + bytes
    }

    private fun int32(value: Int) =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private companion object {
        const val SIZE = 16
    }
}
