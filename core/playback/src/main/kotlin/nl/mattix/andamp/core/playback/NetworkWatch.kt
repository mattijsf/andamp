// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

/**
 * Whether there is a network to reach a server over, and word when that changes.
 *
 * An interface, so that reconnect logic can be driven by a test. A caller watches only
 * while a reconnect is waiting, so the system does not wake the process for every network
 * change.
 */
interface NetworkWatch {
    /** Whether a usable, validated network is there right now. */
    val online: Boolean

    /**
     * Starts reporting: [onChange] hears `false` when the network goes and `true` when one
     * arrives, including a different network taking over from one that was already there.
     * Calling it while already watching does nothing.
     */
    fun watch(onChange: (online: Boolean) -> Unit)

    /** Stops reporting. Safe when not watching. */
    fun unwatch()

    /** No way to know: always online, and never reports a change. Retries then run on the clock alone. */
    object Assumed : NetworkWatch {
        override val online = true

        override fun watch(onChange: (online: Boolean) -> Unit) = Unit

        override fun unwatch() = Unit
    }
}
