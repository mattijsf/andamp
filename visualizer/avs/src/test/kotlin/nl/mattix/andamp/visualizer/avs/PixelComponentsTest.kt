// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The ten pixel loops of PixelComponents.kt, and [AvsDraw]'s line.
 *
 * Each is tested for what it does to pixels; none needs the evaluator. Where a
 * value looks arbitrary it is the arithmetic of the vis_avs source.
 */
class PixelComponentsTest {
    @Test
    fun `fast brightness doubles, halves, or leaves alone`() {
        assertEquals(0xFF808080.toInt(), lit(0x40, 0x40, 0x40).let { fast(0).run(it) })
        assertEquals(0xFF202020.toInt(), lit(0x40, 0x40, 0x40).let { fast(1).run(it) })
        assertEquals(0xFF404040.toInt(), lit(0x40, 0x40, 0x40).let { fast(2).run(it) })
    }

    @Test
    fun `doubling saturates`() {
        assertEquals(0xFFFFFFFF.toInt(), lit(0xC0, 0xC0, 0xC0).let { fast(0).run(it) })
    }

    /** The positive half of the dial is sixteen times as steep: +4096 is x17, not x2. */
    @Test
    fun `brightness lifts each channel by its own gain`() {
        val brighter = BrightnessRenderer(true, 4096, 0, -4096, AvsBlendMode.REPLACE)

        // +4096 is x17 (saturating), 0 leaves alone, -4096 takes it to nothing
        assertEquals(0xFFFF4000.toInt(), brighter.run(lit(0x40, 0x40, 0x40)))
    }

    @Test
    fun `the two halves of the brightness dial have different slopes`() {
        val skewed = BrightnessRenderer(true, 2048, -2048, 0, AvsBlendMode.REPLACE)

        // +2048 is 1 + 16*0.5 = x9; -2048 is 1 - 0.5 = x0.5
        assertEquals(0xFF902033.toInt(), skewed.run(lit(0x10, 0x40, 0x33)))
    }

    @Test
    fun `a brightness that is switched off does nothing`() {
        val off = BrightnessRenderer(false, 4096, 4096, 4096, AvsBlendMode.REPLACE)

        assertEquals(0xFF404040.toInt(), off.run(lit(0x40, 0x40, 0x40)))
    }

    /** The blend is two int32s: the additive flag, overridden by the 50/50 flag. */
    @Test
    fun `a fifty-fifty brightness reads its gains from the right fields`() {
        val body =
            int32(1) + // enabled
                int32(0) + int32(1) + // additive off, fifty-fifty on
                int32(4096) + int32(0) + int32(-4096) + // red to full, green alone, blue to nothing
                int32(1) + // separate: each channel keeps its own gain
                int32(0) + int32(0) + int32(0) // exclude color, flag, distance
        val brightness = BrightnessRenderer.read(body)!!

        // (0xFF, 0x40, 0x00) mixed half and half with the original (0x40, 0x40, 0x40)
        assertEquals(0xFF9F4020.toInt(), brightness.run(lit(0x40, 0x40, 0x40)))
    }

    /** e_brightness.cpp's on-load sync: linked sliders mean the red gain rules all three. */
    @Test
    fun `linked brightness sliders load the red gain into every channel`() {
        val body =
            int32(1) + int32(0) + int32(0) +
                int32(-2048) + int32(0) + int32(0) + // stored green and blue disagree with red
                int32(0) + // separate off: red wins
                int32(0) + int32(0) + int32(0)
        val brightness = BrightnessRenderer.read(body)!!

        assertEquals(0xFF202020.toInt(), brightness.run(lit(0x40, 0x40, 0x40)))
    }

    /** The exclude distance is an axis-aligned cube (every channel within it), not a summed distance. */
    @Test
    fun `brightness excludes a pixel within the distance on every channel`() {
        val brightness =
            BrightnessRenderer(
                true,
                4096,
                4096,
                4096,
                AvsBlendMode.REPLACE,
                exclude = true,
                excludeColour = lit(0x40, 0x40, 0x40),
                excludeDistance = 0x10,
            )

        // each channel is the full distance away (the sum, 48, is past it)
        assertEquals(0xFF505050.toInt(), brightness.run(lit(0x50, 0x50, 0x50)))
        // one channel past the distance and the pixel is processed
        assertEquals(0xFFFFFFFF.toInt(), brightness.run(lit(0x40, 0x40, 0x5F)))
    }

    /**
     * The stored order is (2nd, max, 3rd/gray): the second value drives the
     * brightest channel, and the other two follow cyclically, not by rank.
     */
    @Test
    fun `colorfade sends the max fader to the brightest channel and the second around the cycle`() {
        val body = int32(1) + int32(1) + int32(2) + int32(4) + int32(0) + int32(0) + int32(0)
        val fade = ColorfadeRenderer.read(body)!!

        assertEquals("green brightest: G+max, R+2nd, B+3rd", 0xFF114214.toInt(), fade.run(lit(0x10, 0x40, 0x10)))
        assertEquals("red brightest: R+max, B+2nd, G+3rd", 0xFF421411.toInt(), fade.run(lit(0x40, 0x10, 0x10)))
        assertEquals("blue brightest: B+max, G+2nd, R+3rd", 0xFF141142.toInt(), fade.run(lit(0x10, 0x10, 0x40)))
    }

    /** Any tie for brightest sends all three channels to the 3rd/gray fader. */
    @Test
    fun `colorfade ties go to the gray fader`() {
        val body = int32(1) + int32(8) + int32(8) + int32(-8) + int32(0) + int32(0) + int32(0)
        val fade = ColorfadeRenderer.read(body)!!

        assertEquals("grayscale takes the 3rd/gray fader on every channel", 0xFF383838.toInt(), fade.run(lit(0x40, 0x40, 0x40)))
        assertEquals("black stays black", 0xFF000000.toInt(), fade.run(lit(0, 0, 0)))
    }

    /** On-beat faders decay one step per frame back toward the normal set. */
    @Test
    fun `a colorfade beat fades back out one step at a time`() {
        val body =
            int32(0x05) + // enabled + on beat
                int32(0) + int32(0) + int32(0) + // normal set: nothing
                int32(6) + int32(0) + int32(0) // beat set: 2nd fader 6
        val fade = ColorfadeRenderer.read(body)!!

        // green brightest, so the 2nd fader lands on red
        assertEquals("the beat frame takes the beat set", 0xFF164010.toInt(), fade.run(lit(0x10, 0x40, 0x10), beat = true))
        assertEquals("one frame later the fader steps back by one", 0xFF154010.toInt(), fade.run(lit(0x10, 0x40, 0x10)))
        assertEquals(0xFF144010.toInt(), fade.run(lit(0x10, 0x40, 0x10)))
    }

    /** Legacy files (version byte 0) walk max toward the 3rd target and vice versa: the v2.81d slot swap. */
    @Test
    fun `a legacy colorfade swaps its max and gray faders between beats`() {
        val body =
            int32(0x05) + // enabled + on beat, version byte 0
                int32(0) + int32(8) + int32(0) + // normal set: max fader 8
                int32(0) + int32(0) + int32(0)
        val fade = ColorfadeRenderer.read(body)!!

        repeat(7) { fade.run(lit(0x10, 0x40, 0x10)) }

        // after eight walk steps the max fader has drained into the 3rd/gray slot
        assertEquals(0xFF104018.toInt(), fade.run(lit(0x10, 0x40, 0x10)))
    }

    @Test
    fun `a version-one colorfade keeps its faders where they were stored`() {
        val body =
            int32(0x05 or (1 shl 24)) + // version byte 1 disables the swap
                int32(0) + int32(8) + int32(0) +
                int32(0) + int32(0) + int32(0)
        val fade = ColorfadeRenderer.read(body)!!

        repeat(7) { fade.run(lit(0x10, 0x40, 0x10)) }

        assertEquals("the max fader stays on the brightest channel", 0xFF104810.toInt(), fade.run(lit(0x10, 0x40, 0x10)))
    }

    /** Flag 0x02: a beat rolls the faders instead of taking the stored beat set. */
    @Test
    fun `a random colorfade beat rolls its faders`() {
        val fade =
            ColorfadeRenderer(
                enabled = true,
                fader2nd = 0,
                faderMax = 0,
                fader3rdGray = 0,
                onBeat = true,
                onBeatRandom = true,
                beat2nd = 99,
                beatMax = 99,
                beat3rdGray = 99, // would wash white if the stored beat set were used
                random = ScriptedRandom(listOf(20, 50, 10)),
            )

        // 2nd = 20-6 = 14, max = 50-32 = 18 (outside the dead zone), 3rd = 10-6 = 4
        assertEquals(0xFF1E5214.toInt(), fade.run(lit(0x10, 0x40, 0x10), beat = true))
    }

    @Test
    fun `a random colorfade pushes a weak max fader out of the dead zone`() {
        val fade =
            ColorfadeRenderer(
                enabled = true,
                fader2nd = 0,
                faderMax = 0,
                fader3rdGray = 0,
                onBeat = true,
                onBeatRandom = true,
                random = ScriptedRandom(listOf(0, 40, 0)),
            )

        // max = 40-32 = 8, inside 0..15, forced to 32; 2nd and 3rd are -6
        assertEquals(0xFF0A600A.toInt(), fade.run(lit(0x10, 0x40, 0x10), beat = true))
    }

    @Test
    fun `color clip replaces what is near a color`() {
        val clip = ColorClipRenderer(AvsClipMode.NEAR, 0xFF000000.toInt(), 0xFFFF0000.toInt(), level = 16)

        assertEquals("a dark pixel is near black", 0xFFFF0000.toInt(), clip.run(lit(4, 4, 4)))
        assertEquals("a bright pixel is not near black", 0xFFC0C0C0.toInt(), clip.run(lit(0xC0, 0xC0, 0xC0)))
    }

    @Test
    fun `color clip below and above look the other way`() {
        val below = ColorClipRenderer(AvsClipMode.BELOW, 0xFF808080.toInt(), 0xFFFF0000.toInt(), 0)
        val above = ColorClipRenderer(AvsClipMode.ABOVE, 0xFF808080.toInt(), 0xFFFF0000.toInt(), 0)

        assertEquals(0xFFFF0000.toInt(), below.run(lit(0x10, 0x10, 0x10)))
        assertEquals(0xFF101010.toInt(), above.run(lit(0x10, 0x10, 0x10)))
    }

    @Test
    fun `a clip that is off leaves everything`() {
        assertEquals(
            0xFF000000.toInt(),
            ColorClipRenderer(AvsClipMode.OFF, 0xFF000000.toInt(), 0xFFFF0000.toInt(), 64).run(lit(0, 0, 0)),
        )
    }

    /** e_colorclip.cpp's default case: an unknown stored mode is NEAR with the effect on, not off. */
    @Test
    fun `an out-of-range clip mode clips near`() {
        val body = int32(7) + int32(0x000000) + int32(0xFF0000) + int32(16)
        val clip = ColorClipRenderer.read(body)!!

        assertEquals(0xFFFF0000.toInt(), clip.run(lit(4, 4, 4)))
    }

    @Test
    fun `unique tone paints everything in one color by how bright it was`() {
        val tone = UniqueToneRenderer(true, 0xFF00FF00.toInt(), invert = false, blend = AvsBlendMode.REPLACE)

        assertEquals("a bright pixel is the full tone", 0xFF00FF00.toInt(), tone.run(lit(0xFF, 0, 0)))
        assertEquals("a dark pixel gets none of the tone", 0xFF000000.toInt(), tone.run(lit(0, 0, 0)))
    }

    @Test
    fun `inverting a unique tone turns it inside out`() {
        val tone = UniqueToneRenderer(true, 0xFF00FF00.toInt(), invert = true, blend = AvsBlendMode.REPLACE)

        assertEquals(0xFF00FF00.toInt(), tone.run(lit(0, 0, 0)))
    }

    /** The invert flag sits past the Map8 blend pair. */
    @Test
    fun `a fifty-fifty unique tone is not inverted`() {
        val plain = int32(1) + int32(0x00FF00) + int32(0) + int32(1) + int32(0)
        val flipped = int32(1) + int32(0x00FF00) + int32(0) + int32(1) + int32(1)

        // a bright pixel keeps half the tone; inverted, the same pixel gets none of it
        assertEquals(0xFF7F7F00.toInt(), UniqueToneRenderer.read(plain)!!.run(lit(0xFF, 0, 0)))
        assertEquals(0xFF7F0000.toInt(), UniqueToneRenderer.read(flipped)!!.run(lit(0xFF, 0, 0)))
    }

    /** e_uniquetone.cpp builds its tables in float; at (147/255)*85 the float lands one below integer division. */
    @Test
    fun `unique tone's table uses the original's float arithmetic`() {
        val tone = UniqueToneRenderer(true, 0xFF555555.toInt(), invert = false, blend = AvsBlendMode.REPLACE)

        assertEquals(0xFF303030.toInt(), tone.run(lit(147, 0, 0)))
    }

    @Test
    fun `mirror folds one half onto the other`() {
        val frame = AvsFrame(4, 2)
        frame[0, 0] = WHITE
        frame[1, 0] = WHITE

        MirrorRenderer(true, false, false, leftToRight = true, rightToLeft = false)
            .render(frame, AvsAudioFrame(), state())

        assertEquals("the left edge is mirrored on the right", WHITE, frame[3, 0])
        assertEquals(WHITE, frame[2, 0])
    }

    @Test
    fun `mirror can fold the other way, and top to bottom`() {
        val frame = AvsFrame(2, 4)
        frame[0, 0] = WHITE

        MirrorRenderer(true, topToBottom = true, bottomToTop = false, leftToRight = false, rightToLeft = false)
            .render(frame, AvsAudioFrame(), state())

        assertEquals(WHITE, frame[0, 3])
    }

    /** With on-beat random, nothing folds before the first beat. */
    @Test
    fun `a random mirror folds nothing until the first beat`() {
        val mirror =
            MirrorRenderer(
                true,
                topToBottom = true,
                bottomToTop = false,
                leftToRight = false,
                rightToLeft = false,
                onBeatRandom = true,
            )
        val frame = AvsFrame(2, 4)
        frame[0, 0] = WHITE

        mirror.render(frame, AvsAudioFrame(), state())

        assertEquals(AvsFrame.OPAQUE, frame[0, 3])
    }

    @Test
    fun `a beat rolls a random mirror's direction, and it stays until the next one`() {
        val mirror =
            MirrorRenderer(
                true,
                topToBottom = true,
                bottomToTop = false,
                leftToRight = false,
                rightToLeft = false,
                onBeatRandom = true,
                random = FixedRandom(1), // an odd roll: the coin flip lands on folding
            )
        val onBeat = AvsFrame(2, 4)
        onBeat[0, 0] = WHITE
        val after = AvsFrame(2, 4)
        after[0, 0] = WHITE

        mirror.render(onBeat, AvsAudioFrame(), state(beat = true))
        mirror.render(after, AvsAudioFrame(), state())

        assertEquals("the beat snaps the fold on", WHITE, onBeat[0, 3])
        assertEquals("the fold holds between beats", WHITE, after[0, 3])
    }

    /** The smooth transition crossfades through 4 bits per channel. */
    @Test
    fun `a smooth mirror transition blends in sixteenths`() {
        val mirror =
            MirrorRenderer(
                true,
                topToBottom = true,
                bottomToTop = false,
                leftToRight = false,
                rightToLeft = false,
                onBeatRandom = true,
                transitionDuration = 2,
                random = FixedRandom(1),
            )
        val onBeat = AvsFrame(2, 4)
        onBeat[0, 0] = WHITE
        val next = AvsFrame(2, 4)
        next[0, 0] = WHITE

        mirror.render(onBeat, AvsAudioFrame(), state(beat = true))
        mirror.render(next, AvsAudioFrame(), state())

        assertEquals("the beat frame is still at strength zero", AvsFrame.OPAQUE, onBeat[0, 3])
        assertEquals("one step in, white folds down at one sixteenth", 0xFF0F0F0F.toInt(), next[0, 3])
    }

    @Test
    fun `clear screen paints the frame, and only once when told`() {
        val frame = AvsFrame(2, 2)
        val once = ClearScreenRenderer(true, RED, AvsBlendMode.REPLACE, onlyFirst = true)

        once.render(frame, AvsAudioFrame(), state())
        assertTrue(frame.pixels.all { it == RED })

        frame.clear()
        once.render(frame, AvsAudioFrame(), state())
        assertTrue("only the first frame is cleared", frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    /** Only-first sits past the Map8 blend pair. */
    @Test
    fun `a fifty-fifty clear screen keeps clearing every frame`() {
        val body = int32(1) + int32(0x00FF00) + int32(0) + int32(1) + int32(0)
        val clear = ClearScreenRenderer.read(body)!!
        val frame = AvsFrame(2, 2, IntArray(4) { RED })

        clear.render(frame, AvsAudioFrame(), state())
        assertEquals("the first frame mixes the color in", 0xFF7F7F00.toInt(), frame[0, 0])

        frame.pixels.fill(RED)
        clear.render(frame, AvsAudioFrame(), state())
        assertEquals("the second frame is cleared too", 0xFF7F7F00.toInt(), frame[0, 0])
    }

    @Test
    fun `on beat clear paints every nth beat and not between them`() {
        val frame = AvsFrame(2, 2)
        val clear = OnBeatClearRenderer(RED, AvsBlendMode.REPLACE, everyBeats = 2)

        clear.render(frame, AvsAudioFrame(), state(beat = true))
        assertTrue("the first beat does not clear", frame.pixels.all { it == AvsFrame.OPAQUE })

        clear.render(frame, AvsAudioFrame(), state(beat = false))
        clear.render(frame, AvsAudioFrame(), state(beat = true))
        assertTrue(frame.pixels.all { it == RED })
    }

    /** e_onbeatclear.cpp gates on every_n_beats > 0: zero means never, not every beat. */
    @Test
    fun `an on beat clear of every zero beats never fires`() {
        val frame = AvsFrame(2, 2)
        val clear = OnBeatClearRenderer(RED, AvsBlendMode.REPLACE, everyBeats = 0)

        repeat(3) { clear.render(frame, AvsAudioFrame(), state(beat = true)) }

        assertTrue(frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    /** Cells are w/size wide and each takes the color of its center pixel, not its corner. */
    @Test
    fun `mosaic squares the picture into cells colored from their centers`() {
        val frame = AvsFrame(8, 8)
        frame[2, 2] = WHITE // the center of the first 4x4 cell

        MosaicRenderer(true, size = 2, beatSize = 2, growOnBeat = false, blend = AvsBlendMode.REPLACE)
            .render(frame, AvsAudioFrame(), state())

        assertEquals("the cell takes its center's color", WHITE, frame[0, 0])
        assertEquals(WHITE, frame[3, 3])
        assertEquals("the cell stops at its edge", AvsFrame.OPAQUE, frame[4, 4])
    }

    /** The size counts cells along each axis, so cells are non-square on non-square frames. */
    @Test
    fun `mosaic cells follow the frame's shape`() {
        val frame = AvsFrame(8, 4)
        frame[2, 1] = WHITE // the center of the first 4x2 cell

        MosaicRenderer(true, size = 2, beatSize = 2, growOnBeat = false, blend = AvsBlendMode.REPLACE)
            .render(frame, AvsAudioFrame(), state())

        assertEquals(WHITE, frame[0, 0])
        assertEquals("two cells down a four-tall frame makes two-row cells", AvsFrame.OPAQUE, frame[0, 2])
    }

    /** Grow-on-beat sits past the Map8 blend pair. */
    @Test
    fun `mosaic reads grow-on-beat from past the blend pair`() {
        val body =
            int32(1) + int32(2) + int32(8) + // enabled, two cells across, eight on a beat
                int32(0) + int32(0) + // the Map8 blend pair
                int32(1) + int32(2) // grow on beat, over two frames
        val mosaic = MosaicRenderer.read(body)!!
        val frame = AvsFrame(16, 16)
        frame[1, 1] = WHITE // the center of the first on-beat 2x2 cell

        mosaic.render(frame, AvsAudioFrame(), state(beat = true))

        // eight cells across a 16-wide frame is 2-pixel cells: the corner cell is white...
        assertEquals(WHITE, frame[0, 0])
        // ...and the next cell samples (3,3), which the off-beat size of two would have swept over
        assertEquals(AvsFrame.OPAQUE, frame[2, 2])
    }

    /** After a beat the size walks back linearly, |size - beatSize| / duration per frame. */
    @Test
    fun `mosaic ramps back to its normal size after a beat`() {
        val body = int32(1) + int32(2) + int32(8) + int32(0) + int32(0) + int32(1) + int32(2)
        val mosaic = MosaicRenderer.read(body)!!
        mosaic.render(patterned(), AvsAudioFrame(), state(beat = true))

        val stepped = patterned()
        mosaic.render(stepped, AvsAudioFrame(), state())

        // one step of |2-8|/2 = 3 back from 8: the frame after the beat renders at five cells
        val reference = patterned()
        MosaicRenderer(true, size = 5, beatSize = 5, growOnBeat = false, blend = AvsBlendMode.REPLACE)
            .render(reference, AvsAudioFrame(), state())
        assertEquals(reference.pixels.toList(), stepped.pixels.toList())
    }

    /** The x field is a band width: runs of x colored pixels alternate with runs left alone. */
    @Test
    fun `interleave paints alternating bands`() {
        val frame = AvsFrame(6, 6)

        InterleaveRenderer(true, everyX = 3, everyY = 0, colour = RED, blend = AvsBlendMode.REPLACE)
            .render(frame, AvsAudioFrame(), state())

        assertEquals(RED, frame[0, 2])
        assertEquals(RED, frame[2, 2])
        assertEquals("the second band of three is untouched", AvsFrame.OPAQUE, frame[3, 2])
    }

    /** Row bands toggle on a counter seeded with half the frame's remainder: e_interleave.cpp's phase centering. */
    @Test
    fun `interleave's row bands alternate from the first row`() {
        val frame = AvsFrame(4, 6)

        InterleaveRenderer(true, everyX = 0, everyY = 2, colour = RED, blend = AvsBlendMode.REPLACE)
            .render(frame, AvsAudioFrame(), state())

        val rows = (0 until 6).map { y -> frame[0, y] == RED }
        assertEquals(listOf(true, false, false, true, true, false), rows)
    }

    @Test
    fun `a negative stride disables interleave entirely`() {
        val frame = AvsFrame(6, 6)

        InterleaveRenderer(true, everyX = -4, everyY = 2, colour = RED, blend = AvsBlendMode.REPLACE)
            .render(frame, AvsAudioFrame(), state())

        assertTrue(frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    /** The beat fields sit past the Map8 blend pair. */
    @Test
    fun `interleave reads its beat fields from past the blend pair`() {
        val body =
            int32(1) + int32(3) + int32(0) + int32(0xFF0000) + // enabled, bands of 3 across, none down, red
                int32(0) + int32(0) + // the Map8 blend pair
                int32(1) + int32(5) + int32(0) + int32(1) // on beat: bands of 5 across, duration 1
        val interleave = InterleaveRenderer.read(body)!!
        val frame = AvsFrame(6, 6)

        interleave.render(frame, AvsAudioFrame(), state(beat = true))

        assertEquals("on a beat the first band is five wide", RED, frame[0, 1])
        assertEquals(RED, frame[4, 1])
        assertEquals(AvsFrame.OPAQUE, frame[5, 1])
    }

    /** The beat widths decay by (duration + 448) / 512 per frame, so intermediate widths appear. */
    @Test
    fun `interleave's beat bands decay back through intermediate widths`() {
        val interleave =
            InterleaveRenderer(
                true,
                everyX = 2,
                everyY = 0,
                colour = RED,
                blend = AvsBlendMode.REPLACE,
                onBeat = true,
                beatX = 8,
                beatY = 0,
                beatFrames = 0,
            )
        val onBeat = AvsFrame(8, 1)
        val second = AvsFrame(8, 1)
        val third = AvsFrame(8, 1)

        interleave.render(onBeat, AvsAudioFrame(), state(beat = true))
        interleave.render(second, AvsAudioFrame(), state())
        interleave.render(third, AvsAudioFrame(), state())

        assertTrue("the beat band spans the row", (0 until 8).all { onBeat[it, 0] == RED })
        // 8 decays to 7.25, truncated 7: seven filled, one untouched
        assertEquals(RED, second[6, 0])
        assertEquals(AvsFrame.OPAQUE, second[7, 0])
        // then 6.59, truncated 6, centered: five filled, three untouched
        assertEquals(RED, third[4, 0])
        assertEquals(AvsFrame.OPAQUE, third[5, 0])
    }

    /** AVS stores colors in Android's channel order, so red and blue are not swapped. */
    @Test
    fun `a config color keeps its channel order`() {
        assertEquals(0xFFFF0000.toInt(), AvsFrame.fromConfig(0xFF0000))
        assertEquals(0xFF0000FF.toInt(), AvsFrame.fromConfig(0x0000FF))
    }

    /** A preset's own math can aim a line at a million. */
    @Test
    fun `a line to the far distance draws its visible part`() {
        val frame = AvsFrame(32, 32)

        AvsDraw.line(frame, -1_000_000, 16, 1_000_000, 16, WHITE)

        // linedraw.cpp's horizontal path clamps to the last column and excludes
        // it, so a frame-crossing line leaves the final column dark
        assertTrue("the row is lit", (0 until 31).all { x -> frame[x, 16] == WHITE })
        assertEquals("the clamped far column stays dark", AvsFrame.OPAQUE, frame[31, 16])
        assertTrue(
            "only the row is lit",
            (0 until 32).all { y ->
                y == 16 || (0 until 32).all { x -> frame[x, y] == AvsFrame.OPAQUE }
            },
        )
    }

    @Test
    fun `a line that misses the frame entirely draws nothing`() {
        val frame = AvsFrame(16, 16)

        AvsDraw.line(frame, -500, -500, -400, 900, WHITE)
        AvsDraw.line(frame, 100, 0, 100, 15, WHITE)

        assertTrue(frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    @Test
    fun `a clipped diagonal enters at the corner and stops one pixel short of its endpoint`() {
        val frame = AvsFrame(16, 16)

        AvsDraw.line(frame, -16, -16, 15, 15, WHITE)

        assertEquals("the entry clip lands on the corner", WHITE, frame[0, 0])
        assertEquals(WHITE, frame[14, 14])
        // linedraw.cpp walks while (x1 < x2): the far endpoint's column is never drawn
        assertEquals("the far endpoint is excluded", AvsFrame.OPAQUE, frame[15, 15])
    }

    /** linedraw.cpp: `!dx` with `while (d++ < ye)` never entered, so not even one dot. */
    @Test
    fun `a zero length line draws nothing`() {
        val frame = AvsFrame(16, 16)

        AvsDraw.line(frame, 8, 8, 8, 8, WHITE, thickness = 3)

        assertTrue(frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    /** The span is offset by `width / 2`, so a thick line is centered, not grown down-right. */
    @Test
    fun `a thick horizontal line is centered on the ideal one`() {
        val frame = AvsFrame(16, 16)

        AvsDraw.line(frame, 2, 8, 12, 8, WHITE, thickness = 3)

        assertEquals("one row above the ideal", WHITE, frame[5, 7])
        assertEquals(WHITE, frame[5, 8])
        assertEquals("one row below the ideal", WHITE, frame[5, 9])
        assertEquals(AvsFrame.OPAQUE, frame[5, 6])
        assertEquals(AvsFrame.OPAQUE, frame[5, 10])
        assertEquals("the first column is drawn", WHITE, frame[2, 8])
        assertEquals(WHITE, frame[11, 8])
        assertEquals("the far column is excluded", AvsFrame.OPAQUE, frame[12, 8])
    }

    @Test
    fun `a thick vertical line is centered on the ideal one`() {
        val frame = AvsFrame(16, 16)

        AvsDraw.line(frame, 8, 2, 8, 12, WHITE, thickness = 3)

        assertEquals(WHITE, frame[7, 5])
        assertEquals(WHITE, frame[8, 5])
        assertEquals(WHITE, frame[9, 5])
        assertEquals(AvsFrame.OPAQUE, frame[6, 5])
        assertEquals(AvsFrame.OPAQUE, frame[10, 5])
        assertEquals(WHITE, frame[8, 2])
        assertEquals(WHITE, frame[8, 11])
        assertEquals("the far row is excluded", AvsFrame.OPAQUE, frame[8, 12])
    }

    /**
     * One perpendicular run per Bresenham step, each pixel blended once: an
     * additive thick diagonal must not double-brighten where square stamps
     * would overlap.
     */
    @Test
    fun `a thick additive line blends every pixel once`() {
        val frame = AvsFrame(16, 16)
        val dim = 0xFF0A0A0A.toInt()

        AvsDraw.line(frame, 0, 0, 10, 10, dim, thickness = 4, mode = AvsBlendMode.ADDITIVE)

        val values = frame.pixels.filter { it != AvsFrame.OPAQUE }.distinct()
        assertTrue("every lit pixel carries one blend's worth: $values", values == listOf(dim))
    }

    /** The original clamps line width to 255. */
    @Test
    fun `line width is clamped to 255 and a wider ask fills no further`() {
        val frame = AvsFrame(16, 16)

        AvsDraw.line(frame, 0, 8, 15, 8, WHITE, thickness = 9_999)

        assertTrue("the band covers the whole height", (0 until 16).all { y -> frame[7, y] == WHITE })
        assertEquals("the far column is still excluded", AvsFrame.OPAQUE, frame[15, 8])
    }

    // the blitters and the distance modifier are tested in ZoomComponentsTest

    @Test
    fun `every one of them refuses a body that runs out`() {
        assertNull(FastBrightnessRenderer.read(ByteArray(2)))
        assertNull(BrightnessRenderer.read(ByteArray(2)))
        assertNull(ColorfadeRenderer.read(ByteArray(2)))
        assertNull(ColorClipRenderer.read(ByteArray(2)))
        assertNull(UniqueToneRenderer.read(ByteArray(2)))
        assertNull(MirrorRenderer.read(ByteArray(2)))
        assertNull(ClearScreenRenderer.read(ByteArray(2)))
        assertNull(OnBeatClearRenderer.read(ByteArray(2)))
        assertNull(MosaicRenderer.read(ByteArray(2)))
        assertNull(InterleaveRenderer.read(ByteArray(2)))
        assertNull(BlitterFeedbackRenderer.read(ByteArray(2)))
        assertNull(RotoBlitterRenderer.read(ByteArray(2)))
    }

    private fun fast(level: Int) = FastBrightnessRenderer.read(int32(level))!!

    /** Runs a component over a one-pixel frame and gives back that pixel. */
    private fun AvsComponentRenderer.run(
        pixel: Int,
        beat: Boolean = false,
    ): Int {
        val frame = AvsFrame(1, 1, intArrayOf(pixel))
        render(frame, AvsAudioFrame(), state(beat))
        return frame[0, 0]
    }

    private fun lit(
        r: Int,
        g: Int,
        b: Int,
    ) = AvsFrame.OPAQUE or (r shl 16) or (g shl 8) or b

    private fun patterned() =
        AvsFrame(16, 16).also {
            for (i in 0 until 16) it[i, i] = WHITE
            for (x in 3..12) it[x, 5] = RED
        }

    private fun state(beat: Boolean = false) = AvsRenderState(AvsBuffers(8, 8)).also { it.beat = beat }

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    /** Hands out one value regardless of bound, so a coin flip lands where the test says. */
    private class FixedRandom(
        private val value: Int,
    ) : Random() {
        override fun nextInt(bound: Int) = value % bound
    }

    /** Plays back a scripted sequence of draws. */
    private class ScriptedRandom(
        private val values: List<Int>,
    ) : Random() {
        private var at = 0

        override fun nextInt(bound: Int) = values[at++] % bound
    }

    private companion object {
        val WHITE = 0xFFFFFFFF.toInt()
        val RED = 0xFFFF0000.toInt()
    }
}
