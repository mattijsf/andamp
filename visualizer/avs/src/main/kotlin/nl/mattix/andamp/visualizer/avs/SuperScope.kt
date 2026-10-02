// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/** Where a scope takes its numbers from. */
enum class AvsAudioSource { WAVEFORM, SPECTRUM }

enum class AvsAudioChannel { LEFT, RIGHT, CENTER }

/**
 * Super Scope's settings: four pieces of ns-eel, a palette, and where the
 * numbers come from.
 */
data class SuperScopeConfig(
    val init: String = "",
    val perFrame: String = "",
    val onBeat: String = "",
    val perPoint: String = "",
    val source: AvsAudioSource = AvsAudioSource.WAVEFORM,
    val channel: AvsAudioChannel = AvsAudioChannel.CENTER,
    /**
     * The raw stored int, not a bool: it becomes the `drawmode` variable's
     * per-frame value, and anything nonzero draws lines (vis_avs
     * `e_superscope.cpp:192` treats `< 0.00001` as dots and everything else as
     * lines, so a file holding 2 is a lines scope).
     */
    val drawMode: Int = 1,
    /**
     * Cycled through over time. A scope with no colors is inert: nothing draws
     * and its code does not run (`e_superscope.cpp:109`).
     */
    val colours: List<Int> = listOf(WHITE),
) {
    companion object {
        internal const val WHITE = 0xFFFFFFFF.toInt()
    }
}

/**
 * Reads a Super Scope's body.
 *
 * Two layouts. The current one starts with a version byte of 1 and stores its
 * four code sections as a length then that many bytes; the older one has no
 * version byte and four fixed 256-byte slots. Field order and offsets
 * transcribed from grandchild/AVS-File-Decoder (MIT) and checked against
 * vis_avs `e_superscope.cpp` `load_legacy` (BSD); see NOTICE.md for both.
 *
 * Everything is read through a [Cursor], which records whether it ran off the
 * end, so one check after the last field replaces a bounds check per field.
 */
object SuperScopeReader {
    private const val VERSION_CURRENT = 1
    private const val LEGACY_CODE_SIZE = 256
    private const val SECTIONS = 4

    /** The file's order is per-point, per-frame, on-beat, init, which is not the order they run in. */
    fun read(body: ByteArray): SuperScopeConfig? {
        val cursor = Cursor(body)
        val current = cursor.peek() == VERSION_CURRENT
        if (current) cursor.skip(1)

        val code = List(SECTIONS) { if (current) cursor.sizedText() else cursor.fixedText(LEGACY_CODE_SIZE) }
        val flags = cursor.int32()
        // a count past 16 loads no colors and consumes no color ints: the
        // next field reads from where they would begin, as in vis_avs
        // e_superscope.cpp:295-301 (the count is unsigned there, so a
        // negative here is also oversized). No colors means an inert scope.
        val declared = cursor.int32()
        val colours = if (declared in 0..MAX_COLOURS) List(declared) { AvsFrame.fromConfig(cursor.int32()) } else emptyList()
        val drawMode = cursor.int32()

        return if (!cursor.ok) {
            null
        } else {
            SuperScopeConfig(
                init = code[3],
                perFrame = code[1],
                onBeat = code[2],
                perPoint = code[0],
                channel = channelOf(flags and 0x03),
                source = if (flags and 0x04 != 0) AvsAudioSource.SPECTRUM else AvsAudioSource.WAVEFORM,
                drawMode = drawMode,
                colours = colours,
            )
        }
    }

    private fun channelOf(bits: Int) =
        when (bits) {
            0 -> AvsAudioChannel.LEFT
            1 -> AvsAudioChannel.RIGHT
            else -> AvsAudioChannel.CENTER
        }

    /**
     * A walk through a component's body that cannot run off the end.
     *
     * Reading past what is there sets [ok] to false and answers zero, so the
     * reader above reads straight through and checks once at the end.
     */
    private class Cursor(
        private val bytes: ByteArray,
    ) {
        var ok = true
            private set

        private var at = 0

        fun peek(): Int = if (at < bytes.size) bytes[at].toInt() else fail()

        fun skip(count: Int) {
            at = (at + count).coerceIn(0, bytes.size)
        }

        fun int32(): Int {
            if (at + INT_SIZE > bytes.size) return fail()
            val value =
                (bytes[at].toInt() and 0xFF) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 3].toInt() and 0xFF) shl 24)
            at += INT_SIZE
            return value
        }

        /** A length, then that many bytes, of which the text stops at the first null. */
        fun sizedText(): String {
            val length = int32()
            if (length < 0) {
                fail()
                return ""
            }
            return fixedText(length)
        }

        fun fixedText(length: Int): String {
            // compared against the room left: for a length near the top of an
            // int, an end offset would wrap to a small number and pass
            if (length < 0 || length > bytes.size - at) {
                fail()
                return ""
            }
            val end = (at until at + length).firstOrNull { bytes[it].toInt() == 0 } ?: (at + length)
            val text = String(bytes, at, end - at, Charsets.ISO_8859_1)
            at += length
            return text
        }

        private fun fail(): Int {
            ok = false
            return 0
        }

        private companion object {
            const val INT_SIZE = 4
        }
    }

    /** AVS's own limit: a count past this loads nothing (e_superscope.cpp:295). */
    private const val MAX_COLOURS = 16
}
