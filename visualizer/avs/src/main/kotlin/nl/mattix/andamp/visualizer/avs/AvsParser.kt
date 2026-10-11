// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/** A file [AvsParser] cannot read as a preset. The message says what stopped it. */
class AvsFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Reads the `.avs` binary format.
 *
 * A preset is a 24-byte `Nullsoft AVS Preset 0.2` header, a byte saying whether
 * each frame starts from black, and then a stream of components. Each component
 * is a `uint32` code, a 32-byte name if that code says APE, a `uint32` size, and
 * that many bytes of body. Effect List is the one code that nests: its body is
 * its own config followed by another component stream.
 *
 * This frames the components and decodes only the Effect List's config; every
 * other body is left to its component's reader. Transcribed from
 * grandchild/AVS-File-Decoder (MIT); see NOTICE.md.
 */
object AvsParser {
    private const val HEADER_LENGTH = 25
    private const val INT_SIZE = 4
    private const val APE_NAME_SIZE = 32
    private const val MODE_BIT = 0x80

    /**
     * How deep Effect Lists may nest.
     *
     * Each level costs a stack frame, and a crafted file with enough of them
     * would overflow the stack, which a `catch` of an exception does not see.
     */
    private const val MAX_DEPTH = 32

    /** The header's terminator, the last of its 24 bytes. */
    private const val HEADER_END = "\u001A"

    /**
     * Versions 0.2 and 0.1 frame the same way. The terminator is part of the
     * match, so a file whose 24th byte is something else is refused.
     */
    private val HEADERS =
        listOf(
            "Nullsoft AVS Preset 0.2$HEADER_END",
            "Nullsoft AVS Preset 0.1$HEADER_END",
        ).map { it.toByteArray(Charsets.ISO_8859_1) }

    /**
     * The "AVS 2.8+ Effect List Config" block an Effect List may carry before
     * its children: a marker, then a length-prefixed ns-eel snippet.
     */
    private val EFFECT_LIST_28 =
        byteArrayOf(0x00, 0x40, 0x00, 0x00) +
            "AVS 2.8+ Effect List Config".toByteArray(Charsets.ISO_8859_1) +
            // five nulls: the name pads to a 32-byte field like an APE's, and
            // the byte after it is already the code block's length
            ByteArray(5)

    fun parse(bytes: ByteArray): AvsPreset {
        if (HEADERS.none { bytes.size >= HEADER_LENGTH && bytes.startsAt(0, it) }) {
            throw AvsFormatException("not an AVS preset: no Nullsoft AVS Preset header")
        }
        return try {
            AvsPreset(
                clearEveryFrame = bytes[HEADER_LENGTH - 1].toInt() == 1,
                components = components(bytes, HEADER_LENGTH, bytes.size, depth = 0),
            )
        } catch (
            // a read past the end that the explicit guards did not catch is
            // reported as a format error, with the index in the cause
            @Suppress("TooGenericExceptionCaught")
            e: IndexOutOfBoundsException,
        ) {
            throw AvsFormatException("truncated or malformed preset: ${e.message}", e)
        }
    }

    /**
     * Walks a component stream between [from] and [until].
     *
     * A stream stops when fewer than two int32s are left, which can be before
     * [until]: some presets carry trailing bytes.
     */
    private fun components(
        bytes: ByteArray,
        from: Int,
        until: Int,
        depth: Int,
    ): List<AvsComponent> {
        val found = mutableListOf<AvsComponent>()
        var at = from
        while (at <= until - INT_SIZE * 2) {
            val code = bytes.int32(at)
            val isApe = code != AvsComponents.EFFECT_LIST && code >= AvsComponents.APE_MIN
            val nameSize = if (isApe) APE_NAME_SIZE else 0
            if (at + INT_SIZE * 2 + nameSize > until) {
                throw AvsFormatException("component at $at is cut off in the middle of its header")
            }
            val size = bytes.int32(at + INT_SIZE + nameSize)
            val body = at + INT_SIZE * 2 + nameSize
            if (size < 0 || body + size > until) {
                throw AvsFormatException("component at $at declares $size bytes, past the ${until - body} it has")
            }
            found += component(bytes, code, isApe, at, body, size, depth)
            at = body + size
        }
        return found
    }

    private fun component(
        bytes: ByteArray,
        code: Int,
        isApe: Boolean,
        at: Int,
        body: Int,
        size: Int,
        depth: Int,
    ): AvsComponent {
        if (code == AvsComponents.EFFECT_LIST && depth >= MAX_DEPTH) {
            throw AvsFormatException("effect lists nested more than $MAX_DEPTH deep")
        }
        val blob = bytes.copyOfRange(body, body + size)
        return when {
            isApe -> ape(bytes.nullTerminated(at + INT_SIZE, APE_NAME_SIZE), blob)
            code == AvsComponents.EFFECT_LIST -> effectList(bytes, body, size, blob, depth)
            else -> AvsComponents[code]?.let { AvsComponent.Builtin(code, it, blob) } ?: AvsComponent.Unknown(code, blob)
        }
    }

    /** A plug-in by name, or the built-in that has since replaced it; see [AvsComponents.replacing]. */
    private fun ape(
        name: String,
        blob: ByteArray,
    ): AvsComponent {
        val builtin = AvsComponents.replacing(name) ?: return AvsComponent.Ape(name, blob)
        return AvsComponents[builtin]?.let { AvsComponent.Builtin(builtin, it, blob) } ?: AvsComponent.Ape(name, blob)
    }

    /**
     * An Effect List's body: its own config, then its children.
     *
     * Transcribed from vis_avs `r_list.cpp` `load_config` (BSD; see NOTICE.md).
     * The oldest lists store one mode byte and their children follow it. A
     * newer list sets the top bit of that byte and stores the whole mode as an
     * int32 after it; the top byte of that mode is how long the extended
     * settings are, and each of those is read only when the length covers it.
     */
    private fun effectList(
        bytes: ByteArray,
        body: Int,
        size: Int,
        blob: ByteArray,
        depth: Int,
    ): AvsComponent.EffectList {
        val end = body + size
        var mode = if (size > 0) bytes[body].toInt() and 0xFF else 0
        var at = body + minOf(size, 1)
        if (mode and MODE_BIT != 0) {
            if (size < 1 + INT_SIZE) throw AvsFormatException("effect list with a $size-byte body cannot hold its mode")
            mode = (mode and MODE_BIT.inv()) or bytes.int32(at)
            at += INT_SIZE
        }
        // offsets below are from the start of the body, as the original counts them
        val extended = ((mode ushr EXTENDED_SHIFT) and 0xFF) + EXTENDED_FROM
        val settings = IntArray(EXTENDED_FIELDS)
        if (extended > EXTENDED_FROM) {
            for (field in 0 until EXTENDED_FIELDS) {
                // the last two were added later and are read four bytes more cautiously
                val limit = if (field < EXTENDED_FIELDS - 2) extended else extended - INT_SIZE
                if (at - body >= limit || at + INT_SIZE > end) continue
                settings[field] = bytes.int32(at)
                at += INT_SIZE
            }
        }
        var content = at
        if (bytes.startsAt(content, EFFECT_LIST_28)) content = afterCodeBlock(bytes, content + EFFECT_LIST_28.size, end)
        if (content > end) {
            throw AvsFormatException("effect list config runs ${content - end} bytes past its own body")
        }
        return AvsComponent.EffectList(
            config = effectListConfig(mode, settings),
            components = components(bytes, content, end, depth + 1),
            body = blob,
        )
    }

    /**
     * Where an Effect List's children start, past the code block whose length
     * is at [codeAt].
     *
     * The length itself is checked against the room left: a negative one would
     * land back inside the list's own header, and a huge one wraps around.
     */
    private fun afterCodeBlock(
        bytes: ByteArray,
        codeAt: Int,
        until: Int,
    ): Int {
        val length = bytes.int32(codeAt)
        val room = until - (codeAt + INT_SIZE)
        if (length < 0 || length > room) {
            throw AvsFormatException("effect list code block declares $length bytes, past the $room it has")
        }
        return codeAt + INT_SIZE + length
    }

    /**
     * An Effect List's settings. The flags and the two blend modes are bits of
     * [mode]; [extended] holds the later settings in file order, zero where the
     * file did not carry one.
     */
    private fun effectListConfig(
        mode: Int,
        extended: IntArray,
    ) = AvsEffectListConfig(
        enabled = mode and 0x02 == 0,
        clearFrame = mode and 0x01 != 0,
        input = AvsBlendMode.incoming((mode ushr IN_BLEND_SHIFT) and BLEND_MASK),
        output = AvsBlendMode.outgoing((mode ushr OUT_BLEND_SHIFT) and BLEND_MASK),
        inAdjust = extended[IN_ADJUST],
        outAdjust = extended[OUT_ADJUST],
        inBuffer = extended[IN_BUFFER],
        outBuffer = extended[OUT_BUFFER],
        inBufferInvert = extended[IN_INVERT] == 1,
        outBufferInvert = extended[OUT_INVERT] == 1,
        onlyOnBeat = extended[ON_BEAT] == 1,
        onBeatFrames = extended[ON_BEAT_FRAMES],
    )

    private const val EXTENDED_SHIFT = 24
    private const val EXTENDED_FROM = 5
    private const val EXTENDED_FIELDS = 8
    private const val IN_BLEND_SHIFT = 8
    private const val OUT_BLEND_SHIFT = 16
    private const val BLEND_MASK = 31
    private const val IN_ADJUST = 0
    private const val OUT_ADJUST = 1
    private const val IN_BUFFER = 2
    private const val OUT_BUFFER = 3
    private const val IN_INVERT = 4
    private const val OUT_INVERT = 5
    private const val ON_BEAT = 6
    private const val ON_BEAT_FRAMES = 7

    private fun ByteArray.int32(at: Int): Int =
        (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.nullTerminated(
        at: Int,
        length: Int,
    ): String {
        val end = (at until at + length).firstOrNull { this[it].toInt() == 0 } ?: (at + length)
        return String(this, at, end - at, Charsets.ISO_8859_1)
    }

    private fun ByteArray.startsAt(
        at: Int,
        prefix: ByteArray,
    ): Boolean {
        if (at < 0 || at + prefix.size > size) return false
        return prefix.indices.all { this[at + it] == prefix[it] }
    }
}
