// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import java.util.Random

/** A font as a preset names one: the face, its height in pixels, and its weight and slant. */
data class AvsFont(
    val face: String = "",
    val height: Int = DEFAULT_HEIGHT,
    val bold: Boolean = false,
    val italic: Boolean = false,
) {
    private companion object {
        const val DEFAULT_HEIGHT = 16
    }
}

/** One line of text as pixels: where the letters are, in a box that just holds them. */
class AvsTextMask(
    val width: Int,
    val height: Int,
    /** Row by row, true where a letter covers the pixel. */
    val covered: BooleanArray,
)

/**
 * Draws a line of text. The engine has no fonts of its own, so the view
 * supplies this; without it Text draws nothing. Null for text that has no
 * pixels, such as an empty line.
 */
fun interface AvsTextPainter {
    fun paint(
        text: String,
        font: AvsFont,
    ): AvsTextMask?

    companion object {
        val NONE = AvsTextPainter { _, _ -> null }
    }
}

/**
 * Words over the frame, one at a time.
 *
 * Transcribed from vis_avs `r_text.cpp` (BSD; see NOTICE.md). The text is a
 * list of words or lines separated by semicolons. The next one is shown every
 * so many frames, or on every beat and then only for so many frames after it.
 * It sits where the preset aligns it, moved by a percentage of the frame, or at
 * a random place that keeps it on the frame. An outline is the same text drawn
 * around it in a second color, a shadow the same text once, down and to the
 * right.
 *
 * `$(title)`, `$(playpos)`, `$(playlen)` and `$(reg00)` stand for the song and
 * the preset's shared values in AVS. This engine is not told those, so they
 * are left out of the line.
 *
 * The font's height is the preset's, in pixels, scaled by how much narrower
 * this frame is than the 320 pixels AVS's window starts at.
 */
internal class TextRenderer(
    private val settings: Settings,
    private val random: Random = Random(),
) : AvsComponentRenderer {
    internal data class Settings(
        val enabled: Boolean = true,
        val colour: Int = WHITE,
        val blend: AvsBlendMode = AvsBlendMode.REPLACE,
        val onBeat: Boolean = false,
        val insertBlanks: Boolean = false,
        val randomPlace: Boolean = false,
        val vertical: Int = MIDDLE,
        val horizontal: Int = CENTER,
        val beatFrames: Int = 15,
        val frames: Int = 15,
        val font: AvsFont = AvsFont(),
        val text: String = "",
        val outline: Boolean = false,
        val outlineColour: Int = AvsFrame.OPAQUE,
        val shiftX: Int = 0,
        val shiftY: Int = 0,
        val outlineSize: Int = 1,
        val randomWord: Boolean = false,
        val shadow: Boolean = false,
    )

    private val words = settings.text.split(';').map(::withoutFields)
    private var word = 0
    private var blankTurn = 0
    private var framesShown = 0
    private var beatFramesLeft = 0
    private var horizontal = settings.horizontal
    private var vertical = settings.vertical
    private var shiftX = settings.shiftX
    private var shiftY = settings.shiftY

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!settings.enabled) return
        val line = nextLine(state.beat)
        val font = settings.font.copy(height = scaled(settings.font.height, frame.width))
        val mask = maskFor(line, font, state.text)
        // a word that has just come up is placed: its count is at the start
        val fresh = if (settings.onBeat) state.beat && beatFramesLeft == settings.beatFrames else framesShown >= settings.frames
        if (fresh) {
            framesShown = 0
            place(frame, mask)
        }
        if (settings.onBeat) {
            // on the beat, the word shows only for the frames after one
            if (beatFramesLeft > 0 && mask != null) draw(frame, mask)
            if (beatFramesLeft > 0) beatFramesLeft--
        } else {
            if (mask != null) draw(frame, mask)
            framesShown++
        }
    }

    /** Moves on to the next word when it is time, and answers the line to show: a blank between words if asked. */
    private fun nextLine(beat: Boolean): String {
        val beatStarts = settings.onBeat && beat && beatFramesLeft == 0
        if (beatStarts || (!settings.onBeat && framesShown >= settings.frames)) {
            if (!(settings.insertBlanks && blankTurn % 2 == 0)) {
                word = if (settings.randomWord) random.nextInt(words.size) else (word + 1) % words.size
            }
            blankTurn = (blankTurn + 1) % 2
        }
        if (beatStarts) beatFramesLeft = settings.beatFrames
        return if (settings.insertBlanks && blankTurn == 0) "" else words[word]
    }

    private var painted: Triple<String, AvsFont, AvsTextMask?>? = null

    /** The line as pixels, drawn again only when the line or the font changes. */
    private fun maskFor(
        line: String,
        font: AvsFont,
        painter: AvsTextPainter,
    ): AvsTextMask? {
        if (line.isEmpty()) return null
        painted?.let { (lastLine, lastFont, mask) -> if (lastLine == line && lastFont == font) return mask }
        return painter.paint(line, font).also { painted = Triple(line, font, it) }
    }

    /** A random place that keeps the line on the frame, or back to where the preset put it. */
    private fun place(
        frame: AvsFrame,
        mask: AvsTextMask?,
    ) {
        if (settings.randomPlace && mask != null) {
            horizontal = LEFT
            vertical = TOP
            val roomX = ((frame.width - mask.width) * PERCENT / frame.width)
            val roomY = ((frame.height - mask.height) * PERCENT / frame.height)
            if (roomX > 0) shiftX = random.nextInt(roomX)
            if (roomY > 0) shiftY = random.nextInt(roomY)
        } else {
            horizontal = settings.horizontal
            vertical = settings.vertical
            shiftX = settings.shiftX
            shiftY = settings.shiftY
        }
    }

    private fun draw(
        frame: AvsFrame,
        mask: AvsTextMask,
    ) {
        val left =
            when (horizontal) {
                LEFT -> 0
                RIGHT -> frame.width - mask.width
                else -> (frame.width - mask.width) / 2
            } + shiftX * frame.width / PERCENT
        val top =
            when (vertical) {
                TOP -> 0
                BOTTOM -> frame.height - mask.height
                else -> (frame.height - mask.height) / 2
            } + shiftY * frame.height / PERCENT
        val size = settings.outlineSize
        if (settings.outline) {
            AROUND.forEach { (dx, dy) -> stamp(frame, mask, left + dx * size, top + dy * size, settings.outlineColour) }
        } else if (settings.shadow) {
            stamp(frame, mask, left + size, top + size, settings.outlineColour)
        }
        stamp(frame, mask, left, top, settings.colour)
    }

    private fun stamp(
        frame: AvsFrame,
        mask: AvsTextMask,
        left: Int,
        top: Int,
        colour: Int,
    ) {
        for (y in maxOf(0, -top) until minOf(mask.height, frame.height - top)) {
            for (x in maxOf(0, -left) until minOf(mask.width, frame.width - left)) {
                if (!mask.covered[y * mask.width + x]) continue
                val at = (top + y) * frame.width + left + x
                frame.pixels[at] = AvsBlend.pixel(settings.blend, colour, frame.pixels[at])
            }
        }
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val PERCENT = 100
        private const val LEFT = 0
        private const val CENTER = 1
        private const val RIGHT = 2
        private const val TOP = 0
        private const val MIDDLE = 4
        private const val BOTTOM = 8
        private const val AVS_WINDOW = 320

        /** The eight places around a letter that an outline is drawn at. */
        private val AROUND = listOf(-1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)
        private const val SMALLEST_FONT = 6

        /** Windows' CHOOSEFONT and LOGFONT, which the body stores whole. */
        private const val CHOOSEFONT_SIZE = 60
        private const val LOGFONT_SIZE = 60
        private const val LOGFONT_WEIGHT = 16
        private const val LOGFONT_ITALIC = 20
        private const val LOGFONT_FACE = 28
        private const val FACE_SIZE = 32
        private const val BOLD_FROM = 600
        private const val INT_SIZE = 4

        private val FIELD = Regex("""\$\((title|playpos|playlen|reg\d\d)[^)]*\)""", RegexOption.IGNORE_CASE)

        /** [word] without the fields AVS fills in from the player and the preset's shared values. */
        internal fun withoutFields(word: String): String = FIELD.replace(word, "")

        /** A font height for a frame [width] wide: the preset's, shrunk as the frame is narrower than AVS's own. */
        internal fun scaled(
            height: Int,
            width: Int,
        ): Int = if (width >= AVS_WINDOW) height else (height * width / AVS_WINDOW).coerceAtLeast(minOf(height, SMALLEST_FONT))

        fun read(body: ByteArray): TextRenderer {
            val reader = BodyReader(body)
            val start = Settings()
            val enabled = reader.int32Or(1) != 0
            val colour = AvsFrame.fromConfig(reader.int32Or(WHITE))
            val additive = reader.int32Or(0) != 0
            val average = reader.int32Or(0) != 0
            val onBeat = reader.int32Or(0) != 0
            val insertBlanks = reader.int32Or(0) != 0
            val randomPlace = reader.int32Or(0) != 0
            val vertical = reader.int32Or(MIDDLE)
            val horizontal = reader.int32Or(CENTER)
            val beatFrames = reader.int32Or(start.beatFrames)
            val frames = reader.int32Or(start.frames)
            if (reader.has(CHOOSEFONT_SIZE)) reader.skip(CHOOSEFONT_SIZE)
            val font = if (reader.has(LOGFONT_SIZE)) font(reader) else AvsFont()
            val text = reader.string()
            return TextRenderer(
                Settings(
                    enabled = enabled,
                    colour = colour,
                    blend =
                        when {
                            additive -> AvsBlendMode.ADDITIVE
                            average -> AvsBlendMode.FIFTY_FIFTY
                            else -> AvsBlendMode.REPLACE
                        },
                    onBeat = onBeat,
                    insertBlanks = insertBlanks,
                    randomPlace = randomPlace,
                    vertical = vertical,
                    horizontal = horizontal,
                    beatFrames = beatFrames,
                    frames = frames,
                    font = font,
                    text = text,
                    outline = reader.int32Or(0) != 0,
                    outlineColour = AvsFrame.fromConfig(reader.int32Or(0)),
                    shiftX = reader.int32Or(0),
                    shiftY = reader.int32Or(0),
                    outlineSize = reader.int32Or(1),
                    randomWord = reader.int32Or(0) != 0,
                    shadow = reader.int32Or(0) != 0,
                ),
            )
        }

        /** The LOGFONT at the reader's place: a height (negative for the letters alone), a weight, a slant and a face. */
        private fun font(reader: BodyReader): AvsFont {
            val height = reader.int32()
            reader.skip(LOGFONT_WEIGHT - INT_SIZE)
            val weight = reader.int32()
            val italic = reader.byte() != 0
            reader.skip(LOGFONT_FACE - LOGFONT_ITALIC - 1)
            val face = reader.cStringWithin(FACE_SIZE)
            return AvsFont(face, if (height == 0) AvsFont().height else kotlin.math.abs(height), weight >= BOLD_FROM, italic)
        }
    }
}
