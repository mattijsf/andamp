// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * A parsed `.avs` preset: a flag, and the components in the order AVS runs them.
 *
 * A preset is a stack of components, each with its own binary config, and Effect
 * Lists nest, so this is a tree. See docs/avs-integration.md.
 */
data class AvsPreset(
    /** Whether each frame starts from black; otherwise it starts from the last one. */
    val clearEveryFrame: Boolean,
    val components: List<AvsComponent>,
) {
    /** Every component in the tree, at any depth, a list before its children. */
    fun flatten(): List<AvsComponent> {
        val all = mutableListOf<AvsComponent>()

        fun walk(component: AvsComponent) {
            all += component
            component.children().forEach(::walk)
        }
        components.forEach(::walk)
        return all
    }
}

/**
 * An Effect List's own settings: whether it runs, whether it starts from black,
 * and how its canvas is joined to the frame around it on the way in and out.
 */
data class AvsEffectListConfig(
    val enabled: Boolean = true,
    /** Its canvas starts each frame from black; otherwise it keeps what it held. */
    val clearFrame: Boolean = false,
    val input: AvsBlendMode = AvsBlendMode.IGNORE,
    val output: AvsBlendMode = AvsBlendMode.REPLACE,
    val inAdjust: Int = 0,
    val outAdjust: Int = 0,
    /** Which global buffer the [AvsBlendMode.BUFFER] modes mean, counted from one. */
    val inBuffer: Int = 0,
    val outBuffer: Int = 0,
    val inBufferInvert: Boolean = false,
    val outBufferInvert: Boolean = false,
    val onlyOnBeat: Boolean = false,
    val onBeatFrames: Int = 0,
)

/**
 * One component.
 *
 * The body bytes are kept undecoded; each component's reader decodes its own.
 */
sealed interface AvsComponent {
    /** The name AVS shows for it, which is also what the engine finds its renderer by. */
    val name: String

    val body: ByteArray

    fun children(): List<AvsComponent> = emptyList()

    /** One of AVS's own, identified by [AvsComponents]. */
    data class Builtin(
        val id: Int,
        val type: AvsComponentType,
        override val body: ByteArray,
    ) : AvsComponent {
        override val name get() = type.name

        override fun equals(other: Any?) = other is Builtin && id == other.id && body.contentEquals(other.body)

        override fun hashCode() = 31 * id + body.contentHashCode()
    }

    /**
     * The one component that nests: it renders its children, optionally into a
     * buffer of its own, and blends the result back.
     */
    data class EffectList(
        val config: AvsEffectListConfig,
        val components: List<AvsComponent>,
        override val body: ByteArray,
    ) : AvsComponent {
        override val name get() = "Effect List"

        override fun children() = components

        override fun equals(other: Any?) =
            other is EffectList &&
                config == other.config &&
                components == other.components &&
                body.contentEquals(other.body)

        override fun hashCode() = 31 * (31 * config.hashCode() + components.hashCode()) + body.contentHashCode()
    }

    /**
     * A third-party component, which names itself in 32 bytes instead of taking
     * an id.
     */
    data class Ape(
        override val name: String,
        override val body: ByteArray,
    ) : AvsComponent {
        override fun equals(other: Any?) = other is Ape && name == other.name && body.contentEquals(other.body)

        override fun hashCode() = 31 * name.hashCode() + body.contentHashCode()
    }

    /** A component whose id is not in [AvsComponents]. */
    data class Unknown(
        val id: Int,
        override val body: ByteArray,
    ) : AvsComponent {
        override val name get() = "Unknown($id)"

        override fun equals(other: Any?) = other is Unknown && id == other.id && body.contentEquals(other.body)

        override fun hashCode() = 31 * id + body.contentHashCode()
    }
}
