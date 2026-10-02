// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

/**
 * Lowered code: what the engine runs, once a [GraphSpec] has been checked and arranged.
 *
 * Parallel arrays and not a list of node objects, because one object per node would be one
 * virtual call per node per frame.
 *
 * Everything here is derived by the compiler from a validated graph.
 */
@Suppress("LongParameterList") // one parameter per parallel array; see the KDoc
internal class Tape(
    /** One opcode per instruction. */
    @JvmField val op: IntArray,
    /** The bus slot each instruction writes. */
    @JvmField val dst: IntArray,
    /** Operand slots, [ARGS] per instruction. */
    @JvmField val args: IntArray,
    /** State base, or delay-line id, per instruction. */
    @JvmField val state: IntArray,
    /** A second small integer: a math function, a filter shape, an envelope kind or a noise seed. */
    @JvmField val aux: IntArray,
    /** The bus as it starts: the input and parameter slots, then the pooled constants. */
    @JvmField val constants: FloatArray,
    @JvmField val inSlot: IntArray,
    /** One bus slot per declared parameter, written by the engine as the parameter moves. */
    @JvmField val paramSlot: IntArray,
    /** The fraction of the remaining distance each parameter moves per control tick. */
    @JvmField val paramStep: FloatArray,
    @JvmField val paramDefault: FloatArray,
    @JvmField val outSlot: IntArray,
    /** Instructions below this run once per control tick; the rest run every frame. */
    @JvmField val controlEnd: Int,
    @JvmField val busSize: Int,
    @JvmField val stateSize: Int,
    /** Where each delay line starts in the shared memory array. */
    @JvmField val lineOffset: IntArray,
    @JvmField val lineLength: IntArray,
    @JvmField val memorySize: Int,
    @JvmField val sampleRate: Int,
    /** Frames the engine may run in one pass; see [GraphSpec.safeBlock]. */
    @JvmField val block: Int,
) {
    companion object {
        /** Operand slots per instruction. */
        const val ARGS = 3
    }
}

/** Dense opcodes, so that the dispatch can compile to a jump table. */
internal object Op {
    const val ADD = 0
    const val SUB = 1
    const val MUL = 2
    const val DIV = 3
    const val MIN = 4
    const val MAX = 5
    const val CROSSFADE = 6
    const val CLIP = 7
    const val SANITISE = 8
    const val MATH = 9
    const val GAIN = 10
    const val ONEPOLE = 11
    const val ALLPASS1 = 12

    /** Writes coefficients into state; control or audio rate, depending on its inputs. */
    const val BIQUAD_COEFFICIENTS = 13
    const val BIQUAD = 14
    const val LFO_SINE = 15
    const val LFO_TRIANGLE = 16
    const val ENVELOPE = 17
    const val SOFTCLIP = 18

    /** Reads a line, interpolating between neighbors. */
    const val DELAY_READ_LINEAR = 19

    /** Reads the nearest sample. */
    const val DELAY_READ_NEAREST = 20

    /** Writes a line and advances it. Always placed after every reader. */
    const val DELAY_WRITE = 21
    const val TAP_READ = 22
    const val TAP_WRITE = 23
    const val NOISE = 24
}
